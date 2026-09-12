package com.itantra.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These tests are the actual specification of the wire format. If the format ever needs
 * to change, a test here must be deliberately edited first — which is the point. It
 * makes an accidental change to a shipped contract impossible to do quietly.
 */
class PacketCodecTest {

    private fun decodeOk(bytes: ByteArray): Packet {
        val r = PacketCodec.decode(bytes)
        assertTrue("expected success, got $r", r is DecodeResult.Success)
        return (r as DecodeResult.Success).packet
    }

    private fun decodeErr(bytes: ByteArray): DecodeError {
        val r = PacketCodec.decode(bytes)
        assertTrue("expected failure, got $r", r is DecodeResult.Failure)
        return (r as DecodeResult.Failure).error
    }

    // --- Frozen layout ---

    @Test
    fun `empty routine english packet is exactly 13 bytes`() {
        val p = Packet(
            sessionId = 0x2A, seq = 0,
            langId = LanguageId.ENGLISH, intent = Intent.ROUTINE,
            payload = ByteArray(0), isFinal = false,
        )
        val bytes = PacketCodec.encode(p)
        assertEquals(13, bytes.size)
        assertEquals(13, p.wireSize)
    }

    @Test
    fun `header bytes are laid out exactly as specified`() {
        val p = Packet(
            sessionId = 0x2A, seq = 0x07,
            langId = LanguageId.HINDI, intent = Intent.DISTRESS,
            payload = byteArrayOf(1, 2, 3),
            pitch = 200, rate = 128, energy = 64,
            isFinal = true,
        )
        val b = PacketCodec.encode(p)
        assertEquals(0xA7, b[0].toInt() and 0xFF)   // MAGIC
        assertEquals(0x14, b[1].toInt() and 0xFF)   // version 1, FINAL flag
        assertEquals(0x2A, b[2].toInt() and 0xFF)   // SESSION_ID
        assertEquals(0x07, b[3].toInt() and 0xFF)   // SEQ
        assertEquals(1, b[4].toInt() and 0xFF)      // LANG_ID Hindi
        assertEquals(2, b[5].toInt() and 0xFF)      // INTENT DISTRESS
        assertEquals(200, b[6].toInt() and 0xFF)    // PITCH
        assertEquals(128, b[7].toInt() and 0xFF)    // RATE
        assertEquals(64, b[8].toInt() and 0xFF)     // ENERGY
        assertEquals(3, b[9].toInt() and 0xFF)      // PAYLOAD_LEN
        assertEquals(Crc.crc8(b, 0, 10), b[10].toInt() and 0xFF)
    }

    @Test
    fun `speaker sidecar adds exactly 32 bytes`() {
        val emb = ByteArray(32) { it.toByte() }
        val without = Packet(1, 1, 0, 0, byteArrayOf(9, 9))
        val with = Packet(1, 1, 0, 0, byteArrayOf(9, 9), speakerEmbedding = emb)
        assertEquals(32, PacketCodec.encode(with).size - PacketCodec.encode(without).size)
    }

    @Test
    fun `crc16 is little endian`() {
        val p = Packet(5, 5, 0, 0, byteArrayOf(1, 2, 3, 4))
        val b = PacketCodec.encode(p)
        val crcPos = b.size - 2
        val expected = Crc.crc16(b, 0, crcPos)
        val actual = (b[crcPos].toInt() and 0xFF) or ((b[crcPos + 1].toInt() and 0xFF) shl 8)
        assertEquals(expected, actual)
    }

    // --- Round trips ---

    @Test
    fun `round trip preserves every field`() {
        val original = Packet(
            sessionId = 255, seq = 254,
            langId = LanguageId.TAMIL, intent = Intent.ALERT,
            payload = ByteArray(200) { (it % 256).toByte() },
            pitch = 1, rate = 2, energy = 3,
            speakerEmbedding = ByteArray(32) { (255 - it).toByte() },
            fecOn = true, isFinal = false, textMode = true,
        )
        assertEquals(original, decodeOk(PacketCodec.encode(original)))
    }

    @Test
    fun `round trip across the full range of every scalar field`() {
        for (v in 0..255) {
            val p = Packet(
                sessionId = v, seq = v,
                langId = if (v < LanguageId.COUNT) v else LanguageId.UNSPECIFIED,
                intent = v % 3,
                payload = byteArrayOf(v.toByte()),
                pitch = v, rate = 255 - v, energy = (v * 3) % 256,
            )
            assertEquals("failed at v=$v", p, decodeOk(PacketCodec.encode(p)))
        }
    }

