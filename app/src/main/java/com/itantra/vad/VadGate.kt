package com.itantra.vad

import com.itantra.audio.AudioSpec
import kotlin.math.sqrt

/**
 * Speech detection and endpointing (PRD F-02, F-03).
 *
 * Two jobs. First, decide whether someone is speaking right now. Second — the one that
 * actually matters for the latency score — decide when they have *stopped*, so the
 * recogniser can finalise without waiting for the user to release a button.
 *
 * Deliberately free of Android imports. The decision logic is the part that is easy to
 * get subtly wrong and awkward to debug on a phone, so it is plain Kotlin and fully
 * unit-tested. Phase 4 replaces [rms]-based scoring with the Silero neural VAD; the
 * state machine below stays exactly as it is.
 *
 * The threshold adapts to the room. A fixed threshold works in a quiet lab and fails in
 * a flood-relief camp, which is the only environment that matters here.
 */
class VadGate(
    private val config: Config = Config(),
) {

    data class Config(
        /** Consecutive loud frames before speech is declared. 3 frames = 60 ms. */
        val speechFrames: Int = 3,

        /**
         * Silence held before an utterance is declared over. This is the single
         * biggest lever on perceived latency: too short and it cuts people off
         * mid-sentence, too long and every message feels sluggish. 500 ms is the
         * budgeted value in TRD section 6.
         */
        val hangoverMs: Int = 500,

        /** How far above the measured noise floor counts as speech. */
        val speechFactor: Double = 3.0,

        /**
         * Absolute floor, as RMS in 16-bit sample units. Stops a silent room — where
         * the noise floor approaches zero — from treating faint hiss as speech.
         */
        val minSpeechRms: Double = 350.0,

        /** How fast the noise floor tracks the room. Small = slow = stable. */
        val noiseAdaptRate: Double = 0.02,

        /**
         * Safety valve. If "speech" runs this long without a break it is almost
         * certainly not speech — a generator started up, a truck is idling nearby, or
         * the button is stuck. Without this an energy detector can latch on and never
         * release, and the device goes deaf for the rest of the deployment.
         */
        val maxUtteranceMs: Int = 15_000,
    )

    sealed class Event {
        /** Speech began. Emitted once per utterance. */
        object SpeechStart : Event()

        /**
         * Speech ended.
         *
         * @param speechDurationMs how long the person actually spoke.
         * @param endpointDelayMs how long after they stopped this was decided. This is
         *   a real component of mouth-to-ear latency and is reported, not hidden.
         */
        data class SpeechEnd(
            val speechDurationMs: Int,
            val endpointDelayMs: Int,
        ) : Event()
    }

    private val hangoverFrames = AudioSpec.framesForMs(config.hangoverMs).coerceAtLeast(1)

    private var speaking = false
    private var loudRun = 0
    private var quietRun = 0
    private var speechFrameCount = 0

    /** Starts high so the first frames of a session are not mistaken for speech. */
    var noiseFloor: Double = 500.0
        private set

    /** Loudest frame seen since the last reset. Drives the level meter. */
    var peakRms: Double = 0.0
        private set

    val isSpeaking: Boolean get() = speaking

    fun reset() {
        speaking = false
        loudRun = 0
        quietRun = 0
        speechFrameCount = 0
        noiseFloor = 500.0
        peakRms = 0.0
    }

    /**
     * Feed one frame.
     *
     * @return an event if this frame changed the state, otherwise null.
     */
    fun accept(samples: ShortArray): Event? {
        val level = rms(samples)
        if (level > peakRms) peakRms = level

        val threshold = maxOf(noiseFloor * config.speechFactor, config.minSpeechRms)
        val loud = level > threshold

        // Learn the room ONLY from frames that are both quiet and outside an
        // utterance. Adapting on loud frames lets the onset of each utterance drag the
        // floor upward; over a long session that ratchets until real speech no longer
        // clears the threshold and the device quietly goes deaf.
        if (!speaking && !loud) {
            noiseFloor += (level - noiseFloor) * config.noiseAdaptRate
        }

        return if (loud) {
            quietRun = 0
            loudRun++
            if (speaking) {
                speechFrameCount++
                if (AudioSpec.msForFrames(speechFrameCount) >= config.maxUtteranceMs) {
                    // Sustained noise, not speech. Adopt it as the new room level so
                    // the detector recovers instead of latching on forever.
                    noiseFloor = level
                    val spoken = AudioSpec.msForFrames(speechFrameCount)
                    speaking = false
                    speechFrameCount = 0
                    loudRun = 0
                    Event.SpeechEnd(speechDurationMs = spoken, endpointDelayMs = 0)
                } else {
                    null
                }
            } else if (loudRun >= config.speechFrames) {
                speaking = true
                // The frames that triggered detection were speech too.
                speechFrameCount = loudRun
                Event.SpeechStart
            } else {
                null
            }
        } else {
            loudRun = 0
            if (!speaking) return null
            quietRun++
            if (quietRun >= hangoverFrames) {
                speaking = false
                val spoken = AudioSpec.msForFrames(speechFrameCount)
                speechFrameCount = 0
                val delay = AudioSpec.msForFrames(quietRun)
                quietRun = 0
                Event.SpeechEnd(speechDurationMs = spoken, endpointDelayMs = delay)
            } else {
                // Still inside the hangover window — a pause between words, not an end.
                speechFrameCount++
                null
            }
        }
    }

    /**
     * Force the current utterance closed.
     *
     * Used when push-to-talk is released: the user has said explicitly that they are
     * finished, which is more reliable than any detector, so there is no reason to sit
     * through the hangover window.
     */
    fun forceEnd(): Event.SpeechEnd? {
        if (!speaking) return null
        speaking = false
        val spoken = AudioSpec.msForFrames(speechFrameCount)
        speechFrameCount = 0
        loudRun = 0
        quietRun = 0
        return Event.SpeechEnd(speechDurationMs = spoken, endpointDelayMs = 0)
    }

    companion object {
        /** Root mean square of a frame, in 16-bit sample units. */
        fun rms(samples: ShortArray): Double {
            if (samples.isEmpty()) return 0.0
            var sum = 0.0
            for (s in samples) {
                val v = s.toDouble()
                sum += v * v
            }
            return sqrt(sum / samples.size)
        }
    }
}
