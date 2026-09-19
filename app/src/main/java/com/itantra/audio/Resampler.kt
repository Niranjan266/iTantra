package com.itantra.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Converts audio between sample rates, so every voice plays at its true pitch.
 *
 * ## Why this exists
 *
 * The player runs at [AudioSpec.SAMPLE_RATE], 16 kHz, because the whole pipeline does.
 * Voices do not all agree: MMS voices speak at 16 kHz, but Piper and Mimic3 voices —
 * English, Hindi, Malayalam, Telugu, Gujarati — speak at 22,050 Hz. Handing 22,050 Hz
 * samples to a 16 kHz player does not fail. It plays them at 73% speed, which lowers the
 * pitch by five and a half semitones: every one of those languages came out slow and
 * deep, a voice that sounded like a different, older speaker. Nothing errored, so the
 * only symptom was the sound.
 *
 * ## How
 *
 * Band-limited interpolation with a windowed sinc. Going down from 22,050 to 16,000 the
 * filter's cutoff is lowered to the new Nyquist, so content between 8 and 11 kHz is
 * removed rather than folded back into the audible band as hiss — which is what plain
 * linear interpolation would do. Thirty-two taps per output sample is well under a
 * millisecond of CPU per second of speech on a phone.
 */
object Resampler {

    /** Filter half-width in input samples. 16 each side gives a sharp, clean cutoff. */
    private const val HALF_TAPS = 16

    /** Just below Nyquist, so the transition band falls outside the audible result. */
    private const val CUTOFF = 0.92

    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        require(fromRate > 0 && toRate > 0) { "sample rates must be positive" }
        if (fromRate == toRate || input.isEmpty()) return input

        val ratio = toRate.toDouble() / fromRate
        // When lowering the rate, the filter must be narrowed to the NEW Nyquist; when
        // raising it, the original Nyquist already bounds the content.
        val band = CUTOFF * min(1.0, ratio)
        val outLength = (input.size * ratio).toInt()
        val out = FloatArray(outLength)
        val step = 1.0 / ratio
        // Widen the kernel in input samples when narrowing the band, so the filter keeps
        // the same number of lobes and therefore the same sharpness.
        val half = (HALF_TAPS / min(1.0, ratio)).toInt()

        for (i in 0 until outLength) {
            val centre = i * step
            val first = floor(centre).toInt() - half + 1
            var acc = 0.0
            var norm = 0.0
            for (j in first until first + 2 * half) {
                if (j < 0 || j >= input.size) continue
                val x = j - centre
                val w = kernel(x, band, half)
                acc += input[j] * w
                norm += w
            }
            // Normalising by the taps actually used keeps the level right at the edges,
            // where part of the kernel falls outside the signal.
            out[i] = if (norm != 0.0) (acc / norm).toFloat() else 0f
        }
        return out
    }

    /** sinc low-pass at [band] (fraction of Nyquist), shaped by a Hann window. */
    private fun kernel(x: Double, band: Double, half: Int): Double {
        val sinc = if (x == 0.0) band else sin(PI * band * x) / (PI * x)
        val window = 0.5 + 0.5 * cos(PI * x / half)
        return if (x > -half && x < half) sinc * window else 0.0
    }
}