    @Test
    fun `every flag combination round trips`() {
        for (mask in 0..15) {
            val p = Packet(
                sessionId = 1, seq = 1, langId = 0, intent = 0,
                payload = byteArrayOf(7),
                speakerEmbedding = if (mask and 1 != 0) ByteArray(32) else null,
                fecOn = mask and 2 != 0,
                isFinal = mask and 4 != 0,
                textMode = mask and 8 != 0,
            )
            assertEquals("failed at mask=$mask", p, decodeOk(PacketCodec.encode(p)))
        }
    }

    @Test
    fun `maximum payload round trips and fits the declared frame size`() {
        val p = Packet(
            sessionId = 0, seq = 0, langId = 0, intent = 0,
            payload = ByteArray(PacketCodec.MAX_PAYLOAD) { 0x41 },
            speakerEmbedding = ByteArray(32),
        )
        val b = PacketCodec.encode(p)
        assertEquals(PacketCodec.MAX_FRAME_SIZE, b.size)
        assertEquals(p, decodeOk(b))
    }

    @Test
    fun `utf8 text payload survives non-latin scripts`() {
        val hindi = "बाढ़ आ रही है"
        val p = Packet(
            sessionId = 3, seq = 3,
            langId = LanguageId.HINDI, intent = Intent.ALERT,
            payload = hindi.toByteArray(Charsets.UTF_8),
            textMode = true,
        )
        assertEquals(hindi, decodeOk(PacketCodec.encode(p)).payloadAsText())
    }

    // --- Rejection ---

    @Test
    fun `too short is rejected`() {
        assertEquals(DecodeError.TooShort, decodeErr(ByteArray(12)))
    }

    @Test
    fun `bad magic is rejected`() {
        val b = PacketCodec.encode(Packet(1, 1, 0, 0, byteArrayOf(1)))
        b[0] = 0x00
        assertTrue(decodeErr(b) is DecodeError.BadMagic)
    }

    @Test
    fun `unsupported version is rejected`() {
        val b = PacketCodec.encode(Packet(1, 1, 0, 0, byteArrayOf(1)))
        b[1] = ((2 shl 4) or (b[1].toInt() and 0x0F)).toByte()
        assertTrue(decodeErr(b) is DecodeError.UnsupportedVersion)
    }

    @Test
    fun `corrupt header is caught by the header crc before the payload is read`() {
        val b = PacketCodec.encode(Packet(1, 1, 0, 0, ByteArray(50)))
        b[9] = 0xFF.toByte() // lie about PAYLOAD_LEN
        assertEquals(DecodeError.HeaderChecksumFailed, decodeErr(b))
    }

    @Test
    fun `truncated frame is rejected`() {
        val full = PacketCodec.encode(Packet(1, 1, 0, 0, ByteArray(40)))
        val cut = full.copyOfRange(0, full.size - 5)
        assertTrue(decodeErr(cut) is DecodeError.Truncated)
    }

    @Test
    fun `invalid intent is rejected`() {
        val b = PacketCodec.encode(Packet(1, 1, 0, 0, byteArrayOf(1)))
        b[5] = 9
        b[10] = Crc.crc8(b, 0, 10).toByte()          // repair header crc
        val crcPos = b.size - 2
        val crc = Crc.crc16(b, 0, crcPos)            // repair frame crc
        b[crcPos] = (crc and 0xFF).toByte()
        b[crcPos + 1] = ((crc shr 8) and 0xFF).toByte()
        assertTrue(decodeErr(b) is DecodeError.InvalidField)
    }

    @Test
    fun `unknown language is accepted rather than dropped`() {
        // A newer sender may know a language this build does not. Losing a distress
        // message over that would be indefensible, so it must decode (TRD section 8).
        val b = PacketCodec.encode(Packet(1, 1, 0, 0, byteArrayOf(1)))
        b[4] = 42
        b[10] = Crc.crc8(b, 0, 10).toByte()
        val crcPos = b.size - 2
        val crc = Crc.crc16(b, 0, crcPos)
        b[crcPos] = (crc and 0xFF).toByte()
        b[crcPos + 1] = ((crc shr 8) and 0xFF).toByte()
        val received = decodeOk(b)
        assertEquals(42, received.langId)
        // Decoded successfully, but flagged so the receiver can warn and fall back.
        assertTrue("unknown id should not be reported as known", !received.isKnownLanguage)
        assertTrue(Packet(1, 1, LanguageId.TAMIL, 0, byteArrayOf(1)).isKnownLanguage)
    }

