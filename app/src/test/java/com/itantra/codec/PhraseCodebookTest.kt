package com.itantra.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhraseCodebookTest {

    private val book = PhraseCodebook.of(
        listOf(
            "# a comment, which must not take an id",
            "",
            "send boats",                 // 0
            "we need a doctor",           // 1
            "Flood water is rising",      // 2
            "   ",
            "# another comment",
            "we are ten people",          // 3
        )
    )

    // --- ids ------------------------------------------------------------------------

    @Test
    fun commentsAndBlankLinesDoNotConsumeIds() {
        assertEquals(4, book.size)
        assertEquals(0, book.idOf("send boats"))
        assertEquals(3, book.idOf("we are ten people"))
    }

    @Test
    fun roundTripsAPhrase() {
        val id = book.idOf("we need a doctor")!!
        assertEquals("we need a doctor", book.textOf(id))
    }

    @Test
    fun missingPhraseReturnsNull() {
        // The normal case for free speech, not an error.
        assertNull(book.idOf("the goat is on the roof"))
    }

    @Test
    fun outOfRangeIdReturnsNullRatherThanThrowing() {
        // A sender with a longer codebook than ours must not crash this receiver.
        assertNull(book.textOf(4000))
        assertNull(book.textOf(-1))
    }

    // --- matching --------------------------------------------------------------------

    @Test
    fun matchIgnoresCase() {
        assertEquals(2, book.idOf("FLOOD WATER IS RISING"))
    }

    @Test
    fun matchIgnoresPunctuationAndSpacing() {
        // A recogniser's punctuation and spacing are not reliable enough to match on.
        assertEquals(0, book.idOf("  Send,  boats!  "))
    }

    @Test
    fun matchIsNfcInsensitiveForIndicText() {
        // Tamil "நௌ": U+0BCC (vowel sign AU) canonically decomposes to U+0BC6 U+0BD7.
        // Both spellings render identically, so a codebook stored one way must be found
        // when the text arrives the other way. Without NFC these are unequal strings and
        // the lookup silently misses — the message then costs full price for no reason.
        // Built from named code points on purpose. Written as literals these two are
        // visually IDENTICAL in the source, so a formatter or a tidying edit could
        // silently make them the same string and hollow out the test.
        val na = 'ந'            // TAMIL LETTER NA
        val vowelAu = 'ௌ'       // TAMIL VOWEL SIGN AU - decomposes to the next two
        val vowelE = 'ெ'        // TAMIL VOWEL SIGN E
        val auLengthMark = 'ௗ'  // TAMIL AU LENGTH MARK

        val composed = "$na$vowelAu"
        val decomposed = "$na$vowelE$auLengthMark"
        assertNotEquals("the two spellings really are different strings", composed, decomposed)

        val tamil = PhraseCodebook.of(listOf(composed))
        assertEquals("stored composed, looked up composed", 0, tamil.idOf(composed))
        assertEquals("stored composed, looked up decomposed", 0, tamil.idOf(decomposed))

        // And the other way round, since which form a pack author typed is not knowable.
        val other = PhraseCodebook.of(listOf(decomposed))
        assertEquals(0, other.idOf(composed))
    }

    @Test
    fun normalisationKeepsIndicCombiningMarks() {
        // Combining marks are part of the letter in Indic scripts and are not
        // isLetterOrDigit. Dropping them would merge distinct Tamil words.
        // TAMIL: PA + virama-less consonant + KA + vowel sign U  (padagu, "boat")
        val withMark = "படகு"
        assertEquals(withMark.lowercase(), PhraseCodebook.normalise(withMark))
    }

    @Test
    fun firstOccurrenceWinsForDuplicates() {
        val dup = PhraseCodebook.of(listOf("send boats", "other", "Send Boats"))
        assertEquals("lower id must stay reachable", 0, dup.idOf("send boats"))
        // The duplicate is still decodable — ids are a wire contract, never reused.
        assertEquals("Send Boats", dup.textOf(2))
    }

    // --- the list is a contract -------------------------------------------------------

    @Test
    fun fingerprintIsStableForTheSameList() {
        assertEquals(
            PhraseCodebook.of(listOf("a", "b", "c")).fingerprint,
            PhraseCodebook.of(listOf("a", "b", "c")).fingerprint,
        )
    }

    @Test
    fun fingerprintChangesWhenOrderChanges() {
        // Reordering renumbers every phrase after the change. This is exactly the
        // mismatch that would make two devices exchange confident nonsense.
        assertNotEquals(
            PhraseCodebook.of(listOf("a", "b", "c")).fingerprint,
            PhraseCodebook.of(listOf("a", "c", "b")).fingerprint,
        )
    }

    @Test
    fun fingerprintChangesWhenAPhraseIsAdded() {
        assertNotEquals(
            PhraseCodebook.of(listOf("a", "b")).fingerprint,
            PhraseCodebook.of(listOf("a", "b", "c")).fingerprint,
        )
    }

    @Test
    fun listIsCappedAtTheTwelveBitLimit() {
        val huge = PhraseCodebook.of((0..5000).map { "phrase $it" })
        assertEquals(PhraseCodebook.MAX_ENTRIES, huge.size)
        // Every retained id must be encodable.
        assertEquals(4095, huge.idOf("phrase 4095"))
        assertNull(huge.idOf("phrase 4096"))
    }

    @Test
    fun missingFileYieldsAnEmptyBookNotAnError() {
        // A pack with no codebook loses compression, not its language.
        val book = PhraseCodebook.load(File("does-not-exist.txt"))
        assertTrue(book.isEmpty)
        assertNull(book.idOf("send boats"))
    }

    // --- wire encoding ----------------------------------------------------------------

    @Test
    fun phraseRefRoundTripsAtTheEdges() {
        for (id in listOf(0, 1, 255, 256, 4094, 4095)) {
            val bytes = Symbols.encodePhraseRef(id)
            assertEquals(Symbols.PHRASE_REF_SIZE, bytes.size)
            assertEquals("id $id", id, Symbols.decodePhraseRef(bytes, 0))
        }
    }

    @Test
    fun phraseRefUsesAReservedControlSlot() {
        // The whole reason this was addable without a new protocol version.
        assertTrue(Symbols.isControl(Symbols.PHRASE_REF))
        assertEquals(6, Symbols.PHRASE_REF)
    }

    @Test(expected = IllegalArgumentException::class)
    fun encodingRejectsAnIdThatWillNotFitInTwelveBits() {
        Symbols.encodePhraseRef(PhraseCodebook.MAX_ENTRIES)
    }

    @Test
    fun truncatedPhraseRefDecodesToNull() {
        // A link may cut a packet short. Half an id would decode to a real but WRONG
        // sentence, so this must fail closed.
        val full = Symbols.encodePhraseRef(300)
        assertNull(Symbols.decodePhraseRef(full.copyOf(2), 0))
        assertNull(Symbols.decodePhraseRef(full.copyOf(1), 0))
        assertNull(Symbols.decodePhraseRef(ByteArray(0), 0))
    }

    @Test
    fun decodingRefusesAPayloadThatIsNotAPhraseRef() {
        val notARef = byteArrayOf(Symbols.WORD_BOUNDARY.toByte(), 0, 5)
        assertNull(Symbols.decodePhraseRef(notARef, 0))
    }

    @Test
    fun decodesAPhraseRefThatIsNotAtTheStart() {
        val payload = byteArrayOf(Symbols.WORD_BOUNDARY.toByte()) + Symbols.encodePhraseRef(33)
        assertEquals(33, Symbols.decodePhraseRef(payload, 1))
    }

    // --- the efficiency claim, as a measurement ---------------------------------------

    @Test
    fun aPhraseReferenceCostsSixteenBytesOnTheWire() {
        // This is the number the submission reports, so it is measured through the real
        // codec rather than asserted in prose: 11-byte header + 3-byte ref + 2-byte CRC.
        val packet = Packet(
            sessionId = 7,
            seq = 0,
            langId = LanguageId.ENGLISH,
            intent = Intent.DISTRESS,
            payload = Symbols.encodePhraseRef(33),
        )
        val frame = PacketCodec.encode(packet)
        assertEquals(16, frame.size)

        val decoded = (PacketCodec.decode(frame) as DecodeResult.Success).packet
        assertEquals(33, Symbols.decodePhraseRef(decoded.payload, 0))
        assertEquals(Intent.DISTRESS, decoded.intent)
    }

    @Test
    fun theShippedEnglishCodebookHoldsItsContract() {
        val file = listOf(
            File("src/main/assets/languages/en/phrases.txt"),
            File("app/src/main/assets/languages/en/phrases.txt"),
        ).firstOrNull { it.isFile }
        org.junit.Assume.assumeTrue("phrases.txt not found from test working dir", file != null)

        val shipped = PhraseCodebook.load(file!!)
        assertTrue("should hold a usable number of phrases", shipped.size >= 100)
        assertTrue("must fit the 12-bit id", shipped.size <= PhraseCodebook.MAX_ENTRIES)

        // The demo sentence must be in the book, or the headline figure is not reproducible.
        val id = shipped.idOf("Flood water is rising near the school, send boats")
        assertEquals("demo phrase must keep its id", 33, id)

        // No duplicates: a duplicate wastes an id and signals a careless edit to a file
        // whose line order is a wire contract.
        val normalised = (0 until shipped.size).map { PhraseCodebook.normalise(shipped.textOf(it)!!) }
        assertEquals("duplicate phrases", normalised.size, normalised.toSet().size)
    }

    // --- resolution: the bug that nearly shipped --------------------------------------

    @Test
    fun resolveReturnsTheSentenceForAPhraseReference() {
        val packet = refPacket(1)
        assertEquals("we need a doctor", book.resolve(packet) { "UNRESOLVED" })
    }

    @Test
    fun resolveNeverLeaksTheDebugLabelIntoSpeech() {
        // The bug this method exists to prevent: payloadAsText() renders a phrase
        // reference as "<PHRASE> ..." debug labels, so a receiver that used it for
        // synthesis would SPEAK THE WORD "PHRASE" while the screen showed the right
        // sentence. Text and speech must come from the same resolution.
        val packet = refPacket(0)
        assertTrue("payloadAsText is the wrong source", packet.payloadAsText().contains("PHRASE"))

        val spoken = book.resolve(packet) { "" }
        assertEquals("send boats", spoken)
        assertTrue("must never contain the debug label", !spoken.contains("PHRASE"))
    }

    @Test
    fun resolveUsesTheFallbackWhenTheIdIsNotInThisBook() {
        // A sender with a longer codebook. Showing a marker beats inventing a sentence.
        assertEquals("UNRESOLVED 900", book.resolve(refPacket(900)) { "UNRESOLVED $it" })
    }

    @Test
    fun resolveCanFallBackToSilenceForSpeech() {
        // Speech passes an empty fallback: reading a diagnostic aloud to someone in a
        // flood is worse than saying nothing.
        assertEquals("", book.resolve(refPacket(900)) { "" })
    }

    @Test
    fun resolvePassesTextModePacketsStraightThrough() {
        val packet = Packet(
            sessionId = 1, seq = 0, langId = LanguageId.ENGLISH, intent = Intent.ROUTINE,
            payload = "the goat is on the roof".toByteArray(Charsets.UTF_8),
            textMode = true,
        )
        assertEquals("the goat is on the roof", book.resolve(packet) { "UNRESOLVED" })
    }

    @Test
    fun resolveFallsBackToLabelsForANonPhraseSymbolPayload() {
        // A symbol-id payload that is not a phrase reference must still render, not throw.
        val packet = Packet(
            sessionId = 1, seq = 0, langId = LanguageId.ENGLISH, intent = Intent.ROUTINE,
            payload = byteArrayOf(Symbols.WORD_BOUNDARY.toByte(), 120),
            textMode = false,
        )
        val out = book.resolve(packet) { "UNRESOLVED" }
        assertNotEquals("UNRESOLVED", out)
        assertTrue(out.isNotEmpty())
    }

    private fun refPacket(id: Int) = Packet(
        sessionId = 1, seq = 0, langId = LanguageId.ENGLISH, intent = Intent.ROUTINE,
        payload = Symbols.encodePhraseRef(id),
        textMode = false,
    )
}
