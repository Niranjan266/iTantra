package com.itantra.asr

import android.util.Log
import com.itantra.lang.LanguagePack
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * Speech to text (PRD F-04, F-05).
 *
 * An interface so the recogniser can be swapped — for the phoneme-target model in
 * Phase 6, or for a stub in tests — without the session controller knowing.
 */
interface AsrEngine {
    val name: String

    /**
     * Whether this engine produces results while the person is still speaking.
     *
     * False for Whisper-class models, which only run once the utterance is complete.
     * The UI reports it rather than hiding it: for those languages there are no live
     * partials and the latency figure is genuinely worse (see WhisperAsrEngine).
     */
    val isStreaming: Boolean get() = true

    /** Load the model. Slow — call off the main thread. False means it did not load. */
    fun load(): Boolean

    /** Begin an utterance. */
    fun start()

    /** Feed 16 kHz mono samples. Safe to call repeatedly while speech continues. */
    fun accept(samples: ShortArray)

    /**
     * The best transcript so far, without ending the utterance.
     *
     * This is what makes partial-prefix commit possible (PRD F-05): the far end can
     * start speaking before the sender has finished their sentence, which is where the
     * latency score is actually won.
     */
    fun partial(): String

    /** End the utterance and return the final transcript. */
    fun finish(): String

    fun release()
}

/**
 * sherpa-onnx streaming Zipformer transducer.
 *
 * Runs entirely on the device with no network of any kind — the app declares no
 * INTERNET permission, so this is structurally guaranteed rather than merely intended
 * (PRD C-02).
 *
 * Model files come from a [LanguagePack]; no filename or language name appears here.
 */
class SherpaAsrEngine(
    private val pack: LanguagePack,
    private val numThreads: Int = 2,
) : AsrEngine {

    companion object {
        private const val TAG = "iTantra.Asr"

        /**
         * Silence padded onto each end of an utterance.
         *
         * 700 ms is enough context for the transducer to commit its first and last
         * tokens, and it costs nothing that matters: the padding is fed as samples, not
         * waited for, so it adds compute in the low milliseconds and no latency.
         */
        private const val PAD_MS = 700
    }

    // Every method touching the native stream is @Synchronized.
    //
    // The capture loop calls accept() and partial() on one coroutine while finish()
    // runs on another. Cancelling the capture job does not stop a call already inside
    // the native layer, so without this two threads reach the same OnlineStream at once
    // and the process dies with a SIGSEGV — no Java exception, just a tombstone naming
    // this class. Found by pressing the talk button once.
    private val sampleRate: Int get() = pack.asr?.sampleRate ?: 16_000
    private val padSamples: Int get() = sampleRate * PAD_MS / 1000

    /**
     * Serialises every call that touches the native stream.
     *
     * The capture loop calls [accept] and [partial] on one coroutine while [finish]
     * runs on another. Cancelling the capture job does not stop a call already inside
     * the native layer, so without this two threads reach the same OnlineStream at
     * once and the process dies with a SIGSEGV — no Java exception, just a tombstone
     * naming this class. Found by pressing the talk button.
     */
    override val name: String get() = "Zipformer (${pack.code})"

    override val isStreaming: Boolean get() = true

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null

    var lastError: String? = null
        private set

    val isLoaded: Boolean get() = recognizer != null

    override fun load(): Boolean {
        if (recognizer != null) return true

        val model = pack.asr
        if (model == null || !model.isComplete) {
            lastError = "Language pack '${pack.code}' has no usable recognition model"
            return false
        }
        // A transducer without a joiner cannot be built. Say so, rather than failing
        // later with a native error that names no cause.
        val joiner = model.joiner
        if (joiner == null) {
            lastError = "Pack '${pack.code}' declares a transducer but supplies no joiner"
            return false
        }

        return runCatching {
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(
                    sampleRate = model.sampleRate,
                    featureDim = model.featureDim,
                ),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = model.encoder.absolutePath,
                        decoder = model.decoder.absolutePath,
                        joiner = joiner.absolutePath,
                    ),
                    tokens = model.tokens.absolutePath,
                    numThreads = numThreads,
                    modelType = "zipformer",
                ),
                // sherpa's own endpointing is left off: our VadGate already decides
                // when the utterance ends, and push-to-talk release overrides both.
                // Two competing endpointers would fight each other.
                enableEndpoint = false,
                endpointConfig = EndpointConfig(),
            )

            // No AssetManager argument: packs are real directories on disk by the time
            // we get here, so the file-path path is used (see LanguagePackManager).
            recognizer = OnlineRecognizer(config = config)
            lastError = null
            Log.i(TAG, "loaded recogniser for ${pack.code}")
            true
        }.getOrElse {
            lastError = "Could not load the recognition model: ${it.message}"
            Log.e(TAG, "load failed", it)
            false
        }
    }

    /** Frames fed since [start]. Diagnostic: an empty result with 0 here is a wiring
     *  fault, while an empty result after hundreds of frames is a model or audio
     *  problem. Without this the two are indistinguishable from the outside. */
    var framesAccepted: Int = 0
        private set

    @Synchronized
    override fun start() {
        val r = recognizer ?: run {
            Log.w(TAG, "start() with no recogniser loaded")
            return
        }
        framesAccepted = 0
        runCatching {
            stream?.release()
            val s = r.createStream()
            stream = s
            // Prime with silence before the first real audio.
            //
            // A streaming transducer needs left context before it will commit its first
            // token. Someone using push-to-talk starts speaking the instant they press,
            // so the model's first frames ARE the first word and the leading phonemes
            // are lost. Measured with the self-test: "flood water..." came back as
            // "OD WATER..." without this.
            s.acceptWaveform(FloatArray(padSamples), sampleRate)
            Log.i(TAG, "stream created (+${PAD_MS}ms lead-in)")
        }.onFailure { Log.w(TAG, "could not create stream", it) }
    }

    @Synchronized
    override fun accept(samples: ShortArray) {
        val r = recognizer ?: return
        val s = stream ?: run {
            if (framesAccepted == 0) Log.w(TAG, "accept() with no stream — start() missing?")
            return
        }
        framesAccepted++

        // sherpa wants float samples normalised to -1..1.
        val floats = FloatArray(samples.size) { samples[it] / 32768.0f }

        runCatching {
            s.acceptWaveform(floats, sampleRate)
            // Decode everything the model has enough context for. Doing this as audio
            // arrives is what keeps the tail compute small when the speaker stops.
            while (r.isReady(s)) r.decode(s)
        }.onFailure { Log.w(TAG, "accept failed", it) }
    }

    @Synchronized
    override fun partial(): String {
        val r = recognizer ?: return ""
        val s = stream ?: return ""
        return runCatching { r.getResult(s).text }.getOrDefault("")
    }

    @Synchronized
    override fun finish(): String {
        val r = recognizer ?: return ""
        val s = stream ?: return ""
        return runCatching {
            // Trailing silence before closing the stream, for the same reason as the
            // lead-in: the model will not emit its final tokens while it still expects
            // more audio. Push-to-talk users release the button on the last syllable,
            // so without this the last word is dropped — "send boats" came back as
            // "SUNN" until this was added.
            s.acceptWaveform(FloatArray(padSamples), sampleRate)
            while (r.isReady(s)) r.decode(s)

            s.inputFinished()
            while (r.isReady(s)) r.decode(s)
            val text = r.getResult(s).text
            Log.i(TAG, "finish: $framesAccepted frames accepted, result=\"$text\"")
            s.release()
            stream = null
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
    }
}
