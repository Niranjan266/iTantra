package com.itantra.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Translation by shared phrase id.
 *
 * The case that matters most is [aMisalignedCodebookIsWhatThisGuardsAgainst]: if two
 * languages disagree about what an id means, the system does not fail — it delivers a
 * real sentence nobody sent. Everything here exists to keep that impossible.
 */
class TranslatorTest {

    // A parallel corpus: line N means the same thing in both. Slot 2 is untranslated in
    // Tamil, which must hold its id rather than shift 3 upward.
    private val en = PhraseCodebook.of(listOf("send boats", "we need a doctor", "wait, out", "no"))
    private val ta = PhraseCodebook.of(listOf("padagugalai anuppungal", "maruthuvar thevai", "-", "illai"))

    private fun ref(id: Int, langId: Int) = Packet(
        sessionId = 1, seq = 0, langId = langId, intent = Intent.ROUTINE,
        payload = Symbols.encodePhraseRef(id), textMode = false,
    )

    private fun text(s: String, langId: Int) = Packet(
        sessionId = 1, seq = 0, langId = langId, intent = Intent.ROUTINE,
        payload = s.toByteArray(Charsets.UTF_8), textMode = true,
    )

    @Test
    fun aTamilSenderIsHeardInEnglish() {
        val d = Translator.deliver(ref(0, LanguageId.TAMIL), listener = en,
            listenerLangId = LanguageId.ENGLISH, speaker = ta)
        assertEquals("send boats", d.translated)
        assertEquals("padagugalai anuppungal", d.original)
        assertEquals(Translator.Kind.TRANSLATED, d.kind)
        assertTrue(d.isTranslated)
        assertEquals(0, d.phraseId)
    }

    @Test
    fun anEnglishSenderIsHeardInTamil() {
        val d = Translator.deliver(ref(1, LanguageId.ENGLISH), listener = ta,
            listenerLangId = LanguageId.TAMIL, speaker = en)
        assertEquals("maruthuvar thevai", d.translated)
        assertEquals("we need a doctor", d.original)
        assertTrue(d.isTranslated)
    }

    @Test
    fun translationNeedsNoExtraBytes() {
        // The point of the whole design: a translated message is the same 16 bytes as an
        // untranslated one, because only the index travels.
        val frame = PacketCodec.encode(ref(0, LanguageId.TAMIL))
        assertEquals(16, frame.size)
    }

    @Test
    fun theSpeakersCodebookIsOptional() {
        // A device without the sender's language still gets the message, just not the
        // second line on screen.
        val d = Translator.deliver(ref(0, LanguageId.TAMIL), listener = en,
            listenerLangId = LanguageId.ENGLISH, speaker = null)
        assertEquals("send boats", d.translated)
        assertNull(d.original)
        assertTrue(d.isTranslated)
    }

    @Test
    fun sameLanguageIsNotReportedAsTranslated() {
        val d = Translator.deliver(ref(0, LanguageId.ENGLISH), listener = en,
            listenerLangId = LanguageId.ENGLISH, speaker = en)
        assertEquals("send boats", d.translated)
        assertEquals(Translator.Kind.PHRASE_SAME_LANGUAGE, d.kind)
        assertTrue(!d.isTranslated)
        assertNull("no point showing the same string twice", d.original)
    }

    @Test
    fun plainTextIsNeverReportedAsTranslated() {
        // Free speech cannot be translated by this mechanism, and the screen must not
        // claim otherwise.
        val d = Translator.deliver(text("the goat is on the roof", LanguageId.ENGLISH),
            listener = ta, listenerLangId = LanguageId.TAMIL, speaker = en)
        assertEquals("the goat is on the roof", d.translated)
        assertEquals(Translator.Kind.VERBATIM, d.kind)
        assertTrue(!d.isTranslated)
    }

    @Test
    fun anUntranslatedSlotFallsBackToTheSendersWording() {
        // Slot 2 has no Tamil. Better to show the English than to invent Tamil.
        val d = Translator.deliver(ref(2, LanguageId.ENGLISH), listener = ta,
            listenerLangId = LanguageId.TAMIL, speaker = en)
        assertEquals("wait, out", d.translated)
        assertEquals(Translator.Kind.UNRESOLVED, d.kind)
        assertTrue(!d.isTranslated)
    }

    @Test
    fun anIdBeyondOurCodebookNeverInventsASentence() {
        val d = Translator.deliver(ref(900, LanguageId.ENGLISH), listener = ta,
            listenerLangId = LanguageId.TAMIL, speaker = null)
        assertEquals("[phrase 900]", d.translated)
        assertEquals(Translator.Kind.UNRESOLVED, d.kind)
    }

    @Test
    fun untranslatedSlotsHoldTheirIdRatherThanShiftingLater()  {
        // The reason UNTRANSLATED exists. Slot 2 is "-" in Tamil, so slot 3 must still be
        // "no"/"illai" in both. Omitting the line would make id 3 mean two things.
        assertEquals("no", en.textOf(3))
        assertEquals("illai", ta.textOf(3))
        assertNull("an untranslated slot has no wording", ta.textOf(2))
        assertEquals("but it still occupies its id", 4, ta.size)
    }

    @Test
    fun anUntranslatedSlotIsNeverMatchedWhenEncoding() {
        // Otherwise every unrecognised utterance would compress to the first "-".
        assertNull(ta.idOf("-"))
        assertEquals(3, ta.translatedCount)
    }

    @Test
    fun aMisalignedCodebookIsWhatThisGuardsAgainst() {
        // Written independently rather than as a parallel corpus: the real first Tamil
        // file. Id 3 meant "wait, out" to the sender and "yes" to the receiver — a
        // confident, wrong, plausible sentence. The fingerprints differ, which is the
        // only way to notice.
        val misaligned = PhraseCodebook.of(listOf("send boats", "we need a doctor", "yes"))
        assertTrue(
            "a divergent list must be detectable",
            misaligned.fingerprint != en.fingerprint,
        )
    }

    @Test
    fun theShippedCodebooksAreAParallelCorpus() {
        // Guards the actual data, not just the mechanism. English and Tamil must hold the
        // same number of slots or every id past the first divergence means two things.
        val enFile = listOf(
            java.io.File("src/main/assets/languages/en/phrases.txt"),
            java.io.File("app/src/main/assets/languages/en/phrases.txt"),
        ).firstOrNull { it.isFile }
        val taFile = listOf(
            java.io.File("../build/packs/ta/phrases.txt"),
            java.io.File("build/packs/ta/phrases.txt"),
        ).firstOrNull { it.isFile }
        org.junit.Assume.assumeTrue("codebooks not found from test dir",
            enFile != null && taFile != null)

        val e = PhraseCodebook.load(enFile!!)
        val t = PhraseCodebook.load(taFile!!)
        assertEquals("English and Tamil must have the same number of slots", e.size, t.size)
        assertEquals("the demo phrase must be id 33 in English",
            33, e.idOf("Flood water is rising near the school, send boats"))
        assertTrue("and Tamil must have a wording for id 33", t.textOf(33) != null)
    }
}
