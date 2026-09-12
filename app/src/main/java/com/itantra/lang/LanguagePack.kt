package com.itantra.lang

import com.itantra.codec.PhraseCodebook
import org.json.JSONObject
import java.io.File

/**
 * One installed language, described entirely by data (TRD section 7, PRD F-40 … F-42).
 *
 * Nothing in this class is specific to English, Hindi or any other language, and no
 * model filename appears anywhere in Kotlin source. A pack is a directory plus a
 * `pack.json` that names its own files, so adding a language is a file-copy operation
 * and never a code change. The acceptance test for that is PRD section 10.5: a teammate
 * adds a language without opening a `.kt` file.
 */
data class LanguagePack(
    val id: Int,
    val code: String,
    val displayName: String,
    val root: File,
    val asr: AsrModel?,
    val tts: TtsModel?,
    /**
     * A sentence in this language for the built-in speech self-test.
     *
     * Carried by the pack so the test works for any language without the app knowing
     * a word of it — the same rule as everything else here.
     */
    val selfTestPhrase: String?,
) {
    /**
     * This language's phrase codebook, or an empty one if the pack ships none.
     *
     * Deliberately not a constructor parameter: it is derived from [root] by a fixed
     * filename, exactly like the models are derived from `pack.json`, and a pack without
     * one must still load. Lazy because reading it costs a file read that a pack the user
     * never selects should not pay.
     *
     * The phrase list is per language by necessity — a Tamil id must index Tamil
     * sentences — and so it belongs here beside the models rather than in the app.
     */
    val phrases: PhraseCodebook by lazy {
        PhraseCodebook.load(File(root, PhraseCodebook.PACK_FILE))
    }

    data class AsrModel(
        /**
         * Which recogniser family this is: "zipformer" (streaming transducer) or
         * "whisper" (offline, multilingual). The pack declares it, so adding a
         * language that needs a different architecture is still a data change —
         * the engine is chosen from this string, never from the language code.
         */
        val type: String,
        val encoder: File,
        val decoder: File,
        /** Transducer models only. Whisper has no joiner. */
        val joiner: File?,
        val tokens: File,
        val sampleRate: Int,
        val featureDim: Int,
        /** Whisper only: which language to decode. Empty means "let it guess". */
        val language: String?,
    ) {
        val isComplete: Boolean
            get() = encoder.isFile && decoder.isFile && tokens.isFile &&
                (joiner?.isFile ?: (type != TYPE_TRANSDUCER))

        companion object {
            const val TYPE_TRANSDUCER = "zipformer"
            const val TYPE_WHISPER = "whisper"
        }
    }

    data class TtsModel(
        val model: File,
        val tokens: File,
        /**
         * espeak-ng phoneme data, for voices that use it (Piper).
         *
         * Null for models that carry their own vocabulary — MMS voices are trained on
         * raw characters and need no pronunciation dictionary at all. Requiring this
         * unconditionally is what made the Tamil pack report "no usable voice model"
         * despite being complete.
         */
        val dataDir: File?,
        val speakerId: Int,
        val speed: Float,
    ) {
        val isComplete: Boolean
            get() = model.isFile && tokens.isFile &&
                (dataDir?.isDirectory ?: true)
    }

    /** A pack with neither engine is useless and is hidden rather than offered. */
    val isUsable: Boolean
        get() = (asr?.isComplete == true) || (tts?.isComplete == true)

    val sizeMb: Double
        get() = root.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1024.0 / 1024.0

    companion object {
        const val PACK_FILE = "pack.json"

        /**
         * Read a pack from its directory.
         *
         * Returns null rather than throwing on anything malformed. A corrupt pack must
         * make that one language unavailable, never crash the app (TRD section 8).
         */
        fun load(dir: File): LanguagePack? {
            val descriptor = File(dir, PACK_FILE)
            if (!descriptor.isFile) return null

            return runCatching {
                val json = JSONObject(descriptor.readText())

                val asr = json.optJSONObject("asr")?.let { a ->
                    AsrModel(
                        type = a.optString("type", AsrModel.TYPE_TRANSDUCER),
                        encoder = File(dir, a.getString("encoder")),
                        decoder = File(dir, a.getString("decoder")),
                        joiner = a.optString("joiner").takeIf { it.isNotEmpty() }
                            ?.let { File(dir, it) },
                        tokens = File(dir, a.getString("tokens")),
                        sampleRate = a.optInt("sampleRate", 16_000),
                        featureDim = a.optInt("featureDim", 80),
                        language = a.optString("language").takeIf { it.isNotEmpty() },
                    )
                }

                val tts = json.optJSONObject("tts")?.let { t ->
                    TtsModel(
                        model = File(dir, t.getString("model")),
                        tokens = File(dir, t.getString("tokens")),
                        dataDir = t.optString("dataDir").takeIf { it.isNotEmpty() }
                            ?.let { File(dir, it) },
                        speakerId = t.optInt("speakerId", 0),
                        speed = t.optDouble("speed", 1.0).toFloat(),
                    )
                }

                LanguagePack(
                    id = json.getInt("id"),
                    code = json.getString("code"),
                    displayName = json.getString("name"),
                    root = dir,
                    asr = asr,
                    tts = tts,
                    selfTestPhrase = json.optString("selfTest").takeIf { it.isNotEmpty() },
                )
            }.getOrNull()
        }
    }
}
