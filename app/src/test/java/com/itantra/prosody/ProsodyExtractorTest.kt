package com.itantra.prosody

import com.itantra.audio.AudioSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * A pitch tracker is easy to write and hard to know you got right by listening. Here it
 * is checked against synthetic tones of known frequency, and — more importantly —
 * against signals that have no pitch at all, because confidently reporting a pitch for
 * silence would push wrong prosody onto every message.
 */
class ProsodyExtractorTest {

    private fun tone(hz: Double, ms: Int, amplitude: Double = 8000.0): ShortArray {
        val n = AudioSpec.SAMPLE_RATE * ms / 1000
        return ShortArray(n) { i ->
            (amplitude * sin(2 * PI * hz * i / AudioSpec.SAMPLE_RATE)).toInt().toShort()
        }
    }

    /** A vowel-like signal: fundamental plus harmonics, as a real voice produces. */
    private fun voiced(f0: Double, ms: Int): ShortArray {
        val n = AudioSpec.SAMPLE_RATE * ms / 1000
        return ShortArray(n) { i ->
            val t = i.toDouble() / AudioSpec.SAMPLE_RATE
            val v = 6000 * sin(2 * PI * f0 * t) +
                3000 * sin(2 * PI * 2 * f0 * t) +
                1500 * sin(2 * PI * 3 * f0 * t)
            v.toInt().toShort()
        }
    }

    // --- Pitch ---

    @Test
    fun `pitch is recovered from a pure tone`() {
        for (hz in listOf(100.0, 150.0, 200.0, 300.0)) {
            val measured = ProsodyExtractor.meanF0(tone(hz, 500))
            assertEquals("at $hz Hz", hz, measured, hz * 0.05)
        }
    }

    @Test
    fun `pitch is recovered from a voice-like harmonic signal`() {
        // The fundamental must win, not the louder-looking harmonics.
        val measured = ProsodyExtractor.meanF0(voiced(120.0, 600))
        assertEquals(120.0, measured, 8.0)
    }

    @Test
    fun `silence reports no pitch rather than guessing`() {
        assertEquals(0.0, ProsodyExtractor.meanF0(ShortArray(8000)), 0.0001)
    }

    @Test
    fun `noise reports no pitch rather than guessing`() {
        val rng = Random(99)
        val noise = ShortArray(8000) { rng.nextInt(-3000, 3000).toShort() }
        val f0 = ProsodyExtractor.meanF0(noise)
        // Either nothing, or at least not a confident speech-range value.
        assertTrue("noise reported $f0 Hz as pitch", f0 == 0.0 || f0 < ProsodyExtractor.MIN_F0_HZ)
    }

    @Test
    fun `too short an utterance reports no pitch`() {
        assertEquals(0.0, ProsodyExtractor.meanF0(ShortArray(100)), 0.0001)
    }

    // --- Quantisation round trips ---

    @Test
    fun `pitch survives the round trip through one byte`() {
        for (hz in listOf(80.0, 120.0, 200.0, 350.0)) {
            val b = ProsodyExtractor.quantisePitch(hz)
            assertTrue("$hz Hz encoded as 0 (reserved for unmeasured)", b > 0)
            assertEquals(hz, ProsodyExtractor.dequantisePitch(b)!!, 2.0)
        }
    }

    @Test
    fun `pitch outside the human range is reported as unmeasured`() {
        assertEquals(0, ProsodyExtractor.quantisePitch(20.0))
        assertEquals(0, ProsodyExtractor.quantisePitch(2_000.0))
        assertNull(ProsodyExtractor.dequantisePitch(0))
    }

    @Test
    fun `rate survives the round trip`() {
        for (rate in listOf(4.0, 12.0, 20.0)) {
            val b = ProsodyExtractor.quantiseRate(rate)
            assertEquals(rate, ProsodyExtractor.dequantiseRate(b)!!, 0.2)
        }
    }

    @Test
    fun `energy round trips through decibels`() {
        for (rms in listOf(500.0, 3000.0, 20000.0)) {
            val b = ProsodyExtractor.quantiseEnergy(rms)
            assertTrue(b in 1..255)
            assertTrue(ProsodyExtractor.dequantiseEnergyDb(b)!! <= 0.0)
        }
    }

