package com.itantra.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Telugu to Hindi, Marathi to Malayalam, and every other pair of the ten.
 *
 * Tested against the **real codebooks that ship in the APK**. The claim rests entirely on
 * those ten files agreeing line for line, and nothing but reading all of them can show
 * that: a fixture would have passed while Hindi had no codebook at all and Tamil had 32
 * lines of 144, which is exactly how that went unnoticed.
 */
class EveryLanguagePairTest {

    private val codes = listOf("en", "hi", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn")

    private val books: Map<String, PhraseCodebook> = codes.associateWith { code ->
        // Unit tests run with the module directory as the working directory.
        val f = File("src/main/assets/codebooks/$code.txt")
        assertTrue("missing codebook: ${f.absolutePath}", f.isFile)
        PhraseCodebook.load(f)
    }

    private fun idOf(code: String) = LanguageId.idOfIsoCode(code)!!

    /** What a [to] listener hears when a [from] speaker says [said]. */
    private fun heard(from: String, to: String, said: String): Translator.Delivery {
        val phraseId = books.getValue(from).closestId(said)
        assertNotNull("the $from codebook did not recognise \"$said\"", phraseId)
        val packet = Packet(
            sessionId = 1,
            seq = 1,
            langId = idOf(from),
            intent = Intent.ROUTINE,
            payload = Symbols.encodePhraseRef(phraseId!!),
            textMode = false,
        )
        return Translator.deliver(packet, books.getValue(to), idOf(to), books.getValue(from))
    }

    @Test
    fun allTenLanguagesHaveAComplete144LineCodebook() {
        for ((code, book) in books) {
            assertEquals("$code line count", 144, book.size)
            assertEquals("$code translated count", 144, book.translatedCount)
        }
    }

    @Test
    fun theTenCodebooksAreOneParallelCorpus() {
        // Every language must hold a wording for every id, and they must all be the
        // same length — otherwise an id sent by one phone means nothing, or something
        // else, on another.
        val fingerprints = books.mapValues { (_, b) -> b.size }
        assertEquals(1, fingerprints.values.toSet().size)
        for (id in 0 until 144) {
            for ((code, book) in books) {
                assertNotNull("$code has no wording for id $id", book.textOf(id))
            }
        }
    }

    @Test
    fun everyPhraseCrossesEveryPairOfLanguages() {
        // 10 x 9 directions x 144 phrases. One misaligned line in one file would turn a
        // distress message into a different one, in one direction only.
        var checked = 0
        for (from in codes) for (to in codes) {
            if (from == to) continue
            for (id in 0 until 144) {
                val said = books.getValue(from).textOf(id)!!
                val d = heard(from, to, said)
                assertEquals("$from -> $to, id $id", books.getValue(to).textOf(id), d.translated)
                assertEquals("$from -> $to, id $id", Translator.Kind.TRANSLATED, d.kind)
                checked++
            }
        }
        assertEquals(10 * 9 * 144, checked)
    }

    @Test
    fun theExamplesFromTheRequirement() {
        // Telugu to Hindi, Marathi to Malayalam — named by the person who asked for this.
        assertEquals("हमें तुरंत मदद चाहिए",
            heard("te", "hi", "మాకు వెంటనే సహాయం కావాలి").translated)
        assertEquals("ഡോക്ടർ വേണം",
            heard("mr", "ml", "डॉक्टर हवे").translated)
        assertEquals("ನಾವು ಸಿಕ್ಕಿಕೊಂಡಿದ್ದೇವೆ, ಚಲಿಸಲು ಆಗುತ್ತಿಲ್ಲ",
            heard("bn", "kn", "আমরা আটকে পড়েছি, নড়তে পারছি না").translated)
        assertEquals("પૂરનું પાણી વધી રહ્યું છે",
            heard("or", "gu", "ବଣ୍ୟା ପାଣି ବଢ଼ୁଛି").translated)
        assertEquals("படகுகளை அனுப்புங்கள்",
            heard("te", "ta", "పడవలు పంపండి").translated)
    }

    @Test
    fun aPhraseStillCostsSixteenBytesInEveryLanguage() {
        for (code in codes) {
            val packet = Packet(
                sessionId = 1, seq = 1, langId = idOf(code), intent = Intent.ROUTINE,
                payload = Symbols.encodePhraseRef(books.getValue(code).closestId(
                    books.getValue(code).textOf(80)!!)!!),
                textMode = false,
            )
            // 11 header + 3 phrase reference + 2 checksum, whatever the script.
            assertEquals(code, 16, PacketCodec.encode(packet).size)
        }
    }

    @Test
    fun noCodebookRepeatsAWording() {
        // A duplicated line is unreachable: the lower id always wins the lookup, so the
        // higher one could never be sent, and the two would translate differently.
        for ((code, book) in books) {
            val seen = mutableMapOf<String, Int>()
            for (id in 0 until 144) {
                val text = PhraseCodebook.normalise(book.textOf(id)!!)
                val first = seen.put(text, id)
                assertNull("$code: ids $first and $id are the same wording", first)
            }
        }
    }

    @Test
    fun everyLanguageRefusesSpeechThatIsNotInTheBook() {
        val nonsense = mapOf(
            "en" to "the quick brown fox jumps over the lazy dog",
            "hi" to "मेरा नाम निरंजन है",
            "ta" to "நாளை காலை சந்திப்போம்",
            "te" to "నా పేరు నిరంజన్",
            "ml" to "നാളെ രാവിലെ കാണാം",
            "kn" to "ನನ್ನ ಹೆಸರು ನಿರಂಜನ್",
            "mr" to "माझे नाव निरंजन आहे",
            "gu" to "મારું નામ નિરંજન છે",
            "bn" to "আমার নাম নিরঞ্জন",
            "or" to "ମୋ ନାମ ନିରଞ୍ଜନ",
        )
        for ((code, said) in nonsense) {
            assertNull("$code forced \"$said\" onto a phrase", books.getValue(code).closestId(said))
        }
    }

    @Test
    fun negationsStayApartInEveryLanguage() {
        // 79 "no medical help needed" and 95 "we do not need rescue" against 64 "we need
        // a doctor" and 82 "send a rescue team": dropping the negation would turn a
        // stand-down into a call for help.
        for (code in codes) {
            val book = books.getValue(code)
            for (id in listOf(64, 79, 82, 95)) {
                val said = book.textOf(id)!!
                assertEquals("$code id $id", id, book.closestId(said))
            }
        }
    }
}
