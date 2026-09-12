package com.itantra.codec

/**
 * ITP-1 binary encoder and decoder (TRD section 4.2).
 *
 * This file is the frozen contract of the whole product. Once a build ships, the byte
 * layout below cannot change — a device in the field must be able to decode a packet
 * from a device that was flashed a year earlier. New capability goes in a new protocol
 * version, never by redefining an existing byte.
 *
 * Layout:
 *
 *   offset  size  field
 *   0       1     MAGIC          0xA7, frame sync
 *   1       1     VER_FLAGS      high nibble = version, low nibble = flags
 *   2       1     SESSION_ID
 *   3       1     SEQ
 *   4       1     LANG_ID
 *   5       1     INTENT
 *   6       1     PITCH
 *   7       1     RATE
 *   8       1     ENERGY
 *   9       1     PAYLOAD_LEN
 *   10      1     HDR_CRC        CRC-8 over bytes 0..9
 *   11      32    SPEAKER_EMB    only when the SPKR_PRESENT flag is set
 *   *       N     PAYLOAD        N = PAYLOAD_LEN
 *   *       2     CRC16          little-endian, over every preceding byte
 *
 * Total = 13 + N bytes, or 45 + N with the speaker sidecar attached.
 */
object PacketCodec {

    const val MAGIC = 0xA7
    const val VERSION = 1

    const val HEADER_SIZE = 11
    const val SPEAKER_EMB_SIZE = 32
    const val CRC16_SIZE = 2
    const val MAX_PAYLOAD = 255

    /** Smallest legal frame: header + empty payload + CRC16. */
    const val MIN_FRAME_SIZE = HEADER_SIZE + CRC16_SIZE

    /** Largest legal frame, with sidecar and a full payload. */
    const val MAX_FRAME_SIZE = HEADER_SIZE + SPEAKER_EMB_SIZE + MAX_PAYLOAD + CRC16_SIZE

    // Flag bits in the low nibble of byte 1.
    private const val FLAG_SPKR_PRESENT = 0x1
    private const val FLAG_FEC_ON = 0x2
    private const val FLAG_FINAL = 0x4
    private const val FLAG_TEXT_MODE = 0x8

    // Header field offsets.
    private const val OFF_MAGIC = 0
    private const val OFF_VER_FLAGS = 1
    private const val OFF_SESSION = 2
    private const val OFF_SEQ = 3
    private const val OFF_LANG = 4
    private const val OFF_INTENT = 5
    private const val OFF_PITCH = 6
    private const val OFF_RATE = 7
    private const val OFF_ENERGY = 8
    private const val OFF_PAYLOAD_LEN = 9
    private const val OFF_HDR_CRC = 10

    fun encode(packet: Packet): ByteArray {
        val hasSpeaker = packet.speakerEmbedding != null
        val out = ByteArray(packet.wireSize)

        var flags = 0
        if (hasSpeaker) flags = flags or FLAG_SPKR_PRESENT
        if (packet.fecOn) flags = flags or FLAG_FEC_ON
        if (packet.isFinal) flags = flags or FLAG_FINAL
        if (packet.textMode) flags = flags or FLAG_TEXT_MODE

        out[OFF_MAGIC] = MAGIC.toByte()
        out[OFF_VER_FLAGS] = ((VERSION shl 4) or flags).toByte()
        out[OFF_SESSION] = packet.sessionId.toByte()
        out[OFF_SEQ] = packet.seq.toByte()
        out[OFF_LANG] = packet.langId.toByte()
        out[OFF_INTENT] = packet.intent.toByte()
        out[OFF_PITCH] = packet.pitch.toByte()
        out[OFF_RATE] = packet.rate.toByte()
        out[OFF_ENERGY] = packet.energy.toByte()
        out[OFF_PAYLOAD_LEN] = packet.payload.size.toByte()
        out[OFF_HDR_CRC] = Crc.crc8(out, 0, OFF_HDR_CRC).toByte()

        var pos = HEADER_SIZE
        if (hasSpeaker) {
            packet.speakerEmbedding!!.copyInto(out, pos)
            pos += SPEAKER_EMB_SIZE
        }
        packet.payload.copyInto(out, pos)
        pos += packet.payload.size

        val crc = Crc.crc16(out, 0, pos)
        out[pos] = (crc and 0xFF).toByte()
        out[pos + 1] = ((crc shr 8) and 0xFF).toByte()

        return out
    }