    @Test
    fun `zero is reserved for unmeasured in every field`() {
        // A measured value must never encode as 0, or the receiver cannot tell the
        // difference between "quiet" and "not measured".
        assertEquals(0, ProsodyExtractor.quantiseRate(0.0))
        assertEquals(0, ProsodyExtractor.quantiseEnergy(0.0))
        assertNull(ProsodyExtractor.dequantiseRate(0))
        assertNull(ProsodyExtractor.dequantiseEnergyDb(0))
        // And anything genuinely measurable encodes above 0.
        assertTrue(ProsodyExtractor.quantiseEnergy(1.0) > 0)
        assertTrue(ProsodyExtractor.quantiseRate(0.1) > 0)
    }

    @Test
    fun `quantised values always fit in one byte`() {
        for (hz in 0..500 step 7) assertTrue(ProsodyExtractor.quantisePitch(hz.toDouble()) in 0..255)
        for (r in 0..40) assertTrue(ProsodyExtractor.quantiseRate(r.toDouble()) in 0..255)
        for (e in 0..32767 step 511) assertTrue(ProsodyExtractor.quantiseEnergy(e.toDouble()) in 0..255)
    }

    // --- End to end ---

    @Test
    fun `extract fills every field for a voiced utterance`() {
        val p = ProsodyExtractor.extract(voiced(140.0, 1000), symbolCount = 12)
        assertTrue("pitch was ${p.pitch}", p.pitch > 0)
        assertTrue("rate was ${p.rate}", p.rate > 0)
        assertTrue("energy was ${p.energy}", p.energy > 0)
        // 12 symbols in 1 second.
        assertEquals(12.0, ProsodyExtractor.dequantiseRate(p.rate)!!, 0.5)
    }

    @Test
    fun `an empty utterance is unmeasured throughout`() {
        assertEquals(
            ProsodyExtractor.Prosody.UNMEASURED,
            ProsodyExtractor.extract(ShortArray(0), 0),
        )
    }

    @Test
    fun `a louder utterance encodes as louder`() {
        val quiet = ProsodyExtractor.extract(tone(150.0, 500, amplitude = 500.0), 6)
        val loud = ProsodyExtractor.extract(tone(150.0, 500, amplitude = 20000.0), 6)
        assertTrue("quiet=${quiet.energy} loud=${loud.energy}", loud.energy > quiet.energy)
    }

    @Test
    fun `a faster utterance encodes as faster`() {
        val slow = ProsodyExtractor.extract(voiced(140.0, 2000), symbolCount = 10)
        val fast = ProsodyExtractor.extract(voiced(140.0, 1000), symbolCount = 20)
        assertTrue("slow=${slow.rate} fast=${fast.rate}", fast.rate > slow.rate)
    }

    // --- Urgency hint ---

    @Test
    fun `urgency needs two independent signals not one`() {
        val base = 100
        // Raised pitch alone must not be enough — people also just speak higher.
        val pitchOnly = ProsodyExtractor.Prosody(pitch = 140, rate = 100, energy = 100)
        assertTrue(!ProsodyExtractor.suggestsUrgency(pitchOnly, base))

        val raisedAndLoud = ProsodyExtractor.Prosody(pitch = 140, rate = 100, energy = 220)
        assertTrue(ProsodyExtractor.suggestsUrgency(raisedAndLoud, base))
    }

    @Test
    fun `urgency is never suggested without a baseline or a measurement`() {
        val p = ProsodyExtractor.Prosody(pitch = 200, rate = 200, energy = 240)
        assertTrue(!ProsodyExtractor.suggestsUrgency(p, baselinePitch = null))
        assertTrue(!ProsodyExtractor.suggestsUrgency(p, baselinePitch = 0))
        val unmeasured = ProsodyExtractor.Prosody(0, 0, 0)
        assertTrue(!ProsodyExtractor.suggestsUrgency(unmeasured, baselinePitch = 100))
    }
}
