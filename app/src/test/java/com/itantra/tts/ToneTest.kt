package com.itantra.tts

import com.itantra.audio.AudioSpec
import com.itantra.codec.Intent
import com.itantra.codec.LanguageId
import com.itantra.codec.Packet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ToneTest {

    private fun packet(intent: Int, payloadSize: Int) = Packet(
        sessionId = 1, seq = 1,
        langId = LanguageId.ENGLISH, intent = intent,
        payload = ByteArray(payloadSize),
    )

    @Test
    fun `duration matches the request`() {
        for (ms in listOf(0, 10, 100, 500, 1000)) {
            val t = Tone.generate(440.0, ms)
            assertEquals("at $ms ms", AudioSpec.SAMPLE_RATE * ms / 1000, t.size)
        }
    }

    @Test
    fun `output stays in range and never wraps`() {
        val t = Tone.generate(440.0, 500, amplitude = 1.0)
        // Short.MIN_VALUE is excluded: it has no positive counterpart and negating it
        // overflows, so downstream gain code would misbehave on it.
        assertTrue(t.all { it > Short.MIN_VALUE })
        // A wrap would show up as a huge jump between neighbouring samples.
        val maxStep = (1 until t.size).maxOf { abs(t[it] - t[it - 1]) }
        assertTrue("largest sample-to-sample step was $maxStep", maxStep < 20_000)
    }

    @Test
    fun `edges fade so there is no click`() {
        val t = Tone.generate(440.0, 300, amplitude = 0.8)
        val peak = t.maxOf { abs(it.toInt()) }
        assertTrue("first sample was ${t.first()}", abs(t.first().toInt()) < peak / 20)
        assertTrue("last sample was ${t.last()}", abs(t.last().toInt()) < peak / 20)
        // The body must still reach full level. Checked over a window, not one sample,
        // because a sine legitimately passes through zero.
        val middle = t.copyOfRange(t.size / 3, 2 * t.size / 3).maxOf { abs(it.toInt()) }
        assertTrue("middle peaked at $middle vs overall $peak", middle > peak / 2)
    }

    @Test
    fun `amplitude scales the peak`() {
        val quiet = Tone.generate(440.0, 300, amplitude = 0.2).maxOf { abs(it.toInt()) }
        val loud = Tone.generate(440.0, 300, amplitude = 0.8).maxOf { abs(it.toInt()) }
        assertTrue("quiet=$quiet loud=$loud", loud > quiet * 3)
    }

    @Test
    fun `generation is deterministic`() {
        assertTrue(
            Tone.generate(660.0, 200).contentEquals(Tone.generate(660.0, 200))
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative duration is rejected`() {
        Tone.generate(440.0, -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `amplitude above full scale is rejected`() {
        Tone.generate(440.0, 100, amplitude = 1.5)
    }

    @Test
    fun `distress renders longer and louder than routine`() {
        val r = AlertToneRenderer()
        val routine = r.render(packet(Intent.ROUTINE, 60))
        val distress = r.render(packet(Intent.DISTRESS, 60))
        assertTrue("distress ${distress.size} vs routine ${routine.size}",
            distress.size > routine.size)
        assertTrue(distress.maxOf { abs(it.toInt()) } > routine.maxOf { abs(it.toInt()) })
    }

    @Test
    fun `every intent renders a usable, bounded amount of audio`() {
        val r = AlertToneRenderer()
        for (intent in listOf(Intent.ROUTINE, Intent.ALERT, Intent.DISTRESS)) {
            for (size in listOf(0, 1, 62, 255)) {
                val pcm = r.render(packet(intent, size))
                assertTrue("intent=$intent size=$size produced nothing", pcm.isNotEmpty())
                val ms = pcm.size * 1000 / AudioSpec.SAMPLE_RATE
                assertTrue("intent=$intent size=$size ran $ms ms", ms in 300..5_000)
            }
        }
    }

    @Test
    fun `a longer message renders longer audio`() {
        val r = AlertToneRenderer()
        val short = r.render(packet(Intent.DISTRESS, 30))
        val long = r.render(packet(Intent.DISTRESS, 200))
        assertTrue("short=${short.size} long=${long.size}", long.size > short.size)
    }
}