    /**
     * Decode a received frame.
     *
     * Never throws. A corrupt packet from a noisy radio link must not be able to crash
     * the receiver — in a distress system, dropping one message always beats
     * terminating the process (TRD section 8).
     */
    fun decode(bytes: ByteArray): DecodeResult {
        if (bytes.size < MIN_FRAME_SIZE) {
            return DecodeResult.Failure(DecodeError.TooShort)
        }

        val magic = bytes[OFF_MAGIC].toInt() and 0xFF
        if (magic != MAGIC) {
            return DecodeResult.Failure(DecodeError.BadMagic(magic))
        }

        val verFlags = bytes[OFF_VER_FLAGS].toInt() and 0xFF
        val version = (verFlags shr 4) and 0x0F
        if (version != VERSION) {
            return DecodeResult.Failure(DecodeError.UnsupportedVersion(version))
        }
        val flags = verFlags and 0x0F

        val expectedHdrCrc = Crc.crc8(bytes, 0, OFF_HDR_CRC)
        if ((bytes[OFF_HDR_CRC].toInt() and 0xFF) != expectedHdrCrc) {
            return DecodeResult.Failure(DecodeError.HeaderChecksumFailed)
        }

        // The header is trustworthy from here on, so PAYLOAD_LEN can be believed.
        val hasSpeaker = (flags and FLAG_SPKR_PRESENT) != 0
        val payloadLen = bytes[OFF_PAYLOAD_LEN].toInt() and 0xFF
        val embSize = if (hasSpeaker) SPEAKER_EMB_SIZE else 0
        val expectedSize = HEADER_SIZE + embSize + payloadLen + CRC16_SIZE

        if (bytes.size < expectedSize) {
            return DecodeResult.Failure(DecodeError.Truncated(expectedSize, bytes.size))
        }

        val crcPos = expectedSize - CRC16_SIZE
        val receivedCrc = (bytes[crcPos].toInt() and 0xFF) or
            ((bytes[crcPos + 1].toInt() and 0xFF) shl 8)
        if (Crc.crc16(bytes, 0, crcPos) != receivedCrc) {
            return DecodeResult.Failure(DecodeError.FrameChecksumFailed)
        }

        val langId = bytes[OFF_LANG].toInt() and 0xFF
        val intent = bytes[OFF_INTENT].toInt() and 0xFF
        if (!Intent.isValid(intent)) {
            return DecodeResult.Failure(DecodeError.InvalidField("INTENT", intent))
        }
        // An unknown LANG_ID is deliberately NOT fatal. A newer sender may know a
        // language this build does not; the message is still spoken with the
        // receiver's default pack and a warning is shown (TRD section 8).

        var pos = HEADER_SIZE
        val speaker = if (hasSpeaker) {
            bytes.copyOfRange(pos, pos + SPEAKER_EMB_SIZE).also { pos += SPEAKER_EMB_SIZE }
        } else {
            null
        }
        val payload = bytes.copyOfRange(pos, pos + payloadLen)

        val packet = Packet(
            sessionId = bytes[OFF_SESSION].toInt() and 0xFF,
            seq = bytes[OFF_SEQ].toInt() and 0xFF,
            langId = langId,
            intent = intent,
            payload = payload,
            pitch = bytes[OFF_PITCH].toInt() and 0xFF,
            rate = bytes[OFF_RATE].toInt() and 0xFF,
            energy = bytes[OFF_ENERGY].toInt() and 0xFF,
            speakerEmbedding = speaker,
            fecOn = (flags and FLAG_FEC_ON) != 0,
            isFinal = (flags and FLAG_FINAL) != 0,
            textMode = (flags and FLAG_TEXT_MODE) != 0,
        )

        return DecodeResult.Success(packet, consumed = expectedSize)
    }

    /**
     * How many bytes the frame starting at [offset] occupies, judging by its header
     * alone — or null if the header is not yet complete, or is not a valid header.
     *
     * This is what makes framing possible over a byte stream such as RFCOMM or a
     * serial line, where there are no message boundaries. The reader needs the frame
     * length before it can know whether it has a whole frame, and it must not trust
     * PAYLOAD_LEN until the header CRC says the header is intact — otherwise one
     * corrupted length byte would make the reader swallow the packets that follow.
     *
     * Deliberately does NOT check CRC-16: the payload may not have arrived yet.
     * Full validation stays in [decode].
     */
    fun peekFrameSize(bytes: ByteArray, offset: Int = 0): Int? {
        if (bytes.size - offset < HEADER_SIZE) return null
        if ((bytes[offset + OFF_MAGIC].toInt() and 0xFF) != MAGIC) return null

        val verFlags = bytes[offset + OFF_VER_FLAGS].toInt() and 0xFF
        if (((verFlags shr 4) and 0x0F) != VERSION) return null

        if (Crc.crc8(bytes, offset, offset + OFF_HDR_CRC) !=
            (bytes[offset + OFF_HDR_CRC].toInt() and 0xFF)
        ) return null

        val embSize = if ((verFlags and FLAG_SPKR_PRESENT) != 0) SPEAKER_EMB_SIZE else 0
        val payloadLen = bytes[offset + OFF_PAYLOAD_LEN].toInt() and 0xFF
        return HEADER_SIZE + embSize + payloadLen + CRC16_SIZE
    }

    /** Hex dump for the on-screen debug view. */
    fun hexDump(bytes: ByteArray, maxBytes: Int = 64): String {
        val shown = bytes.take(maxBytes)
        val hex = shown.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        return if (bytes.size > maxBytes) "$hex … (+${bytes.size - maxBytes} more)" else hex
    }
}

sealed class DecodeResult {
    /**
     * @param consumed how many bytes this frame occupied, so a stream reader knows
     *   where the next frame begins.
     */
    data class Success(val packet: Packet, val consumed: Int) : DecodeResult()
    data class Failure(val error: DecodeError) : DecodeResult()

    fun packetOrNull(): Packet? = (this as? Success)?.packet
}
