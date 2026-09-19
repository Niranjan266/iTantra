package com.itantra.acoustic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * The modem against the things a real room does to sound.
 *
 * Each test modulates a packet, damages the audio the way a speaker, a room and a
 * microphone would, and requires the exact bytes back.
 */
class AcousticModemTest {

    private val rnd = Random(26173)

    /** A 16-byte packet, the size of a phrase message on the wire. */
    private val phrase = ByteArray(16) { (it * 37 + 11).toByte() }

    private fun demodulate(audio: ShortArray, chunk: Int = 320): List<ByteArray> {
        val d = AcousticDemodulator()
        val out = mutableListOf<ByteArray>()
        var i = 0
        while (i < audio.size) {
            val n = minOf(chunk, audio.size - i)
            out += d.feed(audio.copyOfRange(i, i + n))
            i += n
        }
        // Flush: a little trailing silence, as a live microphone would supply.
        out += d.feed(ShortArray(AcousticModem.SLOT * 4))
        return out
    }

    private fun silence(ms: Int) = ShortArray(AcousticModem.RATE * ms / 1000)

    private fun ShortArray.scaled(g: Double) = ShortArray(size) { (this[it] * g).toInt().toShort() }

    private fun ShortArray.plusNoise(amplitude: Double) = ShortArray(size) {
        (this[it] + rnd.nextDouble(-amplitude, amplitude) * 32767).toInt()
            .coerceIn(-32768, 32767).toShort()
    }

    private fun ShortArray.withEcho(delaySamples: Int, gain: Double) = ShortArray(size) {
        val echo = if (it >= delaySamples) this[it - delaySamples] * gain else 0.0
        (this[it] + echo).toInt().coerceIn(-32768, 32767).toShort()
    }

    @Test
    fun aCleanFrameDecodes() {
        val got = demodulate(silence(200) + AcousticModem.modulate(phrase))
        assertEquals(1, got.size)
        assertArrayEquals(phrase, got[0])
    }

    @Test
    fun durationMatchesTheAdvertisedFigure() {
        // 16 bytes: 34 nibbles, 4 blocks, 60 data slots + 8 preamble = 68 slots of 40 ms.
        assertEquals(2720, AcousticModem.durationMs(16))
        assertEquals(68 * AcousticModem.SLOT, AcousticModem.modulate(phrase).size)
    }

    @Test
    fun everyLengthRoundTrips() {
        for (len in listOf(1, 2, 8, 9, 16, 24, 50, 108, AcousticModem.MAX_PAYLOAD)) {
            val p = ByteArray(len) { rnd.nextInt(256).toByte() }
            val got = demodulate(silence(100) + AcousticModem.modulate(p))
            assertEquals("length $len", 1, got.size)
            assertArrayEquals("length $len", p, got[0])
        }
    }

    @Test
    fun arrivingAtAnyMomentStillAligns() {
        // The listener does not know when a burst will start. Try many offsets that are
        // not multiples of anything in the modem.
        repeat(12) {
            val lead = ShortArray(rnd.nextInt(0, 5000))
            val got = demodulate(lead + AcousticModem.modulate(phrase))
            assertEquals("lead ${lead.size}", 1, got.size)
            assertArrayEquals(phrase, got[0])
        }
    }

    @Test
    fun aQuietDistantSignalDecodes() {
        val got = demodulate(silence(150) + AcousticModem.modulate(phrase).scaled(0.03))
        assertEquals(1, got.size)
        assertArrayEquals(phrase, got[0])
    }

    @Test
    fun aNoisyRoomDecodes() {
        // Uniform noise at ±0.35 full scale against a 0.6 tone: a loud room.
        val got = demodulate((silence(150) + AcousticModem.modulate(phrase)).plusNoise(0.35))
        assertEquals(1, got.size)
        assertArrayEquals(phrase, got[0])
    }

    @Test
    fun roomEchoIsTolerated() {
        // A reflection 5 ms late at 40%: inside the guard interval.
        val got = demodulate((silence(150) + AcousticModem.modulate(phrase)).withEcho(80, 0.4))
        assertEquals(1, got.size)
        assertArrayEquals(phrase, got[0])
    }

