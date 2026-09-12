package com.itantra.codec

/**
 * One ITP-1 message (TRD section 4.2).
 *
 * This is the only thing that ever crosses the link. Everything on the transmit side
 * and everything on the receive side are independent programs that share nothing but
 * this structure and the byte layout in [PacketCodec].
 */
data class Packet(
    /** Random per session. Tells the receiver when the talker changed. 0-255. */
    val sessionId: Int,

    /** Wraps 0-255. Used to de-duplicate when repeat-send FEC is on. */
    val seq: Int,

    /** See [LanguageId]. */
    val langId: Int,

    /** See [Intent]. */
    val intent: Int,

    /** Payload: symbol ids (see [Symbols]), or UTF-8 bytes when [textMode] is set. */
    val payload: ByteArray,

    /** Mean F0 over the utterance, quantised 0-255. 0 means "not measured". */
    val pitch: Int = 0,

    /** Speaking rate, quantised 0-255. 0 means "not measured". */
    val rate: Int = 0,

    /** RMS energy, quantised 0-255. 0 means "not measured". */
    val energy: Int = 0,

    /** 32-byte voice identity, sent once per session. Null on every other packet. */
    val speakerEmbedding: ByteArray? = null,

    /** Reed-Solomon parity is appended. Phase 7. */
    val fecOn: Boolean = false,

    /**
     * True when this is the last packet of an utterance. When false this is a partial
     * prefix and more will follow — this is what lets the far end start speaking
     * before the sender has finished the sentence (PRD F-05).
     */
    val isFinal: Boolean = true,

    /**
     * Payload is UTF-8 text rather than symbol ids.
     *
     * This is the graceful-degradation path. If the phoneme table for a language is
     * not installed, the same packet still carries the message as plain text and
     * everything downstream keeps working. It is also how English runs in Phase 5,
     * before the phoneme path is switched on in Phase 6.
     */
    val textMode: Boolean = false,
) {
    init {
        require(sessionId in 0..255) { "sessionId out of range: $sessionId" }
        require(seq in 0..255) { "seq out of range: $seq" }
        // Only the wire invariant is enforced here: a language id must fit in one byte.
        // Whether THIS build recognises the id is a receiver policy question, not a
        // format question — see [isKnownLanguage]. A newer sender may legitimately use
        // an id we have never heard of, and refusing to construct the packet would mean
        // dropping a message that might be a distress call (TRD section 8).
        require(langId in 0..255) { "langId out of range: $langId" }
        require(Intent.isValid(intent)) { "invalid intent: $intent" }
        require(pitch in 0..255) { "pitch out of range: $pitch" }
        require(rate in 0..255) { "rate out of range: $rate" }
        require(energy in 0..255) { "energy out of range: $energy" }
        require(payload.size <= PacketCodec.MAX_PAYLOAD) {
            "payload too long: ${payload.size} > ${PacketCodec.MAX_PAYLOAD}"
        }
        require(speakerEmbedding == null || speakerEmbedding.size == PacketCodec.SPEAKER_EMB_SIZE) {
            "speakerEmbedding must be exactly ${PacketCodec.SPEAKER_EMB_SIZE} bytes"
        }
    }

    /**
     * Whether this build recognises the sender's language id. False is not an error —
     * the message is still spoken using the receiver's default pack, with a warning.
     */
    val isKnownLanguage: Boolean
        get() = LanguageId.isValid(langId)

    /** Exact number of bytes this packet will occupy on the wire. */
    val wireSize: Int
        get() = PacketCodec.HEADER_SIZE +
            (if (speakerEmbedding != null) PacketCodec.SPEAKER_EMB_SIZE else 0) +
            payload.size +
            PacketCodec.CRC16_SIZE

    /** The payload as readable text, whichever mode it is in. For the debug HUD. */
    fun payloadAsText(): String =
        if (textMode) {
            String(payload, Charsets.UTF_8)
        } else {
            payload.joinToString(" ") { Symbols.debugLabelOf(it.toInt() and 0xFF) }
        }

    // ByteArray fields need hand-written equals/hashCode.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Packet) return false
        return sessionId == other.sessionId &&
            seq == other.seq &&
            langId == other.langId &&
            intent == other.intent &&
            pitch == other.pitch &&
            rate == other.rate &&
            energy == other.energy &&
            fecOn == other.fecOn &&
            isFinal == other.isFinal &&
            textMode == other.textMode &&
            payload.contentEquals(other.payload) &&
            (speakerEmbedding?.contentEquals(other.speakerEmbedding ?: ByteArray(0))
                ?: (other.speakerEmbedding == null))
    }

    override fun hashCode(): Int {
        var r = sessionId
        r = 31 * r + seq
        r = 31 * r + langId
        r = 31 * r + intent
        r = 31 * r + pitch
        r = 31 * r + rate
        r = 31 * r + energy
        r = 31 * r + fecOn.hashCode()
        r = 31 * r + isFinal.hashCode()
        r = 31 * r + textMode.hashCode()
        r = 31 * r + payload.contentHashCode()
        r = 31 * r + (speakerEmbedding?.contentHashCode() ?: 0)
        return r
    }

    override fun toString(): String =
        "Packet(sess=$sessionId seq=$seq lang=${LanguageId.debugNameOf(langId)} " +
            "intent=${Intent.nameOf(intent)} payload=${payload.size}B " +
            "spk=${speakerEmbedding != null} final=$isFinal text=$textMode " +
            "wire=${wireSize}B)"
}

/** Why a received byte array could not be decoded. Every case is recoverable. */
sealed class DecodeError(val message: String) {
    object TooShort : DecodeError("fewer bytes than the minimum frame size")
    data class BadMagic(val found: Int) :
        DecodeError("magic byte was 0x%02X, expected 0xA7".format(found))
    data class UnsupportedVersion(val found: Int) :
        DecodeError("protocol version $found is not supported")
    object HeaderChecksumFailed : DecodeError("header CRC-8 mismatch")
    object FrameChecksumFailed : DecodeError("frame CRC-16 mismatch")
    data class Truncated(val expected: Int, val actual: Int) :
        DecodeError("declared $expected bytes but received $actual")
    data class InvalidField(val field: String, val value: Int) :
        DecodeError("field $field had invalid value $value")
}
