package com.itantra.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Framing is where stream transports break, and the failures are the awkward kind:
 * they only appear under a particular split of reads, or after a byte is lost on a
 * noisy link. None of that is convenient to reproduce on real hardware, so it is all
 * pinned here instead.
 */
class FrameReaderTest {

    private fun frame(seq: Int, payloadSize: Int = 10, speaker: Boolean = false): ByteArray =
        PacketCodec.encode(
            Packet(
                sessionId = 1, seq = seq,
                langId = LanguageId.ENGLISH, intent = Intent.ROUTINE,
                payload = ByteArray(payloadSize) { (seq + it).toByte() },
                speakerEmbedding = if (speaker) ByteArray(32) { it.toByte() } else null,
            )
        )

    private fun seqsOf(frames: List<ByteArray>): List<Int> =
        frames.mapNotNull { PacketCodec.decode(it).packetOrNull()?.seq }

    @Test
    fun `a whole frame in one read yields one frame`() {
        val r = FrameReader()
        assertEquals(listOf(0), seqsOf(r.append(frame(0))))
        assertEquals(0, r.pendingBytes())
    }

    @Test
    fun `three frames in one read yield three frames in order`() {
        val r = FrameReader()
        val stream = frame(1) + frame(2) + frame(3)
        assertEquals(listOf(1, 2, 3), seqsOf(r.append(stream)))
    }

    @Test
    fun `a frame split across every possible boundary still arrives`() {
        val f = frame(7, payloadSize = 40)
        for (cut in 1 until f.size) {
            val r = FrameReader()
            val first = r.append(f.copyOfRange(0, cut))
            val second = r.append(f.copyOfRange(cut, f.size))
            val all = seqsOf(first + second)
            assertEquals("split at $cut", listOf(7), all)
        }
    }

    @Test
    fun `one byte at a time still reassembles`() {
        val r = FrameReader()
        val stream = frame(4) + frame(5)
        val got = mutableListOf<ByteArray>()
        for (b in stream) got += r.append(byteArrayOf(b))
        assertEquals(listOf(4, 5), seqsOf(got))
    }

    @Test
    fun `leading garbage is skipped`() {
        val r = FrameReader()
        val junk = byteArrayOf(0x00, 0x11, 0x22, 0x33)
        val frames = r.append(junk + frame(9))
        assertEquals(listOf(9), seqsOf(frames))
        assertEquals(4, r.resyncBytes)
    }

    @Test
    fun `garbage containing a false magic byte still resynchronises`() {
        val r = FrameReader()
        // 0xA7 in the junk looks like a frame start but its header will not check out.
        val junk = byteArrayOf(0xA7.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val frames = r.append(junk + frame(11))
        assertEquals(listOf(11), seqsOf(frames))
    }

    @Test
    fun `a lost byte costs one frame and the next frame still arrives`() {
        val r = FrameReader()
        val good = frame(20)
        val damaged = frame(21).let { it.copyOfRange(0, 5) + it.copyOfRange(6, it.size) }
        val recovered = seqsOf(r.append(damaged + good))
        // The damaged frame is gone, but the reader must not be stuck.
        assertTrue("did not recover after a lost byte: $recovered", recovered.contains(20))
    }

    @Test
    fun `speaker sidecar frames are measured correctly`() {
        val r = FrameReader()
        val withSidecar = frame(30, payloadSize = 20, speaker = true)
        assertEquals(11 + 32 + 20 + 2, withSidecar.size)
        assertEquals(listOf(30), seqsOf(r.append(withSidecar)))
        assertEquals(0, r.pendingBytes())
    }

    @Test
    fun `interleaved sidecar and plain frames all arrive`() {
        val r = FrameReader()
        val stream = frame(1, 5, speaker = true) + frame(2, 60) + frame(3, 0, speaker = true)
        assertEquals(listOf(1, 2, 3), seqsOf(r.append(stream)))
    }

    @Test
    fun `maximum size frame reassembles`() {
        val r = FrameReader()
        val max = frame(99, payloadSize = PacketCodec.MAX_PAYLOAD, speaker = true)
        assertEquals(PacketCodec.MAX_FRAME_SIZE, max.size)
        assertEquals(listOf(99), seqsOf(r.append(max)))
    }

    @Test
    fun `buffer never grows without bound on pure garbage`() {
        val r = FrameReader()
        val rng = java.util.Random(7)
        repeat(200) {
            val junk = ByteArray(256).also { rng.nextBytes(it) }
            r.append(junk)
            assertTrue(
                "buffer grew to ${r.pendingBytes()}",
                r.pendingBytes() <= PacketCodec.MAX_FRAME_SIZE * 2,
            )
        }
    }

    @Test
    fun `a real frame is still found after a long run of garbage`() {
        val r = FrameReader()
        val rng = java.util.Random(11)
        repeat(50) {
            val junk = ByteArray(128).also { rng.nextBytes(it) }
            r.append(junk)
        }
        val frames = r.append(frame(42))
        assertTrue("frame 42 lost after garbage: ${seqsOf(frames)}", seqsOf(frames).contains(42))
    }

    @Test
    fun `reset clears state`() {
        val r = FrameReader()
        r.append(frame(1).copyOfRange(0, 6))
        assertTrue(r.pendingBytes() > 0)
        r.reset()
        assertEquals(0, r.pendingBytes())
        assertEquals(0, r.resyncBytes)
    }

    @Test
    fun `peekFrameSize agrees with the encoder for every payload length`() {
        for (n in 0..PacketCodec.MAX_PAYLOAD step 17) {
            for (spk in listOf(false, true)) {
                val f = frame(1, payloadSize = n, speaker = spk)
                assertEquals("n=$n spk=$spk", f.size, PacketCodec.peekFrameSize(f))
            }
        }
    }

    @Test
    fun `peekFrameSize rejects a corrupted header instead of trusting its length`() {
        val f = frame(1, payloadSize = 30)
        f[9] = 0xFF.toByte() // lie about PAYLOAD_LEN, leaving the header CRC stale
        assertEquals(null, PacketCodec.peekFrameSize(f))
    }
}
