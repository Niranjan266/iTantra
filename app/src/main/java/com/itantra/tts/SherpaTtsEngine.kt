package com.itantra.tts

import android.util.Log
import com.itantra.audio.AudioSpec
import com.itantra.audio.Resampler
import com.itantra.codec.Packet
import com.itantra.lang.LanguagePack
import com.itantra.lang.TokensFile
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig

/**
 * Text to speech with a VITS model, on-device (PRD F-20, F-24).
 *
 * Replaces [AlertToneRenderer]: same [SpeechRenderer] interface, so the alert policy,
 * the volume override and the non-interruptible guarantee built in Phase 4 all keep
 * working untouched. That was the point of putting an interface there.
 *
 * Model files come from a [LanguagePack] — no filename or language name appears here.
 *
 * Every method that touches the native session is [Synchronized]. Synthesis runs on one
 * dispatcher while [release] can be called from another — switching language does exactly
 * that — and cancelling a coroutine does not stop a call already inside the native layer.
 * Two threads reaching one session is not an exception in Kotlin: the process aborts with
 * `pthread_mutex_lock called on a destroyed mutex` somewhere inside onnxruntime.
 *
 * The same fix was applied to the recognisers earlier and this class was missed, which is
 * how it was found — by the process dying on a Tamil self-test.
 */