    @Test
    fun `every single bit corruption of a payload byte is detected`() {
        val original = PacketCodec.encode(
            Packet(0x2A, 7, LanguageId.HINDI, Intent.DISTRESS, ByteArray(62) { it.toByte() })
        )
        var undetected = 0
        for (i in original.indices) {
            for (bit in 0 until 8) {
                val c = original.copyOf()
                c[i] = (c[i].toInt() xor (1 shl bit)).toByte()
                if (PacketCodec.decode(c) is DecodeResult.Success) undetected++
            }
        }
        assertEquals("undetected single-bit corruptions", 0, undetected)
    }

    @Test
    fun `decode never throws on random input`() {
        val rng = java.util.Random(42)
        repeat(5000) {
            val len = rng.nextInt(320)
            val junk = ByteArray(len).also { rng.nextBytes(it) }
            PacketCodec.decode(junk) // must return a result, not throw
        }
    }

    // --- Size claims from the PRD ---

    @Test
    fun `a twenty word sentence stays under the ninety six byte budget`() {
        // 62 phoneme symbols is a realistic 20-word Indic sentence (TRD section 4.2).
        val p = Packet(
            sessionId = 1, seq = 1,
            langId = LanguageId.HINDI, intent = Intent.ALERT,
            payload = ByteArray(62) { Symbols.INDIC_FIRST.toByte() },
        )
        assertEquals(75, p.wireSize)
        assertTrue("PRD F-11 budget exceeded: ${p.wireSize}", p.wireSize <= 96)
    }

    @Test
    fun `consumed length lets a stream reader find the next frame`() {
        val a = PacketCodec.encode(Packet(1, 1, 0, 0, ByteArray(10)))
        val b = PacketCodec.encode(Packet(2, 2, 0, 0, ByteArray(20)))
        val stream = a + b
        val first = PacketCodec.decode(stream) as DecodeResult.Success
        assertEquals(a.size, first.consumed)
        val second = decodeOk(stream.copyOfRange(first.consumed, stream.size))
        assertEquals(2, second.seq)
    }

    // --- Frozen tables ---

    @Test
    fun `language ids never move`() {
        assertEquals(0, LanguageId.ENGLISH)
        assertEquals(1, LanguageId.HINDI)
        assertEquals(2, LanguageId.GUJARATI)
        assertEquals(3, LanguageId.MARATHI)
        assertEquals(4, LanguageId.KANNADA)
        assertEquals(5, LanguageId.MALAYALAM)
        assertEquals(6, LanguageId.TAMIL)
        assertEquals(7, LanguageId.TELUGU)
        assertEquals(8, LanguageId.ODIA)
        assertEquals(9, LanguageId.BENGALI)
        assertEquals(255, LanguageId.UNSPECIFIED)
        assertEquals("ta", LanguageId.isoCodeOf(LanguageId.TAMIL))
        assertEquals(LanguageId.BENGALI, LanguageId.idOfIsoCode("bn"))
    }

    @Test
    fun `symbol ranges never move and do not overlap`() {
        assertEquals(1, Symbols.WORD_BOUNDARY)
        assertEquals(2, Symbols.UTTERANCE_END)
        assertEquals(16, Symbols.INDIC_FIRST)
        assertEquals(119, Symbols.INDIC_LAST)
        assertEquals(120, Symbols.ENGLISH_FIRST)
        assertEquals(179, Symbols.ENGLISH_LAST)
        assertTrue(Symbols.INDIC_LAST < Symbols.ENGLISH_FIRST)
        assertTrue(Symbols.ENGLISH_LAST < Symbols.NUMERAL_FIRST)
    }

    @Test
    fun `arpabet round trips and fits its reserved range`() {
        assertEquals(39, Symbols.arpabet.size)
        for (label in Symbols.arpabet) {
            val id = Symbols.englishSymbolOf(label)
            assertNotNull("no id for $label", id)
            assertTrue("$label at $id escapes its range", Symbols.isEnglish(id!!))
            assertEquals(label, Symbols.englishLabelOf(id))
        }
        assertNull(Symbols.englishSymbolOf("NOT_A_PHONEME"))
    }
}
