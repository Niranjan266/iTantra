package com.itantra.asr

import android.util.Log
import com.itantra.lang.LanguagePack
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream

/**
 * AI4Bharat IndicConformer via sherpa-onnx — a CTC recogniser trained per language.
 *
 * ## Why this replaced Whisper for Indian languages
 *
 * Whisper is multilingual and includes Tamil, which made it the only way to have Tamil at
 * all for a while. It is not good at it. Measured on the same synthesised Tamil sentence,
 * same audio file, same device class:
 *
 * | Recogniser | Heard |
 * |---|---|
 * | Whisper base (multilingual) | `விளம் வியருகிறது ப` |
 * | **IndicConformer (this)** | **`வெள்ளம் உயர்கிறது படகுகளை அனுப்புங்கள்`** |
 *
 * The reference was the second of those, exactly — 0% word error rate against roughly
 * total failure. A model trained on one language beats a model trained on ninety-nine,
 * which is unsurprising and worth having measured rather than assumed.
 *
 * It is also **Apache-2.0**, unlike the CC-BY-NC Tamil voice, so this half of the Tamil
 * pipeline carries no non-commercial restriction.
 *
 * ## Shape
 *
 * One graph and one vocabulary — no encoder/decoder/joiner split. The vocabulary is
 * **shared across all ten AI4Bharat Indic languages**, so a second Indic language costs
 * only its own model file and can reuse the same 66 KB `tokens.txt`.
 *
 * Note those tokens are wordpieces and are legitimately multi-character (`<unk>` included),
 * which is why [com.itantra.lang.TokensFile]'s single-character rule is scoped to the TTS
 * character frontend and not applied to recognisers. Applied universally it would reject
 * this model.
 *
 * ## What it costs
 *
 * Offline, like Whisper: no partial results, and recognition begins at end-of-utterance,
 * so partial-prefix commit (PRD F-05) does not apply and latency is worse than the
 * streaming English path. [isStreaming] is false and the UI reports it. The accuracy is
 * worth it; pretending the latency is unaffected would not be.
 */
class NemoCtcAsrEngine(
    private val pack: LanguagePack,
    private val numThreads: Int = 2,
) : AsrEngine {

    companion object {
        private const val TAG = "iTantra.Nemo"
    }

    override val name: String get() = "IndicConformer (${pack.code})"

    /** Offline: the whole utterance is decoded at once. */
    override val isStreaming: Boolean get() = false

    private var recognizer: OfflineRecognizer? = null
    private var stream: OfflineStream? = null

    /** Accumulated samples. Guarded by the same lock as everything else here. */
    private val buffered = ArrayList<Short>()

    var lastError: String? = null
        private set

    val isLoaded: Boolean get() = recognizer != null

    var framesAccepted: Int = 0
        private set

    @Synchronized
    override fun load(): Boolean {
        if (recognizer != null) return true

        val model = pack.asr
        if (model == null || !model.isComplete) {
            lastError = "Language pack '${pack.code}' has no usable recognition model"
            return false
        }
        val graph = model.model
        if (graph == null) {
            lastError = "Pack '${pack.code}' declares nemo_ctc but supplies no model file"
            return false
        }

        return runCatching {
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(
                    sampleRate = model.sampleRate,
                    featureDim = model.featureDim,
                ),
                modelConfig = OfflineModelConfig(
                    nemo = OfflineNemoEncDecCtcModelConfig(model = graph.absolutePath),
                    tokens = model.tokens.absolutePath,
                    numThreads = numThreads,
                    modelType = "nemo_ctc",
                ),
            )
            recognizer = OfflineRecognizer(config = config)
            lastError = null
            Log.i(TAG, "loaded IndicConformer for ${pack.code}")
            true
        }.getOrElse {
            lastError = "Could not load the recognition model: ${it.message}"
            Log.e(TAG, "load failed", it)
            false
        }
    }

    @Synchronized
    override fun start() {
        framesAccepted = 0
        buffered.clear()
        runCatching {
            stream?.release()
            stream = recognizer?.createStream()
        }.onFailure { Log.w(TAG, "could not create stream", it) }
    }

    @Synchronized
    override fun accept(samples: ShortArray) {
        // An offline model cannot be fed incrementally: it wants the whole utterance in
        // one call. So this only collects, and the work happens in finish().
        framesAccepted++
        buffered.ensureCapacity(buffered.size + samples.size)
        for (s in samples) buffered.add(s)
    }

    /** Always empty: there is nothing to show before the utterance ends. */
    @Synchronized
    override fun partial(): String = ""

    @Synchronized
    override fun finish(): String {
        val r = recognizer ?: return ""
        val s = stream ?: return ""
        if (buffered.isEmpty()) {
            Log.w(TAG, "finish with no audio — start() missing?")
            return ""
        }

        return runCatching {
            val floats = FloatArray(buffered.size) { buffered[it] / 32768.0f }
            s.acceptWaveform(floats, pack.asr?.sampleRate ?: 16_000)
            r.decode(s)
            val text = r.getResult(s).text.trim()
            Log.i(TAG, "finish: ${buffered.size} samples, result=\"$text\"")
            s.release()
            stream = null
            buffered.clear()
            text
        }.getOrElse {
            Log.w(TAG, "finish failed", it)
            ""
        }
    }

    @Synchronized
    override fun release() {
        runCatching { stream?.release() }
        runCatching { recognizer?.release() }
        stream = null
        recognizer = null
        buffered.clear()
    }
}
