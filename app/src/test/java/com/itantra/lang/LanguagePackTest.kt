package com.itantra.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pack loading is the mechanism behind "adding a language is a data task, not an
 * engineering task" (PRD F-42). It is also the part most likely to meet a malformed
 * folder — packs will be assembled by hand, copied over cables, and occasionally
 * truncated mid-transfer.
 *
 * The rule being enforced throughout: a bad pack makes ONE language unavailable. It
 * never throws, and it never takes the app down (TRD section 8). In a distress system,
 * a phone that refuses to start because a folder was half-copied is worse than a phone
 * that quietly offers one fewer language.
 */
class LanguagePackTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun writePack(
        dir: File,
        json: String,
        withAsrFiles: Boolean = true,
        withTtsFiles: Boolean = true,
    ): File {
        dir.mkdirs()
        File(dir, "pack.json").writeText(json)
        if (withAsrFiles) {
            File(dir, "asr").mkdirs()
            listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")
                .forEach { File(dir, "asr/$it").writeText("x") }
        }
        if (withTtsFiles) {
            File(dir, "tts/espeak-ng-data").mkdirs()
            listOf("model.onnx", "tokens.txt").forEach { File(dir, "tts/$it").writeText("x") }
        }
        return dir
    }

    private val validJson = """
        {
          "id": 6, "code": "ta", "name": "Test Language",
          "asr": {
            "encoder": "asr/encoder.onnx", "decoder": "asr/decoder.onnx",
            "joiner": "asr/joiner.onnx", "tokens": "asr/tokens.txt",
            "sampleRate": 16000, "featureDim": 80
          },
          "tts": {
            "model": "tts/model.onnx", "tokens": "tts/tokens.txt",
            "dataDir": "tts/espeak-ng-data", "speakerId": 0, "speed": 1.0
          }
        }
    """.trimIndent()

    // --- The happy path ---

    @Test
    fun `a well formed pack loads with everything wired up`() {
        val dir = writePack(temp.newFolder("ta"), validJson)
        val pack = LanguagePack.load(dir)

        assertNotNull(pack)
        assertEquals(6, pack!!.id)
        assertEquals("ta", pack.code)
        assertEquals("Test Language", pack.displayName)
        assertTrue(pack.asr!!.isComplete)
        assertTrue(pack.tts!!.isComplete)
        assertTrue(pack.isUsable)
        assertEquals(16_000, pack.asr!!.sampleRate)
        assertEquals(80, pack.asr!!.featureDim)
    }

    @Test
    fun `model paths resolve relative to the pack directory`() {
        val dir = writePack(temp.newFolder("ta"), validJson)
        val pack = LanguagePack.load(dir)!!
        // Absolute paths matter: these are handed straight to the native runtime.
        assertTrue(pack.asr!!.encoder!!.absolutePath.startsWith(dir.absolutePath))
        assertTrue(pack.tts!!.model.isFile)
    }

    @Test
    fun `the display name is whatever the pack calls itself`() {
        // Nothing translates or overrides this — a pack in its own script shows up in
        // that script, with no table of names anywhere in the app.
        val json = validJson.replace("\"Test Language\"", "\"\\u0924\\u092e\\u093f\\u0932\"")
        val dir = writePack(temp.newFolder("x"), json)
        assertEquals("तमिल", LanguagePack.load(dir)!!.displayName)
    }

    @Test
    fun `optional numeric fields fall back to sensible defaults`() {
        val json = """
            {
              "id": 1, "code": "hi", "name": "H",
              "asr": {
                "encoder": "asr/encoder.onnx", "decoder": "asr/decoder.onnx",
                "joiner": "asr/joiner.onnx", "tokens": "asr/tokens.txt"
              }
            }
        """.trimIndent()
        val dir = writePack(temp.newFolder("hi"), json, withTtsFiles = false)
        val pack = LanguagePack.load(dir)!!
        assertEquals(16_000, pack.asr!!.sampleRate)
        assertEquals(80, pack.asr!!.featureDim)
    }

    // --- Partial packs ---

    @Test
    fun `a recognition only pack is usable`() {
        val json = validJson.substringBefore(",\n  \"tts\"") + "\n}"
        val dir = writePack(temp.newFolder("asr-only"), json, withTtsFiles = false)
        val pack = LanguagePack.load(dir)
        assertNotNull(pack)
        assertNull(pack!!.tts)
        assertTrue(pack.isUsable)
    }

    @Test
    fun `a pack whose model files are missing is declared incomplete`() {
        val dir = writePack(
            temp.newFolder("empty"), validJson,
            withAsrFiles = false, withTtsFiles = false,
        )
        val pack = LanguagePack.load(dir)
        assertNotNull("descriptor is valid so it should still parse", pack)
        assertTrue(!pack!!.asr!!.isComplete)
        assertTrue(!pack.tts!!.isComplete)
        // Nothing works, so it must not be offered to the user.
        assertTrue(!pack.isUsable)
    }

    @Test
    fun `a half copied pack is not usable`() {
        // Interrupted transfer: the encoder never arrived.
        val dir = writePack(temp.newFolder("partial"), validJson, withTtsFiles = false)
        File(dir, "asr/encoder.onnx").delete()
        val pack = LanguagePack.load(dir)!!
        assertTrue(!pack.asr!!.isComplete)
        assertTrue(!pack.isUsable)
    }

    // --- Malformed input: none of these may throw ---

    @Test
    fun `a directory with no descriptor is skipped`() {
        val dir = temp.newFolder("nothing")
        assertNull(LanguagePack.load(dir))
    }

    @Test
    fun `a directory that does not exist is skipped`() {
        assertNull(LanguagePack.load(File(temp.root, "absent")))
    }

    @Test
    fun `malformed json is skipped rather than thrown`() {
        val dir = temp.newFolder("bad")
        File(dir, "pack.json").writeText("{ this is not json")
        assertNull(LanguagePack.load(dir))
    }

    @Test
    fun `an empty descriptor is skipped`() {
        val dir = temp.newFolder("empty-json")
        File(dir, "pack.json").writeText("")
        assertNull(LanguagePack.load(dir))
    }

    @Test
    fun `a descriptor missing required fields is skipped`() {
        for (missing in listOf("id", "code", "name")) {
            val dir = temp.newFolder("missing-$missing")
            val json = when (missing) {
                "id" -> """{ "code": "x", "name": "X" }"""
                "code" -> """{ "id": 1, "name": "X" }"""
                else -> """{ "id": 1, "code": "x" }"""
            }
            File(dir, "pack.json").writeText(json)
            assertNull("missing $missing should be skipped", LanguagePack.load(dir))
        }
    }

    @Test
    fun `an asr block missing a model path is skipped`() {
        val dir = temp.newFolder("bad-asr")
        File(dir, "pack.json").writeText(
            """{ "id": 1, "code": "x", "name": "X", "asr": { "encoder": "a.onnx" } }"""
        )
        assertNull(LanguagePack.load(dir))
    }

    @Test
    fun `a descriptor that is a json array is skipped`() {
        val dir = temp.newFolder("array")
        File(dir, "pack.json").writeText("""[1,2,3]""")
        assertNull(LanguagePack.load(dir))
    }

    @Test
    fun `binary rubbish in the descriptor is skipped`() {
        val dir = temp.newFolder("binary")
        File(dir, "pack.json").writeBytes(ByteArray(512) { (it * 37).toByte() })
        assertNull(LanguagePack.load(dir))
    }

    @Test
    fun `a pack may declare any language id including one this build predates`() {
        // Forward compatibility, matching the decoder: a newer pack numbering scheme
        // must not make the pack unloadable.
        val json = validJson.replace("\"id\": 6", "\"id\": 42")
        val dir = writePack(temp.newFolder("future"), json)
        assertEquals(42, LanguagePack.load(dir)!!.id)
    }

    @Test
    fun `size is reported from what is actually on disk`() {
        val dir = writePack(temp.newFolder("sized"), validJson)
        File(dir, "asr/encoder.onnx").writeBytes(ByteArray(2 * 1024 * 1024))
        val pack = LanguagePack.load(dir)!!
        assertTrue("was ${pack.sizeMb} MB", pack.sizeMb >= 2.0)
    }

    // --- recogniser families have different shapes -------------------------------------

    @Test
    fun `a single-graph nemo pack loads without encoder or decoder`() {
        // These fields were once required, so a NeMo pack failed to parse and the
        // language vanished from the picker with no error reported anywhere.
        val dir = temp.newFolder("nemo")
        File(dir, "model.int8.onnx").writeText("x")
        File(dir, "tokens.txt").writeText("<unk> 0")
        writePack(dir, """
            {
              "id": 6, "code": "ta", "name": "Tamil",
              "asr": { "type": "nemo_ctc", "model": "model.int8.onnx", "tokens": "tokens.txt" }
            }
        """.trimIndent())

        val pack = LanguagePack.load(dir)!!
        assertEquals(LanguagePack.AsrModel.TYPE_NEMO_CTC, pack.asr!!.type)
        assertTrue("a single-graph pack is complete without encoder/decoder", pack.asr!!.isComplete)
        assertTrue(pack.isUsable)
    }

    @Test
    fun `a nemo pack with no model file is incomplete`() {
        val dir = temp.newFolder("nemo-broken")
        File(dir, "tokens.txt").writeText("<unk> 0")
        writePack(dir, """
            {
              "id": 6, "code": "ta", "name": "Tamil",
              "asr": { "type": "nemo_ctc", "tokens": "tokens.txt" }
            }
        """.trimIndent())
        assertTrue(LanguagePack.load(dir)?.asr?.isComplete != true)
    }

    @Test
    fun `a transducer is incomplete without its joiner`() {
        // Family-aware completeness: the joiner is required here and meaningless for
        // nemo_ctc, so one shared rule would be wrong for one of them.
        val dir = temp.newFolder("transducer")
        File(dir, "e.onnx").writeText("x")
        File(dir, "d.onnx").writeText("x")
        File(dir, "tokens.txt").writeText("a 0")
        writePack(dir, """
            {
              "id": 0, "code": "en", "name": "English",
              "asr": { "type": "zipformer", "encoder": "e.onnx",
                       "decoder": "d.onnx", "tokens": "tokens.txt" }
            }
        """.trimIndent())
        assertTrue(LanguagePack.load(dir)?.asr?.isComplete != true)
    }
}
