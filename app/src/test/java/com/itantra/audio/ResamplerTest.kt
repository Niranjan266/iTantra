package com.itantra.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The voice must come out at the pitch it was spoken at.
 *
 * Measured, not assumed: a tone is generated at the voice's rate, converted, and its
 * frequency read back by counting zero crossings at the player's rate. Before the fix a
 * 22,050 Hz voice played at 16 kHz came out at 73% of its pitch.
 */
class ResamplerTest {

    private fun tone(hz: Double, rate: Int, seconds: Double) =
        FloatArray((rate * seconds).toInt()) { i -> (0.5 * sin(2 * PI * hz * i / rate)).toFloat() }

    /** Frequency from zero crossings, ignoring the edges where the filter ramps in. */
    private fun frequency(x: FloatArray, rate: Int): Double {
        val a = x.size / 10
        val b = x.size - x.size / 10
        var crossings = 0
        for (i in a + 1 until b) if ((x[i - 1] < 0f) != (x[i] < 0f)) crossings++
        return crossings / 2.0 / ((b - a).toDouble() / rate)
    }

    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += x[i] * x[i]
        return sqrt(s / (to - from))
    }

    @Test
    fun piperVoiceKeepsItsPitchAtThePlayerRate() {
        for (hz in listOf(150.0, 440.0, 2000.0, 5000.0)) {
            val out = Resampler.resample(tone(hz, 22_050, 1.0), 22_050, 16_000)
            val measured = frequency(out, 16_000)
            assertEquals("pitch of a $hz Hz tone", hz, measured, hz * 0.01)
        }
    }

    @Test
    fun durationIsPreserved() {
        val out = Resampler.resample(tone(300.0, 22_050, 2.0), 22_050, 16_000)
        assertEquals(32_000, out.size)
    }

    @Test
    fun levelIsPreserved() {
        val out = Resampler.resample(tone(440.0, 22_050, 1.0), 22_050, 16_000)
        // A 0.5-amplitude sine has RMS 0.354.
        assertEquals(0.354, rms(out, 2000, 14_000), 0.01)
    }

    @Test
    fun contentAboveTheNewNyquistIsRemovedNotFoldedBack() {
        // 10 kHz exists at 22,050 Hz but cannot at 16 kHz. Plain interpolation would fold
        // it to 6 kHz, an audible whistle; the filter must remove it instead.
        val out = Resampler.resample(tone(10_000.0, 22_050, 1.0), 22_050, 16_000)
        assertTrue("residual ${rms(out, 2000, 14_000)}", rms(out, 2000, 14_000) < 0.02)
    }

    @Test
    fun sameRateIsUntouched() {
        val x = tone(440.0, 16_000, 0.1)
        assertTrue(Resampler.resample(x, 16_000, 16_000) === x)
    }

    @Test
    fun emptyStaysEmpty() {
        assertEquals(0, Resampler.resample(FloatArray(0), 22_050, 16_000).size)
        assertTrue(abs(0) == 0)
    }
}