class SherpaTtsEngine(
    private val pack: LanguagePack,
) : SpeechRenderer {

    /** From the pack, which knows what its own model needs. See TtsModel.numThreads. */
    private val numThreads: Int get() = pack.tts?.numThreads ?: 2

    companion object {
        private const val TAG = "iTantra.Tts"

        /** Below this, prosody scaling is not worth applying. */
        private const val MIN_RATE_BYTE = 1
    }

    override val name: String get() = "VITS (${pack.code})"

    private var tts: OfflineTts? = null

    var lastError: String? = null
        private set

    val isLoaded: Boolean get() = tts != null

    /**
     * Native sample rate of the voice. Not necessarily 16 kHz — Piper voices are 22,050.
     * Everything this class returns has already been converted to [AudioSpec.SAMPLE_RATE].
     */
    val sampleRate: Int get() = tts?.sampleRate() ?: 0

    /** Slow. Call off the main thread. */
    @Synchronized
    fun load(): Boolean {
        if (tts != null) return true

        val model = pack.tts
        if (model == null || !model.isComplete) {
            lastError = "Language pack '${pack.code}' has no usable voice model"
            return false
        }

        // Check the vocabulary before any native code runs.
        //
        // sherpa does not return an error for a malformed tokens file — it aborts the
        // process, below the JNI boundary, where the runCatching below cannot reach it.
        // One bad line once cost four build-push-test cycles to find. See TokensFile.
        //
        // A voice with no dataDir uses the character frontend (MMS), which requires
        // single-character symbols; Piper voices are read by the phoneme frontend and
        // are not held to that rule.
        val tokensCheck = TokensFile.validate(
            model.tokens,
            requireSingleCharacter = model.dataDir == null,
        )
        if (!tokensCheck.isValid) {
            lastError = "Voice for '${pack.code}' is unusable: ${tokensCheck.error}"
            Log.e(TAG, "rejected before loading — ${tokensCheck.error}")
            return false
        }
        Log.i(TAG, "tokens validated for ${pack.code}: ${tokensCheck.symbols} symbols")

        return runCatching {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = model.model.absolutePath,
                        tokens = model.tokens.absolutePath,
                        // Empty for voices with their own vocabulary (MMS). Piper
                        // voices need espeak data, and it must be a real directory —
                        // which is why packs are copied out of the APK on first run.
                        dataDir = model.dataDir?.absolutePath.orEmpty(),
                    ),
                    numThreads = numThreads,
                ),
            )
            tts = OfflineTts(config = config)
            lastError = null
            Log.i(TAG, "loaded voice for ${pack.code} at ${tts?.sampleRate()} Hz")
            true
        }.getOrElse {
            lastError = "Could not load the voice model: ${it.message}"
            Log.e(TAG, "load failed", it)
            false
        }
    }

    @Synchronized
    override fun render(packet: Packet): ShortArray {
        val engine = tts ?: return ShortArray(0)
        // Resolve a phrase reference to its sentence. Without this the speaker says
        // the literal word "PHRASE" and the message is lost — the text on screen
        // would look right while the audio was useless. Unresolvable references
        // render as silence rather than reading a diagnostic aloud to someone in a flood.
        val text = pack.phrases.resolve(packet) { "" }.trim()
        if (text.isEmpty()) return ShortArray(0)

        val model = pack.tts ?: return ShortArray(0)

        return runCatching {
            val audio = engine.generate(
                text = text,
                sid = model.speakerId,
                speed = speedFor(packet, model.speed),
            )
            toPcm16(toPlayerRate(audio.samples, audio.sampleRate))
        }.getOrElse {
            Log.e(TAG, "synthesis failed", it)
            ShortArray(0)
        }
    }

    /**
     * Synthesise clause by clause, calling [onChunk] as each piece is ready.
     *
     * This is what lets playback begin before the whole utterance has been generated
     * (PRD F-21). For a long sentence it removes most of the synthesis time from the
     * mouth-to-ear figure.
     *
     * @param onChunk return false to abandon synthesis early.
     */
    @Synchronized
    fun renderStreaming(packet: Packet, onChunk: (ShortArray) -> Boolean): Boolean {
        val engine = tts ?: return false
        val model = pack.tts ?: return false
        // Resolve a phrase reference to its sentence. Without this the speaker says
        // the literal word "PHRASE" and the message is lost — the text on screen
        // would look right while the audio was useless. Unresolvable references
        // render as silence rather than reading a diagnostic aloud to someone in a flood.
        val text = pack.phrases.resolve(packet) { "" }.trim()
        if (text.isEmpty()) return false

        return runCatching {
            engine.generateWithCallback(
                text = text,
                sid = model.speakerId,
                speed = speedFor(packet, model.speed),
            ) { chunk ->
                // sherpa treats a non-1 return as "stop generating".
                if (onChunk(toPcm16(toPlayerRate(chunk, engine.sampleRate())))) 1 else 0
            }
            true
        }.getOrElse {
            Log.e(TAG, "streaming synthesis failed", it)
            false
        }
    }

    /**
     * Apply the sender's speaking rate, if they measured one (TRD section 4.5).
     *
     * A panicked sender spoke fast; reproducing that at the far end preserves
     * information a plain-text pipeline throws away. Clamped hard, because a wrong
     * prosody byte must never make a distress message unintelligible.
     */
    private fun speedFor(packet: Packet, base: Float): Float {
        if (packet.rate < MIN_RATE_BYTE) return base
        // Byte 0..255 maps to 0..25 symbols/sec; ~12 is unremarkable speech.
        val symbolsPerSec = packet.rate * 25.0f / 255.0f
        val ratio = (symbolsPerSec / 12.0f).coerceIn(0.8f, 1.3f)
        return base * ratio
    }

    @Synchronized
    fun release() {
        runCatching { tts?.release() }
        tts = null
    }

    /**
     * Bring the voice to the player's rate.
     *
     * The player runs at 16 kHz; Piper and Mimic3 voices speak at 22,050 Hz. Without
     * this, five of the ten languages played at 73% speed, five and a half semitones
     * low — the "deep voice" that was reported. See [Resampler].
     */
    private fun toPlayerRate(samples: FloatArray, voiceRate: Int): FloatArray =
        if (voiceRate <= 0) samples
        else Resampler.resample(samples, voiceRate, AudioSpec.SAMPLE_RATE)

    private fun toPcm16(samples: FloatArray): ShortArray =
        ShortArray(samples.size) { i ->
            // Clamp before narrowing: a value beyond range would wrap to full-scale
            // opposite sign, which is an audible crack rather than a clipped sample.
            (samples[i].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
        }
}
