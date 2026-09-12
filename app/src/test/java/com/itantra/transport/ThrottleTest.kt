package com.itantra.transport

import com.itantra.codec.Intent
import com.itantra.codec.LanguageId
import com.itantra.codec.Packet
import com.itantra.codec.PacketCodec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The narrow-link demonstration is now the project's only evidence for the
 * long-distance claim (PRD F-52, promoted to P0 when the hardware bridge was dropped).
 * That makes its arithmetic load-bearing, so every number a judge might check with a
 * calculator is pinned here.
 *
 * The throttle itself is tested with virtual time: a real 300 bps test of a voice note
 * would take two minutes of wall clock per assertion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThrottleTest {

    // --- The arithmetic ---

    @Test
    fun `airtime is bytes times eight divided by bitrate`() {
        // 75 bytes = 600 bits; at 300 bps that is exactly 2 seconds.
        assertEquals(2_000L, LinkBudget.airtimeMs(75, 300))
        assertEquals(1_000L, LinkBudget.airtimeMs(125, 1_000))
        assertEquals(0L, LinkBudget.airtimeMs(0, 300))
    }

    @Test
    fun `airtime rounds up because a partial byte still occupies the link`() {
        // 1 byte = 8 bits at 300 bps = 26.67 ms, which must not round down to 26.
        assertEquals(27L, LinkBudget.airtimeMs(1, 300))
    }

    @Test
    fun `airtime rejects a zero or negative bitrate instead of dividing by zero`() {
        for (bad in listOf(0, -1, -300)) {
            runCatching { LinkBudget.airtimeMs(10, bad) }
                .onSuccess { throw AssertionError("bitrate $bad was accepted") }
        }
    }

    @Test
    fun `codec sizes match published bitrates`() {
        // 3 seconds of Opus at 12 kbps = 4500 bytes.
        assertEquals(4_500, LinkBudget.codecBytes(3.0, LinkBudget.OPUS_BPS))
        // 3 seconds of 16 kHz 16-bit mono = 96000 bytes.
        assertEquals(96_000, LinkBudget.codecBytes(3.0, LinkBudget.PCM_BPS))
    }

    // --- The claim itself ---

    @Test
    fun `at 300 bps a real packet keeps up and a voice note does not`() {
        // A genuine encoded packet, not an invented number.
        val packet = Packet(
            sessionId = 1, seq = 1,
            langId = LanguageId.HINDI, intent = Intent.ALERT,
            payload = ByteArray(62),
        )
        val bytes = PacketCodec.encode(packet).size
        assertEquals(75, bytes)

        val c = LinkBudget.compare(bytes, utteranceSeconds = 3.0, bearerBps = 300)

        assertEquals(2_000L, c.itantraMs)
        assertTrue("iTantra should keep up: ${c.itantraMs} ms", c.itantraKeepsUp)

        // 4500 bytes at 300 bps = 120 seconds to send 3 seconds of speech.
        assertEquals(120_000L, c.opusMs)
        assertTrue("Opus should fall behind: ${c.opusMs} ms", c.opusFallsBehind)

        assertEquals(60.0, c.versusOpus, 0.5)
    }

    @Test
    fun `the advantage holds across every bearer preset`() {
        val bytes = 75
        for (preset in LinkBudget.PRESETS.mapNotNull { it.bitsPerSecond }) {
            val c = LinkBudget.compare(bytes, 3.0, preset)
            assertTrue(
                "at $preset bps iTantra took ${c.itantraMs} ms vs Opus ${c.opusMs} ms",
                c.itantraMs < c.opusMs,
            )
        }
    }

    @Test
    fun `raw audio is hopeless on every narrow bearer`() {
        for (preset in LinkBudget.PRESETS.mapNotNull { it.bitsPerSecond }) {
            val c = LinkBudget.compare(75, 3.0, preset)
            assertTrue("raw PCM took only ${c.pcmMs} ms at $preset bps", c.pcmMs > 60_000)
        }
    }

    @Test
    fun `durations read sensibly at both ends of the range`() {
        assertEquals("500 ms", LinkBudget.humanDuration(500))
        assertEquals("2.0 s", LinkBudget.humanDuration(2_000))
        assertEquals("2 min 00 s", LinkBudget.humanDuration(120_000))
        assertEquals("1 min 05 s", LinkBudget.humanDuration(65_000))
    }

    // --- The decorator ---

    @Test
    fun `throttling holds a frame for its real airtime`() = runTest {
        val throttled = ThrottleWrapper(LoopbackTransport(), bitsPerSecond = 300)
        throttled.connect()

        val before = currentTime
        throttled.send(ByteArray(75))
        val elapsed = currentTime - before

        assertEquals(2_000L, elapsed)
        assertEquals(2_000L, throttled.lastAirtimeMs)
    }

    @Test
    fun `airtime accumulates across frames`() = runTest {
        val throttled = ThrottleWrapper(LoopbackTransport(), bitsPerSecond = 300)
        throttled.connect()
        repeat(3) { throttled.send(ByteArray(75)) }
        assertEquals(6_000L, throttled.totalAirtimeMs)
    }

    @Test
    fun `a faster link is proportionally quicker`() = runTest {
        val slow = ThrottleWrapper(LoopbackTransport(), 300)
        val fast = ThrottleWrapper(LoopbackTransport(), 2_400)
        slow.connect(); fast.connect()

        val t0 = currentTime
        slow.send(ByteArray(75))
        val slowMs = currentTime - t0

        val t1 = currentTime
        fast.send(ByteArray(75))
        val fastMs = currentTime - t1

        assertEquals(8L, slowMs / fastMs)
    }

    @Test
    fun `the frame still arrives intact after being throttled`() = runTest {
        val throttled = ThrottleWrapper(LoopbackTransport(), bitsPerSecond = 1_000)
        throttled.connect()

        val original = PacketCodec.encode(
            Packet(9, 9, LanguageId.TAMIL, Intent.DISTRESS, ByteArray(20) { it.toByte() })
        )

        var received: ByteArray? = null
        val collector = launch { received = throttled.incoming.first() }
        throttled.send(original)
        collector.join()

        assertTrue("nothing arrived", received != null)
        assertTrue("frame was altered", original.contentEquals(received!!))
        // And it must still decode — throttling is transport-level and must not touch
        // the bytes.
        val decoded = PacketCodec.decode(received!!).packetOrNull()
        assertEquals(Intent.DISTRESS, decoded?.intent)
    }

    @Test
    fun `throttle reports itself in the transport name`() {
        val t = ThrottleWrapper(LoopbackTransport(), 300)
        assertTrue("name was '${t.name}'", t.name.contains("300 bps"))
        assertEquals(300, t.nominalBitrate)
        assertTrue(ThrottleWrapper(LoopbackTransport(), 2_400).name.contains("2.4 kbps"))
    }

    @Test
    fun `a non positive bitrate is rejected at construction`() {
        for (bad in listOf(0, -1)) {
            runCatching { ThrottleWrapper(LoopbackTransport(), bad) }
                .onSuccess { throw AssertionError("bitrate $bad was accepted") }
        }
    }
}
