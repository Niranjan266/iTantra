package com.itantra.codec

import java.io.File
import java.text.Normalizer

/**
 * A dictionary of pre-agreed phrases, referenced by number instead of spelled out.
 *
 * ## Why
 *
 * The expensive part of a message is its length. "Flood water is rising near the school,
 * send boats" costs 62 bytes as symbols — already 72× smaller than Opus — but if both
 * ends already hold that sentence in a numbered list, the whole message is a 12-bit
 * index. Header and checksum then dominate: **16 bytes on the wire, 0.43 s at 300 bps**,
 * against 1.7 s for the same sentence spelled out and two full minutes for compressed
 * audio.
 *
 * This is the oldest trick in emergency signalling and the reason flag codes and
 * brevity codes exist. It applies here because disaster traffic is enormously
 * repetitive: a flood response is mostly a few hundred sentences about water, boats,
 * roads, medicine and people.
 *
 * ## What it does not do
 *
 * A codebook only helps for phrases that are in it. Anything else costs full price, and
 * the app must never imply otherwise — [idOf] returning null is the normal case for
 * free speech, not a failure. The measured figures the submission reports are therefore
 * reported per message with the encoding named, never as a single averaged "compression
 * ratio" that quietly assumes a hit.
 *
 * ## Why it lives in the language pack
 *
 * The phrases are **data**, like everything else language-specific in this project
 * (TRD section 7). A Tamil pack carries Tamil phrases; adding a language adds its
 * codebook with no code change, and a district can ship its own list of local place
 * names without a new build. Nothing in this file names a language or a phrase.
 *
 * ## Both ends must agree
 *
 * A phrase id means nothing on its own — it is an index into a list. Two devices with
 * different lists will exchange confident nonsense, which is worse than failing. So the
 * list is fingerprinted ([fingerprint]) and the sender's fingerprint travels with the
 * session; a receiver whose fingerprint differs refuses to decode phrase references and
 * falls back to asking for plain text. That check is the caller's job — this class
 * supplies the fingerprint and does not enforce policy.
 */
class PhraseCodebook private constructor(
    /** Index is the phrase id. Immutable: ids are a wire contract once shared. */
    private val phrases: List<String>,
) {

    companion object {
        /** The file a language pack stores its codebook in, if it has one. */
        const val PACK_FILE = "phrases.txt"

        /**
         * 4096 entries, because the id is carried in 12 bits.
         *
         * Chosen over 8 bits (256 — too few for useful coverage) and over 16 bits
         * (65,536 — a list no one will ever curate, and two bytes wasted on every
         * reference). 4096 is roughly the size of a well-built brevity code.
         */
        const val MAX_ENTRIES = 4096

        /** An empty codebook. Every lookup misses; nothing else in the app changes. */
        val EMPTY = PhraseCodebook(emptyList())

        /**
         * Read a codebook from a pack file.
         *
         * One phrase per line. Blank lines and lines starting with `#` are ignored, so a
         * list can be commented and grouped — but **only non-ignored lines count toward
         * the id**, which is why [fingerprint] exists: reordering or inserting a phrase
         * silently renumbers everything after it.
         *
         * Returns [EMPTY] rather than throwing. A pack with an unreadable codebook must
         * lose compression, not its language.
         */
        fun load(file: File): PhraseCodebook {
            if (!file.isFile) return EMPTY
            val lines = runCatching { file.readLines() }.getOrElse { return EMPTY }
            return of(lines)
        }

        /** Build from raw lines, applying the same comment and blank-line rules. */
        fun of(lines: List<String>): PhraseCodebook {
            val kept = lines
                .map { it.trimEnd('\r').trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .take(MAX_ENTRIES)
            return PhraseCodebook(kept)
        }

        /**
         * Reduce a phrase to the form used for matching.
         *
         * NFC first: Tamil and every other Indic script can represent the same text with
         * different code point sequences, and two spellings that look identical must not
         * miss each other. Then case-folded, punctuation dropped and whitespace
         * collapsed, because a recogniser's output punctuation and spacing are not
         * reliable enough to match on.
         */
        fun normalise(text: String): String {
            val nfc = Normalizer.normalize(text, Normalizer.Form.NFC)
            val stripped = buildString(nfc.length) {
                for (ch in nfc) {
                    when {
                        ch.isLetterOrDigit() -> append(ch)
                        // Keep combining marks: in Indic scripts they are part of the
                        // letter, and isLetterOrDigit is false for them.
                        ch.isDefined() && Character.getType(ch).let {
                            it == Character.NON_SPACING_MARK.toInt() ||
                                it == Character.COMBINING_SPACING_MARK.toInt()
                        } -> append(ch)
                        else -> append(' ')
                    }
                }
            }
            return stripped.trim().replace(WHITESPACE, " ").lowercase()
        }

        private val WHITESPACE = Regex("\\s+")
    }

    val size: Int get() = phrases.size

    val isEmpty: Boolean get() = phrases.isEmpty()

    private val byNormalised: Map<String, Int> =
        // First occurrence wins, so a duplicated phrase cannot make the lower id
        // unreachable. The duplicate id stays decodable; it is simply never chosen.
        LinkedHashMap<String, Int>(phrases.size).apply {
            phrases.forEachIndexed { id, phrase -> putIfAbsent(normalise(phrase), id) }
        }

    /** The phrase id for [text], or null when it is not in the book — the normal case. */
    fun idOf(text: String): Int? = byNormalised[normalise(text)]

    /** The phrase for [id], or null if this book is too short to have one. */
    fun textOf(id: Int): String? = phrases.getOrNull(id)

    /**
     * What [packet] says, resolving a phrase reference against this book.
     *
     * **The single place this resolution happens.** It has to be, because there are three
     * independent consumers — the screen, [com.itantra.tts.SpeechRenderer] synthesis, and
     * streaming synthesis — and a version that only fixed the screen would leave the
     * speaker saying the literal word "PHRASE" while the text displayed correctly. That
     * is precisely the bug this method was extracted to prevent.
     *
     * [unresolved] decides what happens when the reference cannot be looked up: the
     * screen wants a visible marker, while speech wants silence rather than reading a
     * diagnostic aloud to someone in a flood.
     */
    fun resolve(packet: Packet, unresolved: (Int) -> String): String {
        if (packet.textMode) return packet.payloadAsText()
        val id = Symbols.decodePhraseRef(packet.payload, 0)
            ?: return packet.payloadAsText()
        return textOf(id) ?: unresolved(id)
    }

    /**
     * A short, order-sensitive fingerprint of the whole list.
     *
     * Both ends must hold the identical list in the identical order or a phrase id
     * decodes to the wrong sentence. Deliberately cheap (FNV-1a over the normalised
     * phrases) because this is a mismatch check, not a security measure — the threat is
     * a stale pack, not an attacker.
     */
    val fingerprint: Int by lazy {
        var h = -0x7ee3623b // FNV-1a 32-bit offset basis
        for (p in phrases) {
            for (b in normalise(p).toByteArray()) {
                h = h xor (b.toInt() and 0xFF)
                h *= 0x01000193
            }
            h = h xor 0x0A
            h *= 0x01000193
        }
        h
    }
}
