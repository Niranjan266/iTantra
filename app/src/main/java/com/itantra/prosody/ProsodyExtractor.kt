package com.itantra.prosody

import com.itantra.audio.AudioSpec
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Measures how something was said, in three bytes (PRD F-08, TRD section 4.5).
 *
 * A text pipeline throws away everything except the words. That is a real loss in a
 * distress system: whether the sender was calm or frantic is information a rescue
 * coordinator acts on, and it costs three bytes to keep.
 *
 *   pitch   mean fundamental frequency  → how high the voice was
 *   rate    speaking rate               → how fast they were talking
 *   energy  RMS level                   → how loudly
 *
 * The receiving synthesiser scales its output toward these, so a fast loud sender
 * *sounds* fast and loud at the far end (see SherpaTtsEngine.speedFor).
 *
 * Pure Kotlin, no Android, so the pitch tracker can be tested against synthetic tones
 * of known frequency rather than by listening to it.
 */
object ProsodyExtractor {

    /** Human speech lives here. Anything outside is noise, not a voice. */
    const val MIN_F0_HZ = 60.0
    const val MAX_F0_HZ = 400.0

    /** Quantisation ranges, frozen with the wire format (TRD section 4.5). */
    private const val PITCH_LO = 50.0
    private const val PITCH_HI = 400.0
    private const val RATE_MAX_SYM_PER_SEC = 25.0
    private const val ENERGY_MIN_DB = -60.0

    data class Prosody(
        /** 0 means "not measured" — the receiver then uses its own defaults. */
        val pitch: Int,
        val rate: Int,
        val energy: Int,
    ) {
        companion object {
            val UNMEASURED = Prosody(0, 0, 0)
        }
    }

    /**
     * @param samples the whole utterance, 16 kHz mono.
     * @param symbolCount how many symbols the recogniser produced, for the rate.
     */
    fun extract(samples: ShortArray, symbolCount: Int): Prosody {
        if (samples.isEmpty()) return Prosody.UNMEASURED

        val seconds = samples.size.toDouble() / AudioSpec.SAMPLE_RATE
        val f0 = meanF0(samples)
        val rms = rms(samples)

        return Prosody(
            pitch = quantisePitch(f0),
            rate = quantiseRate(if (seconds > 0) symbolCount / seconds else 0.0),
            energy = quantiseEnergy(rms),
        )
    }

    /**
     * Mean fundamental frequency by autocorrelation.
     *
     * Autocorrelation rather than anything cleverer: it is a few lines, it runs in
     * microseconds on a weak CPU, and the number only has to be good enough to tilt a
     * synthesiser. Frames where no clear period is found are skipped rather than
     * guessed at, so silence and unvoiced consonants do not drag the average down.
     *
     * @return Hz, or 0.0 if the utterance had no voiced frames at all.
     */
    fun meanF0(samples: ShortArray): Double {
        val frame = AudioSpec.SAMPLES_PER_FRAME * 2   // 40 ms: two periods at 60 Hz
        if (samples.size < frame) return 0.0

        val minLag = (AudioSpec.SAMPLE_RATE / MAX_F0_HZ).toInt()
        val maxLag = (AudioSpec.SAMPLE_RATE / MIN_F0_HZ).toInt()
        if (maxLag >= frame) return 0.0

        var total = 0.0
        var voiced = 0

        var start = 0
        while (start + frame <= samples.size) {
            val f0 = frameF0(samples, start, frame, minLag, maxLag)
            if (f0 > 0) {
                total += f0
                voiced++
            }
            start += frame
        }

        return if (voiced > 0) total / voiced else 0.0
    }

    private fun frameF0(
        samples: ShortArray,
        offset: Int,
        length: Int,
        minLag: Int,
        maxLag: Int,
    ): Double {
        // Energy at zero lag. A frame this quiet has no pitch worth measuring.
        var energy = 0.0
        for (i in offset until offset + length) {
            val v = samples[i].toDouble()
            energy += v * v
        }
        if (energy < 1e4) return 0.0

        var bestLag = 0
        var bestScore = 0.0

        for (lag in minLag..maxLag) {
            var sum = 0.0
            for (i in offset until offset + length - lag) {
                sum += samples[i].toDouble() * samples[i + lag].toDouble()
            }
            val score = sum / energy
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }

        // Require a genuinely periodic frame. Below this the "peak" is noise, and
        // reporting a confident pitch for a fricative would be worse than reporting
        // nothing.
        if (bestLag == 0 || bestScore < 0.3) return 0.0
        return AudioSpec.SAMPLE_RATE.toDouble() / bestLag
    }

    fun rms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (s in samples) {
            val v = s.toDouble()
            sum += v * v
        }
        return sqrt(sum / samples.size)
    }

    // --- Quantisation to single bytes (TRD section 4.5) ---
    //
    // 0 is reserved for "not measured" in every field, so a measured value never
    // encodes as 0 and the receiver can always tell the difference.

    fun quantisePitch(hz: Double): Int {
        if (hz < MIN_F0_HZ || hz > MAX_F0_HZ) return 0
        val f = (hz - PITCH_LO) / (PITCH_HI - PITCH_LO)
        return (f * 255).roundToInt().coerceIn(1, 255)
    }

    fun dequantisePitch(byte: Int): Double? {
        if (byte <= 0) return null
        return PITCH_LO + (byte / 255.0) * (PITCH_HI - PITCH_LO)
    }

    fun quantiseRate(symbolsPerSecond: Double): Int {
        if (symbolsPerSecond <= 0.0) return 0
        val f = symbolsPerSecond / RATE_MAX_SYM_PER_SEC
        return (f * 255).roundToInt().coerceIn(1, 255)
    }

    fun dequantiseRate(byte: Int): Double? {
        if (byte <= 0) return null
        return byte * RATE_MAX_SYM_PER_SEC / 255.0
    }

    /** RMS in sample units to a byte, via dBFS so the scale matches perception. */
    fun quantiseEnergy(rms: Double): Int {
        if (rms <= 0.0) return 0
        val db = 20.0 * kotlin.math.log10(rms / 32768.0)
        val f = (db - ENERGY_MIN_DB) / (0.0 - ENERGY_MIN_DB)
        return (f * 255).roundToInt().coerceIn(1, 255)
    }

    fun dequantiseEnergyDb(byte: Int): Double? {
        if (byte <= 0) return null
        return ENERGY_MIN_DB + (byte / 255.0) * (0.0 - ENERGY_MIN_DB)
    }

    /**
     * A crude urgency hint from the voice alone (PRD F-34).
     *
     * Deliberately conservative and deliberately advisory: it may *raise* a suggestion
     * but the manual selector always wins. Automatically escalating someone to DISTRESS
     * — which overrides their volume and cannot be interrupted — on the strength of an
     * autocorrelation estimate would be indefensible.
     */
    fun suggestsUrgency(p: Prosody, baselinePitch: Int?): Boolean {
        val base = baselinePitch ?: return false
        if (base <= 0 || p.pitch <= 0) return false
        val raised = p.pitch > base * 1.25
        val loud = p.energy > 200
        val fast = p.rate > 160
        // Two of three, so one noisy measurement cannot trigger it alone.
        return listOf(raised, loud, fast).count { it } >= 2
    }
}
