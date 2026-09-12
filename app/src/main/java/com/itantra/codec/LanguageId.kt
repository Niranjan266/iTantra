package com.itantra.codec

/**
 * The frozen language table (TRD section 4.3).
 *
 * These numbers are a permanent part of the wire format. They are never renumbered,
 * never reordered and never reused. A packet recorded today must still decode to the
 * same language in ten years.
 *
 * Note what is NOT here: model filenames, pack directories, or which languages are
 * actually installed. Those live in the language pack manifest and are discovered at
 * runtime (TRD section 7). This table only says "the number 6 means Tamil, forever".
 */
object LanguageId {

    const val ENGLISH = 0
    const val HINDI = 1
    const val GUJARATI = 2
    const val MARATHI = 3
    const val KANNADA = 4
    const val MALAYALAM = 5
    const val TAMIL = 6
    const val TELUGU = 7
    const val ODIA = 8
    const val BENGALI = 9

    /** Sender did not specify, or asked the receiver to auto-detect. */
    const val UNSPECIFIED = 255

    /** ISO 639-1 code for each id. Index is the language id. */
    private val isoCodes = arrayOf(
        "en", "hi", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn"
    )

    /** English name, for logs and debug output only — never for the UI. */
    private val englishNames = arrayOf(
        "English", "Hindi", "Gujarati", "Marathi", "Kannada",
        "Malayalam", "Tamil", "Telugu", "Odia", "Bengali"
    )

    /** The ten languages named in the problem statement. */
    const val COUNT = 10

    fun isValid(id: Int): Boolean = id in 0 until COUNT || id == UNSPECIFIED

    fun isoCodeOf(id: Int): String? = isoCodes.getOrNull(id)

    fun debugNameOf(id: Int): String = when {
        id == UNSPECIFIED -> "Unspecified"
        else -> englishNames.getOrNull(id) ?: "Unknown($id)"
    }

    fun idOfIsoCode(code: String): Int? {
        val i = isoCodes.indexOf(code.lowercase())
        return if (i >= 0) i else null
    }
}

/**
 * Urgency level carried in every packet (TRD section 4.2, byte 5).
 *
 * DISTRESS is not a UI hint. It is a contract: the receiving device must play the
 * message at maximum volume and must not allow it to be interrupted (PRD F-31, F-32).
 */
object Intent {
    const val ROUTINE = 0
    const val ALERT = 1
    const val DISTRESS = 2

    fun isValid(v: Int): Boolean = v in ROUTINE..DISTRESS

    fun nameOf(v: Int): String = when (v) {
        ROUTINE -> "ROUTINE"
        ALERT -> "ALERT"
        DISTRESS -> "DISTRESS"
        else -> "UNKNOWN($v)"
    }
}
