package com.itantra.asr

import android.util.Log
import com.itantra.lang.LanguagePack
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig

/**
 * Whisper recognition, for languages with no streaming model available.
 *
 * ## Why this exists
 *
 * The streaming Zipformer path covers English. For Tamil there is, at the time of
 * writing, **no ready-made streaming model in any open catalogue** — AI4Bharat publish
 * a Tamil IndicConformer only as a NeMo checkpoint, and the community ONNX conversions
 * of that family cover Assamese, Bengali, Bodo, Gujarati, Hindi, Kannada, Kashmiri and
 * Marathi, but not Tamil.
 *
 * Whisper is multilingual, includes Tamil, and ships in sherpa-onnx format already. It
 * is the difference between having Tamil and not having it.
 *
 * ## What it costs, stated plainly
 *
 * Whisper is **not streaming**. It cannot emit partial results, and it only runs once
 * the utterance is complete. So for a Whisper language:
 *
 *  - [partial] always returns empty — there is nothing to show mid-sentence
 *  - partial-prefix commit (PRD F-05) does not apply, so the far end cannot begin
 *    speaking before the sender finishes
 *  - latency is higher, because recognition starts at end-of-utterance rather than
 *    running alongside the speech
 *
 * That is a real regression against the latency metric for those languages, and it is
 * reported rather than hidden: [isStreaming] is false and the UI says so. A streaming
 * Tamil model would be a straight upgrade, and because this sits behind [AsrEngine] it
 * would be a one-line swap.
 */
class WhisperAsrEngine(
    private val pack: LanguagePack,
    private val numThreads: Int = 2,
) : AsrEngine {

    companion object {
        private const val TAG = "iTantra.Whisper"

        /**
         * Whisper expects 30-second windows and pads internally. Extra tail padding
         * stops the last word being cut, the same problem the streaming engine has.
         */
        private const val TAIL_PADDINGS = 2000
    }

    override val name: String get() = "Whisper (${pack.code})"

    /** False: no partial results, and recognition begins only at end of utterance. */
    override val isStreaming: Boolean get() = false

    private var recognizer: OfflineRecognizer? = null

    /**
     * Whisper runs on the whole utterance at once, so the audio is buffered rather
     * than fed incrementally. A minute of speech is under 2 MB at 16 kHz, which is
     * affordable; a runaway recording is capped in [accept].
     */
    // Guarded by @Synchronized on every method that touches it: the capture loop and
    // the finishing coroutine run concurrently, and an ArrayList resized from two
    // threads corrupts silently.
    private val buffered = ArrayList<Short>(16_000 * 10)

    var lastError: String? = null
        private set

    val isLoaded: Boolean get() = recognizer != null

    private val sampleRate: Int get() = pack.asr?.sampleRate ?: 16_000

    override fun load(): Boolean {
        if (recognizer != null) return true

        val model = pack.asr
        if (model == null || !model.isComplete) {
            lastError = "Language pack '${pack.code}' has no usable recognition model"
            return false
        }

        return runCatching {
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(
                    sampleRate = model.sampleRate,
                    featureDim = model.featureDim,
                ),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = model.encoder.absolutePath,
                        decoder = model.decoder.absolutePath,
                        // The pack says which language to decode. Left empty, Whisper
                        // guesses — and a wrong guess on a short utterance produces
                        // confident nonsense in another language.
                        language = model.language.orEmpty(),
                        task = "transcribe",
                        tailPaddings = TAIL_PADDINGS,
                    ),
                    tokens = model.tokens.absolutePath,
                    numThreads = numThreads,
                    modelType = "whisper",
                ),
            )
            recognizer = OfflineRecognizer(config = config)
            lastError = null
            Log.i(TAG, "loaded Whisper for ${pack.code} (language='${model.language}')")
            true
        }.getOrElse {
            lastError = "Could not load the Whisper model: ${it.message}"
            Log.e(TAG, "load failed", it)
            false
        }
    }

    @Synchronized
    override fun start() {
        buffered.clear()
    }

    @Synchronized
    override fun accept(samples: ShortArray) {
        // Cap the buffer. A stuck button or a forgotten phone must not grow this
        // without bound; 60 seconds is far longer than any single radio transmission.
        if (buffered.size > sampleRate * 60) return
        for (s in samples) buffered.add(s)
    }

    /** Always empty: Whisper has no partial results. See the class note. */
    override fun partial(): String = ""

    @Synchronized
    override fun finish(): String {
        val r = recognizer ?: return ""
        if (buffered.isEmpty()) return ""

        return runCatching {
            val floats = FloatArray(buffered.size) { buffered[it] / 32768.0f }
            buffered.clear()

            val stream = r.createStream()
            stream.acceptWaveform(floats, sampleRate)
            r.decode(stream)
            val text = r.getResult(stream).text.trim()
            stream.release()

            Log.i(TAG, "finish: ${floats.size} samples, result=\"$text\"")
            text
        }.getOrElse {
            Log.w(TAG, "finish failed", it)
            buffered.clear()
            ""
        }
    }

    @Synchronized
    override fun release() {
        buffered.clear()
        runCatching { recognizer?.release() }
        recognizer = null
    }
}
