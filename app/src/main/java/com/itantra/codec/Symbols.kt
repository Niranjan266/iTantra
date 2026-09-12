package com.itantra.codec

/**
 * The frozen symbol space (TRD section 4.4).
 *
 * One byte per symbol, 256 slots. This allocation is a permanent part of the wire
 * format. New capabilities get new protocol versions; existing slots never change
 * meaning.
 *
 *   0        NUL              padding, never transmitted
 *   1        WORD_BOUNDARY    see the note below — this one matters
 *   2        UTTERANCE_END
 *   3        SENTENCE_BOUNDARY
 *   4        QUESTION
 *   5        PAUSE_SHORT
 *   6        PHRASE_REF       escape: next two bytes are a 12-bit phrase id
 *   7-15     reserved control
 *   16-119   Indic phonemes   (Common Label Set; labels loaded from the language pack)
 *   120-179  English phonemes (ARPAbet; table below)
 *   180-199  numeral tokens
 *   200-239  reserved
 *   240-255  vendor / experimental, never used in a released build
 *
 * WHY WORD_BOUNDARY EXISTS
 * ------------------------
 * Recognising phonemes rather than words means the recogniser does not naturally
 * produce spaces. Without an explicit boundary symbol the received message could be
 * spoken but never displayed as readable text, and the sentence "one hundred and
 * four" would be indistinguishable from "onehundredandfour" to any downstream code.
 * Reserving slot 1 from version 1 of the format solves that permanently, at a cost of
 * about one byte per word. This is the concrete answer to the risk in PRD section 11.
 */
object Symbols {

    // --- Control symbols (slots 0-15) ---
    const val NUL = 0
    const val WORD_BOUNDARY = 1
    const val UTTERANCE_END = 2
    const val SENTENCE_BOUNDARY = 3
    const val QUESTION = 4
    const val PAUSE_SHORT = 5

    /**
     * Escape: the next two bytes are a 12-bit phrase id (see [PhraseCodebook]).
     *
     * Taken from the range this table reserved for exactly this purpose — "6-15
     * reserved control" — so nothing that already had a meaning has been redefined and
     * the protocol version does not change. That reservation is what makes the codebook
     * addable at all; the flags nibble in the header has no spare bit.
     *
     * A receiver that predates this escape sees an unassigned control symbol. It must
     * skip it, which is the behaviour every unknown control symbol already requires —
     * so an older build degrades to dropping the phrase rather than to mis-speaking it.
     */
    const val PHRASE_REF = 6

    /** Bytes on the wire for one phrase reference: the escape plus a 12-bit id. */
    const val PHRASE_REF_SIZE = 3

    const val CONTROL_FIRST = 0
    const val CONTROL_LAST = 15

    // --- Indic phoneme range (slots 16-119) ---
    // Labels are NOT hardcoded here. They are loaded per language from the pack's
    // phonemes.tsv (TRD section 7), because the authoritative Common Label Set is
    // data, not code. Only the range is frozen.
    const val INDIC_FIRST = 16
    const val INDIC_LAST = 119

    // --- English phoneme range (slots 120-179) ---
    const val ENGLISH_FIRST = 120
    const val ENGLISH_LAST = 179

    // --- Numerals (slots 180-199) ---
    const val NUMERAL_FIRST = 180
    const val NUMERAL_LAST = 199

    // --- Reserved (slots 200-255) ---
    const val RESERVED_FIRST = 200
    const val VENDOR_FIRST = 240

    /**
     * ARPAbet, the standard 39-phoneme inventory for English, laid out from
     * ENGLISH_FIRST in fixed order. Index in this array plus 120 is the symbol id.
     * The remaining slots up to 179 are left empty on purpose, so stress-marked or
     * dialect variants can be added later without shifting anything.
     */
    val arpabet: Array<String> = arrayOf(
        "AA", "AE", "AH", "AO", "AW", "AY", "B", "CH", "D", "DH",
        "EH", "ER", "EY", "F", "G", "HH", "IH", "IY", "JH", "K",
        "L", "M", "N", "NG", "OW", "OY", "P", "R", "S", "SH",
        "T", "TH", "UH", "UW", "V", "W", "Y", "Z", "ZH"
    )

    private val arpabetToId: Map<String, Int> =
        arpabet.withIndex().associate { (i, label) -> label to (ENGLISH_FIRST + i) }

    fun englishSymbolOf(arpabetLabel: String): Int? = arpabetToId[arpabetLabel.uppercase()]

    fun englishLabelOf(symbol: Int): String? =
        arpabet.getOrNull(symbol - ENGLISH_FIRST)

    /** A symbol id must fit in one byte. Anything else is a programming error. */
    fun isValid(symbol: Int): Boolean = symbol in 0..255

    /**
     * Encode one phrase reference: [PHRASE_REF], then the id big-endian in 12 bits.
     *
     * Big-endian to match every other multi-byte field in this format, so a hex dump
     * reads the same way throughout. The top nibble of the first id byte is zero and is
     * left that way — it is spare, and a future version can spend it.
     */
    fun encodePhraseRef(phraseId: Int): ByteArray {
        require(phraseId in 0 until PhraseCodebook.MAX_ENTRIES) {
            "phrase id out of range: $phraseId"
        }
        return byteArrayOf(
            PHRASE_REF.toByte(),
            ((phraseId shr 8) and 0x0F).toByte(),
            (phraseId and 0xFF).toByte(),
        )
    }

    /**
     * Read the phrase id at [offset], which must point at a [PHRASE_REF].
     *
     * Returns null if the escape is truncated — a packet may be cut short by a bad link,
     * and a half-read id would decode to a real but wrong sentence. Failing closed here
     * is the difference between losing a message and delivering a false one.
     */
    fun decodePhraseRef(payload: ByteArray, offset: Int): Int? {
        if (offset + PHRASE_REF_SIZE > payload.size) return null
        if ((payload[offset].toInt() and 0xFF) != PHRASE_REF) return null
        val hi = payload[offset + 1].toInt() and 0x0F
        val lo = payload[offset + 2].toInt() and 0xFF
        return (hi shl 8) or lo
    }

    fun isControl(symbol: Int): Boolean = symbol in CONTROL_FIRST..CONTROL_LAST
    fun isIndic(symbol: Int): Boolean = symbol in INDIC_FIRST..INDIC_LAST
    fun isEnglish(symbol: Int): Boolean = symbol in ENGLISH_FIRST..ENGLISH_LAST
    fun isVendor(symbol: Int): Boolean = symbol >= VENDOR_FIRST

    /** Human-readable rendering, for the debug hex/symbol dump only. */
    fun debugLabelOf(symbol: Int): String = when (symbol) {
        NUL -> "_"
        WORD_BOUNDARY -> "/"
        UTTERANCE_END -> "<END>"
        SENTENCE_BOUNDARY -> "."
        QUESTION -> "?"
        PAUSE_SHORT -> ","
        PHRASE_REF -> "<PHRASE>"
        else -> englishLabelOf(symbol) ?: "#$symbol"
    }
}
