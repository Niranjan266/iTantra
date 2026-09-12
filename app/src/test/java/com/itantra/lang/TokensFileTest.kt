package com.itantra.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for the tokens-file check.
 *
 * The case that matters most is [rejectsMultiCharacterSymbol]: that exact line shipped
 * in a real Tamil pack and killed the process in native code with no message. These
 * tests exist so the validator that now catches it cannot be weakened by accident.
 */
class TokensFileTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun tokens(vararg lines: String): File =
        temp.newFile("tokens-${counter++}.txt").apply {
            writeText(lines.joinToString("\n"))
        }

    private var counter = 0

    // --- the real bug ---------------------------------------------------------------

    @Test
    fun rejectsMultiCharacterSymbol() {
        // Verbatim from the pack that aborted the app: an MMS vocabulary's unknown
        // token, written out literally by the exporter.
        val file = tokens("அ 1", "ஈ 2", "<unk> 58")
        val result = TokensFile.validate(file, requireSingleCharacter = true)

        assertFalse(result.isValid)
        assertTrue("should name the line number", result.error!!.contains("line 3"))
        assertTrue("should quote the offending symbol", result.error!!.contains("<unk>"))
    }

    @Test
    fun allowsMultiCharacterSymbolForPhonemeVoices() {
        // Piper voices are read by a different sherpa frontend. Rejecting a pack that
        // actually works would be a worse bug than the one this class prevents.
        val file = tokens("a 1", "tʃ 2")
        assertTrue(TokensFile.validate(file, requireSingleCharacter = false).isValid)
    }

    // --- the happy path, including the two shapes that look wrong but are not --------

    @Test
    fun acceptsAWellFormedFile() {
        val result = TokensFile.validate(tokens("a 0", "b 1", "c 2"), true)
        assertTrue(result.error, result.isValid)
        assertEquals(3, result.symbols)
    }

    @Test
    fun acceptsALiteralSpaceAsASymbol() {
        // MMS and Piper vocabularies both contain a space. Splitting on the FIRST space
        // instead of the last would silently drop it and shift nothing — the failure
        // would show up only as speech with no word gaps.
        val result = TokensFile.validate(tokens("a 0", "  7", "b 1"), true)
        assertTrue(result.error, result.isValid)
        assertEquals(3, result.symbols)
    }

    @Test
    fun acceptsDuplicateIds() {
        // Deliberate in MMS: upper and lower case map to one token.
        assertTrue(TokensFile.validate(tokens("k 0", "K 0", "z 1"), true).isValid)
    }

    @Test
    fun acceptsATrailingBlankLine() {
        assertTrue(TokensFile.validate(tokens("a 0", "b 1", ""), true).isValid)
    }

    @Test
    fun toleratesCrlfLineEndings() {
        // Our exporter writes LF, but a file edited on Windows should not cost a user
        // their language when the carriage return is trivially strippable.
        val file = temp.newFile("crlf.txt").apply { writeText("a 0\r\nb 1\r\n") }
        val result = TokensFile.validate(file, true)
        assertTrue(result.error, result.isValid)
        assertEquals(2, result.symbols)
    }

    @Test
    fun acceptsAMultiBytePrivateUseSymbol() {
        // A single character outside the BMP is two Java chars but one code point.
        // Counting chars rather than code points would reject it wrongly.
        val file = tokens("a 0", "😀 1")
        assertTrue(TokensFile.validate(file, requireSingleCharacter = true).isValid)
    }

    // --- malformed input ------------------------------------------------------------

    @Test
    fun rejectsANonNumericId() {
        val result = TokensFile.validate(tokens("a 0", "b two"), true)
        assertFalse(result.isValid)
        assertTrue(result.error!!.contains("line 2"))
    }

    @Test
    fun rejectsALineWithNoId() {
        val result = TokensFile.validate(tokens("a 0", "b"), true)
        assertFalse(result.isValid)
        assertTrue(result.error!!.contains("line 2"))
    }

    @Test
    fun aLoneCarriageReturnSeparatesLinesRatherThanCorruptingAnId() {
        // Worth pinning because it is the difference between this reader and sherpa's.
        // Kotlin's readLines treats a lone CR as a line terminator, so a classic-Mac
        // file parses as separate lines and no id ever carries a stray CR. sherpa's C++
        // reader splits on \n only, which is why the exporter must still write LF —
        // that hazard lives in tools/export-mms-tts.py, not here.
        val file = temp.newFile("lone-cr.txt").apply { writeText("a 0\rb 1") }
        val result = TokensFile.validate(file, true)
        assertTrue(result.error, result.isValid)
        assertEquals(2, result.symbols)
    }

    @Test
    fun rejectsAnEmptyFile() {
        val result = TokensFile.validate(tokens(""), true)
        assertFalse(result.isValid)
        assertEquals(0, result.symbols)
    }

    @Test
    fun rejectsAMissingFile() {
        val missing = File(temp.root, "not-there.txt")
        val result = TokensFile.validate(missing, true)
        assertFalse(result.isValid)
        assertTrue(result.error!!.contains("missing"))
    }

    @Test
    fun errorNamesTheFileSoTheUserCanFindIt() {
        val file = tokens("<unk> 58")
        val error = TokensFile.validate(file, true).error!!
        assertTrue(error.contains(file.name))
    }
}
