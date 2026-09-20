package com.itantra.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * One person speaks Hindi; the person listening hears Tamil.
 *
 * This is the headline claim, tested against the **real codebooks that ship in the APK**
 * rather than fixtures, because the claim depends entirely on those files agreeing line
 * for line. It failed for a long time in a way no fixture would have caught: Hindi had no
 * codebook at all, and Tamil had 32 of 144 lines.
 */
class HindiToTamilTest {

    private fun book(code: String): PhraseCodebook {
        // Unit tests run with the module directory as the working directory.
        val f = File("src/main/assets/codebooks/$code.txt")
        assertTrue("missing codebook: ${f.absolutePath}", f.isFile)
        return PhraseCodebook.load(f)
    }

    private val en = book("en")
    private val hi = book("hi")
    private val ta = book("ta")

    /** What a Tamil listener hears when a Hindi speaker says [said]. */
    private fun hindiToTamil(said: String): Translator.Delivery {
        val id = hi.closestId(said)
        assertNotNull("Hindi codebook did not recognise \"$said\"", id)
        val packet = Packet(
            sessionId = 1,
            seq = 1,
            langId = LanguageId.HINDI,
            intent = Intent.ROUTINE,
            payload = Symbols.encodePhraseRef(id!!),
            textMode = false,
        )
        return Translator.deliver(packet, ta, LanguageId.TAMIL, hi)
    }

    @Test
    fun theThreeCodebooksAreTheSameLength() {
        assertEquals(144, en.size)
        assertEquals(144, hi.size)
        assertEquals(144, ta.size)
    }

    @Test
    fun hindiAndTamilHaveEveryLineTranslated() {
        assertEquals("Hindi", 144, hi.translatedCount)
        assertEquals("Tamil", 144, ta.translatedCount)
    }

    @Test
    fun spokenHindiIsHeardInTamil() {
        val cases = mapOf(
            "हमें तुरंत मदद चाहिए" to "உடனே உதவி தேவை",
            "डॉक्टर चाहिए" to "மருத்துவர் தேவை",
            "नावें भेजिए" to "படகுகளை அனுப்புங்கள்",
            "पीने का पानी चाहिए" to "குடிநீர் தேவை",
            "बिजली नहीं है" to "மின்சாரம் இல்லை",
            "यह आपातकाल है" to "இது அவசர நிலை",
            "एक बच्चा लापता है" to "ஒரு குழந்தை காணவில்லை",
        )
        for ((said, expected) in cases) {
            val d = hindiToTamil(said)
            assertEquals("\"$said\"", expected, d.translated)
            assertEquals(Translator.Kind.TRANSLATED, d.kind)
            // The sender's own words travel too, for a bilingual reader to check against.
            assertEquals(said, d.original)
            assertEquals(LanguageId.HINDI, d.fromLangId)
            assertEquals(LanguageId.TAMIL, d.toLangId)
        }
    }

    @Test
    fun everyPhraseCrossesFromHindiToTamil() {
        // All 144, not a sample: a single unaligned line would translate one distress
        // message into a different one.
        for (id in 0 until 144) {
            val said = hi.textOf(id)!!
            val d = hindiToTamil(said)
            assertEquals("id $id", ta.textOf(id), d.translated)
        }
    }

    @Test
    fun aPhraseCostsSixteenBytesWhicheverLanguageSpeaksIt() {
        val packet = Packet(
            sessionId = 1, seq = 1, langId = LanguageId.HINDI, intent = Intent.ROUTINE,
            payload = Symbols.encodePhraseRef(hi.closestId("नावें भेजिए")!!),
            textMode = false,
        )
        // 11 header + 3 phrase reference + 2 checksum. The translation is free.
        assertEquals(16, PacketCodec.encode(packet).size)
    }

    @Test
    fun anExtraWordStillTranslates() {
        // What a person actually says, and what a recogniser actually returns.
        assertEquals("मुझे डॉक्टर चाहिए", "மருத்துவர் தேவை", hindiToTamil("मुझे डॉक्टर चाहिए").translated)
        assertEquals("जल्दी नावें भेजिए", "படகுகளை அனுப்புங்கள்", hindiToTamil("जल्दी नावें भेजिए").translated)
        assertEquals("यहाँ बिजली नहीं है", "மின்சாரம் இல்லை", hindiToTamil("यहाँ बिजली नहीं है").translated)
    }

    @Test
    fun aNegationIsNeverMatchedToItsOpposite() {
        // "we do not need rescue" (95) against "we need a doctor" and the rest: a dropped
        // negation would turn a stand-down into a call for help.
        assertEquals(95, en.closestId("we do not need rescue"))
        assertEquals(79, en.closestId("no medical help needed"))
        assertEquals(64, en.closestId("we need a doctor"))
        // Tamil's two opposites must stay apart as well.
        assertEquals(ta.textOf(95), ta.textOf(ta.closestId("மீட்பு தேவையில்லை")!!))
    }

    @Test
    fun unrelatedSpeechIsNotForcedIntoTheCodebook() {
        for (said in listOf(
            "मेरा नाम निरंजन है",
            "कल बाज़ार से सब्ज़ी लेकर आना",
            "the quick brown fox jumps over the lazy dog",
            "आज मौसम कैसा है",
        )) {
            assertNull("\"$said\" must travel as itself", hi.closestId(said) ?: en.closestId(said))
        }
    }

    @Test
    fun twoPhrasesThatDifferByOneWordBothLoseRatherThanOneWinning() {
        // "people are gathering at the school" vs "...at the temple": if someone says a
        // place that is in neither, guessing between them would put people in the wrong
        // building.
        assertNull(en.closestId("people are gathering at the mosque"))
    }
}