    @Test
    fun microphoneSizedChunksDecode() {
        for (chunk in listOf(1, 160, 441, 1024, 4096)) {
            val got = demodulate(silence(120) + AcousticModem.modulate(phrase), chunk)
            assertEquals("chunk $chunk", 1, got.size)
            assertArrayEquals(phrase, got[0])
        }
    }

    @Test
    fun twoFramesBackToBackAreBothReceived() {
        val second = ByteArray(9) { (200 - it).toByte() }
        val audio = silence(100) + AcousticModem.modulate(phrase) + silence(300) +
            AcousticModem.modulate(second)
        val got = demodulate(audio)
        assertEquals(2, got.size)
        assertArrayEquals(phrase, got[0])
        assertArrayEquals(second, got[1])
    }

    @Test
    fun noiseAloneProducesNothing() {
        val noise = silence(20_000).plusNoise(0.5)
        assertTrue(demodulate(noise).isEmpty())
    }

    @Test
    fun speechLikeTonesProduceNothing() {
        // A voice-band sweep, standing in for someone talking beside the phone.
        val n = AcousticModem.RATE * 10
        val sweep = ShortArray(n) {
            val f = 200.0 + 3000.0 * (it % 8000) / 8000.0
            (0.5 * sin(2 * PI * f * it / AcousticModem.RATE) * 32767).toInt().toShort()
        }
        assertTrue(demodulate(sweep).isEmpty())
    }

    @Test
    fun tonesStayInsideAVoiceRadioBand() {
        // 300–3400 Hz is what a walkie-talkie or a phone voice channel passes.
        val bins = (0 until 16).map { AcousticModem.dataBin(it) } +
            AcousticModem.SYNC_A_BIN + AcousticModem.SYNC_B_BIN
        for (b in bins) {
            val hz = AcousticModem.binHz(b)
            assertTrue("$hz Hz", hz in 300.0..3400.0)
        }
    }

    /** A room: direct sound plus a decaying tail of reflections lasting [rt60Ms]. */
    private fun ShortArray.inRoom(rt60Ms: Int, tailGain: Double): ShortArray {
        val n = AcousticModem.RATE * rt60Ms / 1000
        val ir = DoubleArray(n)
        ir[0] = 1.0
        val r = Random(7)
        // Reflections start after ~3 ms and decay by 60 dB over rt60.
        for (i in 48 until n) ir[i] = tailGain * r.nextDouble(-1.0, 1.0) *
            Math.pow(10.0, -3.0 * i / n) / Math.sqrt(n / 48.0)
        val out = DoubleArray(size + n)
        for (i in indices) {
            val x = this[i].toDouble()
            if (x == 0.0) continue
            for (k in 0 until n) out[i + k] += x * ir[k]
        }
        val peak = out.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1.0)
        val g = minOf(1.0, 30000.0 / peak)
        return ShortArray(size) { (out[it] * g).toInt().toShort() }
    }

    /** The other phone's clock runs [ppm] parts per million fast. */
    private fun ShortArray.clockSkew(ppm: Double): ShortArray {
        val ratio = 1.0 + ppm / 1e6
        val n = (size / ratio).toInt()
        return ShortArray(n) { i ->
            val t = i * ratio
            val a = t.toInt().coerceAtMost(size - 2)
            val f = t - a
            (this[a] * (1 - f) + this[a + 1] * f).toInt().toShort()
        }
    }

    @Test
    fun aReverberantRoomDecodes() {
        // 300 ms of reverberation — an ordinary furnished room — far longer than the
        // 8 ms guard. Only the tail's leakage into later slots matters, and error
        // correction absorbs it.
        val audio = (silence(150) + AcousticModem.modulate(phrase) + silence(400)).inRoom(300, 0.5)
        val got = demodulate(audio)
        assertEquals(1, got.size)
        assertArrayEquals(phrase, got[0])
    }

    @Test
    fun twoPhonesWithDifferentClocksStillAgree() {
        // Phone audio clocks commonly differ by tens of ppm; 200 ppm is a bad case.
        for (ppm in listOf(-200.0, 200.0)) {
            val got = demodulate((silence(150) + AcousticModem.modulate(phrase)).clockSkew(ppm))
            assertEquals("$ppm ppm", 1, got.size)
            assertArrayEquals("$ppm ppm", phrase, got[0])
        }
    }
}
