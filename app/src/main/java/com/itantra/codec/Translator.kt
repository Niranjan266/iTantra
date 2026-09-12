package com.itantra.codec

/**
 * Cross-language delivery: a Tamil speaker is heard in Telugu, offline, for **no extra
 * bytes and no extra model**.
 *
 * ## How it works
 *
 * A phrase reference is not words — it is an **index into an agreed list**. So when the
 * codebooks are a parallel corpus, where line N means the same thing in every language,
 * translation is already done by the time the packet arrives:
 *
 * ```
 *   Tamil phone   "வெள்ளம் உயர்கிறது படகுகளை அனுப்புங்கள்"
 *                     -> id 33  (3 bytes on the wire)
 *   Telugu phone   id 33 -> its own line 33, spoken by its own Telugu voice
 * ```
 *
 * Nothing is translated at transmit time, nothing at receive time, and the packet is the
 * same 16 bytes it would have been within one language. The language id in the header says
 * what the speaker used, which is what lets the screen show both wordings — but it plays no
 * part in the lookup.
 *
 * This is why it costs nothing. A neural translation model for ten Indian languages is
 * hundreds of megabytes and tens of milliseconds per sentence; an index into a numbered
 * list is neither.
 *
 * ## What it cannot do, stated plainly
 *
 * **Only codebook phrases translate.** Free speech does not: if the sender said something
 * not in the list, the packet carries their words as text and the far end can display them
 * but not translate them. That is a real limit and the UI must show which of the two
 * happened rather than implying everything is translated.
 *
 * The honest framing is that this translates **the sentences that matter most** — the ones
 * a disaster actually repeats, which is exactly why they are in the codebook — and nothing
 * else. Widening it is a matter of adding phrases, not of adding code.
 *
 * ## The invariant everything rests on
 *
 * Line N must mean the same thing in every language. Break that and the system does not
 * fail, it lies: it delivers a real sentence that nobody sent. Two defences:
 *
 *  - a language missing a phrase writes [PhraseCodebook.UNTRANSLATED] rather than omitting
 *    the line, so ids never shift;
 *  - [PhraseCodebook.fingerprint] differs the moment two files disagree in content or
 *    order, so a mismatch is detectable rather than silent.
 */
object Translator {

    /**
     * What a received message says, in the listener's language and the speaker's.
     *
     * [translated] is what to speak. [original] is what the sender actually said, shown
     * beside it when available — the design puts both on screen, and a bilingual reader
     * checking the two is a genuine safety feature for a distress message.
     */
    data class Delivery(
        /** Words to speak and to read, in the listener's language. Never blank. */
        val translated: String,
        /** The sender's own wording, if this device can reconstruct it. */
        val original: String?,
        /** Header language id of the speaker. */
        val fromLangId: Int,
        /** The listener's language id. */
        val toLangId: Int,
        /** Set when this arrived as a codebook reference. */
        val phraseId: Int?,
        /** How the words were obtained — the UI must not imply more than happened. */
        val kind: Kind,
    ) {
        val isTranslated: Boolean get() = kind == Kind.TRANSLATED
    }

    enum class Kind {
        /** A phrase reference resolved into a different language. Real translation. */
        TRANSLATED,

        /** A phrase reference, speaker and listener already sharing a language. */
        PHRASE_SAME_LANGUAGE,

        /** Plain text, delivered as sent. Not translated, and must not be shown as such. */
        VERBATIM,

        /** A reference this device cannot resolve: wrong or missing codebook. */
        UNRESOLVED,
    }

    /**
     * Resolve [packet] for a listener whose language is [listener].
     *
     * @param listener the listener's codebook — the one that decides the output wording.
     * @param listenerLangId the listener's language id, for reporting only.
     * @param speaker the sender's codebook if this device happens to have that language
     *   installed. Optional: it only supplies [Delivery.original], and its absence costs
     *   the second line on screen, never the message itself.
     */
    fun deliver(
        packet: Packet,
        listener: PhraseCodebook,
        listenerLangId: Int,
        speaker: PhraseCodebook? = null,
    ): Delivery {
        if (packet.textMode) {
            // Plain text. Reporting this as VERBATIM rather than dressing it up is the
            // whole difference between an honest screen and one that claims a translation
            // it never performed.
            return Delivery(
                translated = packet.payloadAsText(),
                original = null,
                fromLangId = packet.langId,
                toLangId = listenerLangId,
                phraseId = null,
                kind = Kind.VERBATIM,
            )
        }

        val id = Symbols.decodePhraseRef(packet.payload, 0)
            ?: return Delivery(
                translated = packet.payloadAsText(),
                original = null,
                fromLangId = packet.langId,
                toLangId = listenerLangId,
                phraseId = null,
                kind = Kind.VERBATIM,
            )

        val mine = listener.textOf(id)
        val theirs = speaker?.textOf(id)

        if (mine == null) {
            // Either our codebook is shorter than theirs or this language has not
            // translated that slot. Show the reference; never invent a sentence.
            return Delivery(
                translated = theirs ?: "[phrase $id]",
                original = theirs,
                fromLangId = packet.langId,
                toLangId = listenerLangId,
                phraseId = id,
                kind = Kind.UNRESOLVED,
            )
        }

        val sameLanguage = packet.langId == listenerLangId
        return Delivery(
            translated = mine,
            // Pointless to repeat the same string twice on screen.
            original = theirs?.takeIf { !sameLanguage && it != mine },
            fromLangId = packet.langId,
            toLangId = listenerLangId,
            phraseId = id,
            kind = if (sameLanguage) Kind.PHRASE_SAME_LANGUAGE else Kind.TRANSLATED,
        )
    }
}
