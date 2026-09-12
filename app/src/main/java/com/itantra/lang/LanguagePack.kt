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
        /**
         * Split-graph families only (transducer, Whisper). Null for a single-graph model.
         *
         * Nullable because recogniser families genuinely differ in shape, and forcing one
         * shape on all of them is how a pack stops loading: these were once required, so a
         * NeMo pack failed to parse and the language silently disappeared from the picker
         * with no error anywhere.
         */
        val encoder: File?,
        val decoder: File?,
        /** Transducer models only. Whisper and NeMo CTC have no joiner. */
        val joiner: File?,
        /** Single-graph families ([TYPE_NEMO_CTC]). Null for the split-graph ones. */
        val model: File?,
        val tokens: File,
        val sampleRate: Int,
        val featureDim: Int,
        /** Whisper only: which language to decode. Empty means "let it guess". */
        val language: String?,
    ) {
        /**
         * Whether the files this family actually needs are all present.
         *
         * Family-aware on purpose: a transducer without a joiner is broken, while a NeMo
         * CTC model without one is normal. One shared rule would either reject good packs
         * or accept broken ones.
         */
        val isComplete: Boolean
            get() = tokens.isFile && when (type) {
                TYPE_NEMO_CTC -> model?.isFile == true
                TYPE_TRANSDUCER ->
                    encoder?.isFile == true && decoder?.isFile == true && joiner?.isFile == true
                else -> encoder?.isFile == true && decoder?.isFile == true
            }

        companion object {
            const val TYPE_TRANSDUCER = "zipformer"
            const val TYPE_WHISPER = "whisper"

            /**
             * AI4Bharat IndicConformer and friends: one CTC graph, one vocabulary.
             *
             * Worth having as its own family rather than bent into the Whisper path — it
             * is a far better recogniser for Indian languages than multilingual Whisper.
             * On the Tamil self-test sentence Whisper heard "விளம் வியருகிறது ப"; this
             * family transcribes it exactly.
             */
            const val TYPE_NEMO_CTC = "nemo_ctc"
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
        /**
         * Inference threads for synthesis.
         *
         * Declared by the pack because the right number depends on the model, not on the
         * app: a small Piper voice saturates at two, while the Tamil VITS graph is heavy
         * enough to keep four or more busy.
         *
         * This field existed in `pack.json` for weeks and was **never read** — TtsModel
         * had no such property, so the engine silently used its own default of 2 on an
         * eight-core phone. Tamil synthesis took 12.2 s to produce 2.8 s of audio, and the
         * declared setting that should have fixed it did nothing.
         */
        val numThreads: Int,
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
                        // All optional: which of these a pack supplies depends on its
                        // recogniser family, and isComplete checks the right ones.
                        encoder = a.optString("encoder").takeIf { it.isNotEmpty() }
                            ?.let { File(dir, it) },
                        decoder = a.optString("decoder").takeIf { it.isNotEmpty() }
                            ?.let { File(dir, it) },
                        joiner = a.optString("joiner").takeIf { it.isNotEmpty() }
                            ?.let { File(dir, it) },
                        model = a.optString("model").takeIf { it.isNotEmpty() }
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
                        // Default 2 to match the old hardcoded behaviour, so a pack that
                        // says nothing behaves exactly as before. Clamped: a pack asking
                        // for more threads than the phone has cores makes it slower, not
                        // faster, through contention.
                        numThreads = t.optInt("numThreads", 2)
                            .coerceIn(1, Runtime.getRuntime().availableProcessors()),
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
