package com.itantra.tts

import com.itantra.audio.AudioSpec
import com.itantra.codec.Intent
import com.itantra.codec.Packet
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Turns a received packet into audio (PRD F-20).
 *
 * The seam for Phase 5. Everything downstream — the alert policy, the volume override,
 * the playback path — is written against this interface, so introducing the real
 * synthesiser later means constructing a different object and changing nothing else.
 * The same trick as [com.itantra.transport.Transport], for the same reason.
 */
interface SpeechRenderer {
    /** Name for the metrics readout, so it is obvious which renderer produced the audio. */
    val name: String

    /** Synthesise the packet's payload. 16 kHz mono PCM. */
    fun render(packet: Packet): ShortArray
}

/**
 * Placeholder renderer: an attention tone, not speech.
 *
 * This exists so Phase 4's alert path can be built and demonstrated end to end before
 * any speech model is integrated — the volume override, the audio focus grab and the
 * non-interruptible guarantee are all real and testable with a tone as the payload.
 *
 * It is labelled clearly and reported by name in the UI. Presenting a beep as though it
 * were the finished text-to-speech would be dishonest, and Phase 5 deletes this.
 */
class AlertToneRenderer : SpeechRenderer {

    override val name = "Tone (placeholder)"

    override fun render(packet: Packet): ShortArray {
        // Length loosely tracks the message so a long sentence does not become a blip.
        val perSymbolMs = 18
        val bodyMs = (packet.payload.size * perSymbolMs).coerceIn(400, 4_000)

        return when (packet.intent) {
            // Urgent two-tone alternation, the shape of an emergency signal.
            Intent.DISTRESS -> alternating(bodyMs, 880.0, 660.0, segmentMs = 180)
            Intent.ALERT -> alternating(bodyMs, 740.0, 590.0, segmentMs = 320)
            else -> Tone.generate(520.0, min(bodyMs, 700), amplitude = 0.35)
        }
    }

    private fun alternating(
        totalMs: Int,
        highHz: Double,
        lowHz: Double,
        segmentMs: Int,
    ): ShortArray {
        val segments = (totalMs / segmentMs).coerceAtLeast(2)
        val parts = (0 until segments).map { i ->
            Tone.generate(
                freqHz = if (i % 2 == 0) highHz else lowHz,
                durationMs = segmentMs,
                amplitude = 0.55,
            )
        }
        val out = ShortArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) {
            p.copyInto(out, at)
            at += p.size
        }
        return out
    }
}

/** Tone synthesis. Pure arithmetic, no Android, so it can be unit-tested. */
object Tone {

    /** Fade length at each end. Without it every segment starts with an audible click. */
    private const val FADE_MS = 8

    private const val MAX_SAMPLE = 32767.0
    private const val MIN_SAMPLE = -32767.0

    /**
     * @param amplitude 0..1, as a fraction of full scale. Kept below 1.0 so summing or
     *   later gain does not clip.
     */
    fun generate(freqHz: Double, durationMs: Int, amplitude: Double = 0.5): ShortArray {
        require(durationMs >= 0) { "duration must not be negative" }
        require(amplitude in 0.0..1.0) { "amplitude out of range: $amplitude" }

        val n = AudioSpec.SAMPLE_RATE * durationMs / 1000
        if (n == 0) return ShortArray(0)

        val peak = amplitude * Short.MAX_VALUE
        val fade = min(AudioSpec.SAMPLE_RATE * FADE_MS / 1000, n / 2)

        return ShortArray(n) { i ->
            val phase = 2.0 * PI * freqHz * i / AudioSpec.SAMPLE_RATE
            val envelope = when {
                fade == 0 -> 1.0
                i < fade -> raisedCosine(i.toDouble() / fade)
                i >= n - fade -> raisedCosine((n - 1 - i).toDouble() / fade)
                else -> 1.0
            }
            // Clamp before narrowing. Without this, a value one unit above the range
            // would wrap to full-scale negative — a loud crack instead of a quiet
            // sample. Cannot happen at amplitude <= 1.0, but the conversion should not
            // depend on that invariant holding forever.
            val v = sin(phase) * peak * envelope
            v.coerceIn(MIN_SAMPLE, MAX_SAMPLE).toInt().toShort()
        }
    }

    /** Smooth 0..1 ramp. Gentler than a straight line, so no click at the edges. */
    private fun raisedCosine(t: Double): Double = 0.5 * (1.0 - cos(PI * t.coerceIn(0.0, 1.0)))
}
