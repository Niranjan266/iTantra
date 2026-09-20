package com.itantra.session

import android.util.Log

import com.itantra.alert.AlertPolicy
import com.itantra.asr.AsrEngine
import com.itantra.asr.AsrEngines
import com.itantra.asr.SherpaAsrEngine
import com.itantra.audio.AudioCapture
import com.itantra.audio.AudioPlayer
import com.itantra.audio.AudioSpec
import com.itantra.codec.DecodeResult
import com.itantra.lang.LanguagePack
import com.itantra.prosody.ProsodyExtractor
import com.itantra.codec.Intent
import com.itantra.codec.LanguageId
import com.itantra.codec.Packet
import com.itantra.codec.PacketCodec
import com.itantra.codec.PhraseCodebook
import com.itantra.codec.Symbols
import com.itantra.codec.Translator
import com.itantra.telemetry.LatencyTracker
import com.itantra.telemetry.Percentiles
import com.itantra.telemetry.TransmissionRecord
import com.itantra.telemetry.WordErrorRate
import com.itantra.transport.Transport
import com.itantra.transport.TransportState
import com.itantra.tts.AlertToneRenderer
import com.itantra.tts.SherpaTtsEngine
import com.itantra.tts.SpeechRenderer
import com.itantra.vad.VadGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * The state machine. The only class that knows the whole flow (TRD section 1.2).
 *
 * The full path now runs end to end: microphone → speech recognition → ~60-byte packet
 * → transport → speech synthesis → speaker. With the loopback transport selected, one
 * device performs both ends, so the complete loop can be demonstrated on a single
 * phone with no pairing and no second handset.
 *
 * Everything below the [Transport] interface and everything above it were built
 * independently and still know nothing about each other. Swapping Bluetooth for
 * Wi-Fi Direct, or the tone renderer for a real voice, changed which object is
 * constructed and nothing in this file.
 */
class SessionController(
    private val scope: CoroutineScope,
    private var transport: Transport,
    private val capture: AudioCapture = AudioCapture(scope),
    private val player: AudioPlayer = AudioPlayer(scope),
    private val vad: VadGate = VadGate(),
    /**
     * Null in unit tests and anywhere without an Android context. When absent, received
     * packets are still decoded and counted — they simply are not played aloud.
     */
    private val alertPolicy: AlertPolicy? = null,
) {

    /**
     * The speech engines for the currently selected language.
     *
     * Mutable because switching language swaps them, exactly as switching transports
     * swaps the [Transport]. Both are runtime choices made from data, and neither is
     * allowed to leak into the code around it.
     *
     * Null / tone renderer means no usable pack is installed. The app still runs:
     * transport, codec and alerting all work. Degrade, never die.
     */
    private var asr: AsrEngine? = null
    private var renderer: SpeechRenderer = AlertToneRenderer()

    var currentPack: LanguagePack? = null
        private set

    /**
     * Every installed pack, so a received message can also be shown in the **sender's**
     * language beside the listener's.
     *
     * Only ever used to look up the sender's wording for display. The translation itself
     * needs nothing but the listener's own codebook, because a phrase id is an index and
     * not words — see [Translator].
     */
    var installedPacks: List<LanguagePack> = emptyList()

    /** Random per app run. Tells the far end when the talker changed. */
    private val sessionId: Int = Random.nextInt(0, 256)

    private var seq: Int = 0

    /**
     * Shared UI state.
     *
     * Always mutated through [MutableStateFlow.update], never `_ui.value = _ui.value.copy(...)`.
     * The latter is a read-modify-write, and this state is written from at least six
     * coroutines at once — the transport collector, model loading, the audio capture
     * loop, playback callbacks, and both send and receive paths. Two of them
     * interleaving silently discards one update.
     *
     * That is not theoretical: it was found by running the same build on two phones at
     * once. One showed the selected language, the other showed none, because the
     * transport-state collector overwrote the language field between selectLanguage's
     * read and its write. [update] retries on conflict, so no field is ever lost.
     */
    private val _ui = MutableStateFlow(SessionUiState(sessionId = sessionId))
    val ui: StateFlow<SessionUiState> = _ui.asStateFlow()

    private val records = mutableListOf<TransmissionRecord>()

    private val TAG = "iTantra.Session"

    /**
     * Recent messages, newest first, for the Messages screen.
     *
     * Deliberately **in memory only and bounded**. Nothing is written to disk: a distress
     * app that keeps a durable transcript of who said what, on a phone that may be handed
     * around or lost, creates a risk the user never asked for. If persistence is wanted
     * later it should be an explicit choice with an explicit way to clear it, not a side
     * effect of showing a list.
     */
    private val _messages = MutableStateFlow<List<LoggedMessage>>(emptyList())
    val messages: StateFlow<List<LoggedMessage>> = _messages.asStateFlow()

    /**
     * Who else is running iTantra on this link, heard from their own beacons.
     *
     * This is the answer to "who can hear me", and it is per-bearer for free: presence is
     * an ordinary ITP-1 packet, so whichever transport is selected carries it.
     *
     * Peers expire. A phone that walks away sends nothing more, and a list that only ever
     * grows would show a peer who left ten minutes ago as present — worse than showing
     * nobody, because someone might rely on it.
     */
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private var beaconJob: Job? = null

    /**
     * Announce this phone, repeatedly, for as long as a transport is up.
     *
     * Every few seconds rather than once: phones arrive after we did, and a single
     * announcement at startup is invisible to anyone who was not already listening.
     */
    fun startAnnouncing(deviceName: String) {
        beaconJob?.cancel()
        beaconJob = scope.launch {
            while (true) {
                sendPresence(deviceName)
                expirePeers()
                kotlinx.coroutines.delay(BEACON_INTERVAL_MS)
            }
        }
    }

    fun stopAnnouncing() {
        beaconJob?.cancel()
        beaconJob = null
    }

    private suspend fun sendPresence(deviceName: String) {
        if (!_ui.value.transportState.isConnected) return
        // The sound link would turn every beacon into an audible chirp.
        if (!transport.carriesPresence) return
        val packet = Packet(
            sessionId = sessionId,
            // Deliberately NOT the message sequence. A beacon must never consume a seq
            // that a real message would use, or the relay's duplicate suppression would
            // silently drop the next thing somebody actually said.
            seq = PRESENCE_SEQ,
            langId = currentPack?.id ?: LanguageId.UNSPECIFIED,
            intent = Intent.ROUTINE,
            payload = Symbols.encodePresence(
                currentPack?.id ?: LanguageId.UNSPECIFIED,
                deviceName,
            ),
            textMode = false,
        )
        runCatching { transport.send(PacketCodec.encode(packet)) }
    }

    private fun expirePeers() {
        val cutoff = System.currentTimeMillis() - PEER_TIMEOUT_MS
        _peers.update { list -> list.filter { it.lastSeenMs >= cutoff } }
    }

    /** Record a beacon. Returns true if this packet was presence and must not be spoken. */
    private fun handleIfPresence(packet: Packet): Boolean {
        if (packet.textMode) return false
        val (langId, name) = Symbols.decodePresence(packet.payload) ?: return false
        if (packet.sessionId == sessionId) return true // our own beacon, heard back

        val now = System.currentTimeMillis()
        _peers.update { list ->
            val others = list.filter { it.sessionId != packet.sessionId }
            others + Peer(
                sessionId = packet.sessionId,
                name = name.ifBlank { "a phone" },
                langId = langId,
                lastSeenMs = now,
            )
        }
        return true
    }

    private fun log(entry: LoggedMessage) {
        _messages.update { (listOf(entry) + it).take(MAX_LOG) }
    }

    /**
     * Collectors for the current transport. Held so that switching transports can
     * cancel them — without this, every switch would leave the old collector running
     * and each frame would be handled once per transport ever selected.
     */
    private var incomingJob: Job? = null
    private var stateJob: Job? = null

    init {
        observe(transport)
    }

    private fun observe(t: Transport) {
        incomingJob?.cancel()
        stateJob?.cancel()
        incomingJob = scope.launch { t.incoming.collect { frame -> onFrameReceived(frame) } }
        stateJob = scope.launch {
            t.state.collect { s ->
                _ui.update { it.copy(
                    transportState = s,
                    transportName = t.name,
                    bearerBps = t.nominalBitrate,
                ) }
            }
        }
    }

    suspend fun connect() = transport.connect()

    // ---- Speech path -----------------------------------------------------------
    //
    // Push-to-talk captures the microphone, recognition runs while the person is still
    // speaking, and on release the transcript is encoded and sent.
    //
    // Note what never touches the transport: audio. Three seconds of raw PCM is ~96 kB
    // and would take thirteen minutes over a 1 kbps link, where the same sentence as an
    // ITP-1 packet takes about half a second. That difference is the product.

    private val recorded = mutableListOf<ShortArray>()
    private var captureJob: Job? = null

    /**
     * Switch to a language pack, releasing whatever was loaded before.
     *
     * This is the whole of "adding a language is a data task, not an engineering task"
     * (PRD F-42). Nothing here names a language; the pack supplies its own identity,
     * its own models and its own phoneme table. Passing a pack that arrived on the
     * device five minutes ago works exactly as well as passing the bundled one.
     *
     * @param pack null to run with no speech models at all.
     */
    suspend fun selectLanguage(pack: LanguagePack?): Boolean {
        // Hand the old engines aside and release them only once the new ones exist.
        //
        // This used to release first, to keep peak memory down — two model sets loaded at
        // once is a real cost on a cheap phone. But releasing every session first means
        // that for a moment the process holds none, and onnxruntime keeps state shared
        // between sessions: the next inference then aborted the process with
        // `pthread_mutex_lock called on a destroyed mutex`, inside onnxruntime, on the
        // first *use* of the newly loaded model rather than at load. Selecting Tamil
        // after English and running the self-test killed the app every time.
        //
        // Keeping one session alive across the swap costs memory briefly and is worth it:
        // the alternative is a crash with no Java exception to catch.
        val previousAsr = asr
        val previousRenderer = renderer as? SherpaTtsEngine

        currentPack = pack
        asr = pack?.let { AsrEngines.create(it) }
        renderer = pack?.let { SherpaTtsEngine(it) } ?: AlertToneRenderer()

        _ui.update { it.copy(
            selectedLangId = pack?.id,
            selectedLangName = pack?.displayName ?: "None",
            asrReady = false,
            ttsReady = false,
            recognisedText = "",
            partialText = "",
        ) }

        if (pack == null) {
            // Nothing will be loaded, so there is no new session to keep alive and the
            // old ones have to go now or they never will.
            previousAsr?.release()
            previousRenderer?.release()
            _ui.update { it.copy(
                engineError = "No language pack installed",
                rendererName = renderer.name,
            ) }
            return false
        }

        val loaded = loadEngines()

        // Release the old engines only now, with the new ones already loaded, so the
        // process never holds zero onnxruntime sessions. Both engines are @Synchronized,
        // so this cannot overlap an inference still finishing on another dispatcher.
        previousAsr?.release()
        previousRenderer?.release()

        return loaded
    }

    /**
     * Load the speech models for the selected language. Slow (hundreds of ms to
     * seconds), so it runs off the main thread and reports progress through UI state.
     */
    suspend fun loadEngines(): Boolean = withContext(Dispatchers.IO) {
        _ui.update { it.copy(enginesLoading = true, engineError = null) }
        val started = System.nanoTime()

        // Load both models concurrently. They are independent, and loading them one
        // after the other made startup the sum of the two rather than the slower of
        // them — measured at 19 s on a mid-range phone, most of it the voice model.
        val asrLoad = async { asr?.load() ?: false }
        val ttsLoad = async { (renderer as? SherpaTtsEngine)?.load() ?: true }

        val asrOk = asrLoad.await()
        val ttsOk = ttsLoad.await()
        val loadMs = (System.nanoTime() - started) / 1_000_000.0

        val problems = buildList {
            if (!asrOk) add(AsrEngines.lastError(asr) ?: "recogniser unavailable")
            if (!ttsOk) add((renderer as? SherpaTtsEngine)?.lastError ?: "voice unavailable")
        }

        _ui.update { it.copy(
            enginesLoading = false,
            asrReady = asrOk,
            ttsReady = ttsOk,
            asrName = asr?.name ?: "-",
            rendererName = renderer.name,
            engineLoadMs = loadMs,
            engineError = problems.joinToString("; ").ifEmpty { null },
        ) }
        asrOk && ttsOk
    }

    // ---- Operating mode ---------------------------------------------------------
    //
    // The problem statement asks for both: "it should work like a walkie talkie using
    // push to talk feature, if turned off it should work like a phone."
    //
    // The difference is only who decides an utterance has ended. Push-to-talk uses the
    // button, which is the most reliable signal there is. Phone mode has no button, so
    // the voice detector's endpoint decides — which is why VadGate's hangover timer was
    // built and tested in Phase 4 even though nothing used it until now.

    private var mode: SessionMode = SessionMode.PUSH_TO_TALK

    /** Language and urgency to use for messages phone mode sends on its own. */
    private var outgoingLangId: Int = LanguageId.UNSPECIFIED
    private var outgoingIntent: Int = Intent.ROUTINE

    /**
     * Switch between walkie-talkie and phone operation (PRD section 6).
     *
     * Phone mode holds the microphone open continuously. That costs battery, which is
     * exactly why push-to-talk is the default for a device carried into the field.
     */
    suspend fun setMode(next: SessionMode, langId: Int, intent: Int) {
        outgoingLangId = langId
        outgoingIntent = intent
        if (mode == next) return

        mode = next
        _ui.update { it.copy(mode = next) }

        when (next) {
            SessionMode.PUSH_TO_TALK -> stopContinuous()
            SessionMode.PHONE -> startContinuous()
        }
    }

    /** Keep the outgoing settings current while phone mode is running. */
    fun updateOutgoing(langId: Int, intent: Int) {
        outgoingLangId = langId
        outgoingIntent = intent
    }

    private fun startContinuous() {
        if (_ui.value.isRecording) return
        beginCapture()
    }

    private fun stopContinuous() {
        capture.stop()
        captureJob?.cancel()
        captureJob = null
        vad.forceEnd()
        _ui.update { it.copy(isRecording = false, speechDetected = false) }
    }

    /**
     * Phone mode detected the end of an utterance: send it and immediately start
     * listening for the next one.
     */
    private fun onPhoneModeEndpoint(event: VadGate.Event.SpeechEnd) {
        val spoken = recorded.toList()
        recorded.clear()

        // Begin the next utterance straight away. A conversation does not pause while
        // the previous sentence is being recognised.
        asr?.let { engine ->
            scope.launch {
                val text = withContext(Dispatchers.Default) { engine.finish().trim() }
                engine.start()

                _ui.update { it.copy(
                    recognisedText = text,
                    endpointDelayMs = event.endpointDelayMs,
                    speechDetected = false,
                ) }
                if (text.isBlank()) return@launch

                val prosody = withContext(Dispatchers.Default) {
                    val flat = ShortArray(spoken.sumOf { it.size })
                    var at = 0
                    for (chunk in spoken) {
                        chunk.copyInto(flat, at)
                        at += chunk.size
                    }
                    ProsodyExtractor.extract(flat, symbolCount = text.length)
                }
                _ui.update { it.copy(prosody = prosody) }
                transmit(text, outgoingLangId, outgoingIntent, prosody)
            }
        }
    }

    /**
     * Feed the synthesiser's own output back into the recogniser.
     *
     * The speech path cannot otherwise be checked without a person in the room, and
     * "it seemed not to work" is not a diagnosis. This drives both models with known
     * input and scores the result, so three questions get separate answers:
     *
     *  - does the voice model produce audio at all, and how much
     *  - does the recogniser turn clean audio into words
     *  - how accurate is it, as a word error rate (PRD section 8, 40% of the score)
     *
     * An empty result here means the recognition path is broken. A high error rate
     * means it works but the model is weak. A live microphone cannot tell those apart,
     * because a failure to hear looks identical to a failure to recognise.
     *
     * This is synthetic speech, so the score is an upper bound — a real voice in a
     * noisy room will always do worse. It is a self-test, not the accuracy figure for
     * the submission.
     */
    suspend fun runSelfTest(fallbackReference: String): String = withContext(Dispatchers.Default) {
        // The pack supplies the sentence when it has one, so the test works in a
        // language nothing in this file has heard of.
        val reference = currentPack?.selfTestPhrase?.takeIf { it.isNotBlank() } ?: fallbackReference
        val tts = renderer as? SherpaTtsEngine
            ?: return@withContext "Self-test needs the real voice model (loaded: ${renderer.name})"
        val engine = asr ?: return@withContext "Self-test needs a recogniser"

        val probe = Packet(
            sessionId = sessionId, seq = 0,
            langId = currentPack?.id ?: LanguageId.UNSPECIFIED,
            intent = Intent.ROUTINE,
            payload = reference.toByteArray(Charsets.UTF_8),
            textMode = true,
        )

        val synthStart = System.nanoTime()
        val pcm = tts.render(probe)
        val synthMs = (System.nanoTime() - synthStart) / 1_000_000.0
        if (pcm.isEmpty()) return@withContext "Voice model produced no audio — TTS path is broken"

        val audioMs = pcm.size * 1000.0 / AudioSpec.SAMPLE_RATE

        // Feed it in real frame-sized chunks, exactly as the microphone would.
        val recogStart = System.nanoTime()
        engine.start()
        var at = 0
        while (at < pcm.size) {
            val n = minOf(AudioSpec.SAMPLES_PER_FRAME, pcm.size - at)
            engine.accept(pcm.copyOfRange(at, at + n))
            at += n
        }
        val heard = engine.finish().trim()
        val recogMs = (System.nanoTime() - recogStart) / 1_000_000.0

        val score = WordErrorRate.score(reference, heard)
        val rtf = if (audioMs > 0) recogMs / audioMs else 0.0

        // Logged as well as returned, so the figures can be read over adb without a
        // screenshot — which matters when the phone is in use and the screen is not
        // mine to photograph.
        Log.i(
            TAG,
            "self-test ${currentPack?.code}: wer=${"%.1f".format(score.wer * 100)}% " +
                "synth=${synthMs.toInt()}ms audio=${audioMs.toInt()}ms " +
                "recognise=${recogMs.toInt()}ms rtf=${"%.2f".format(rtf)} " +
                "threads=${currentPack?.tts?.numThreads}",
        )

        buildString {
            appendLine("said:  \"$reference\"")
            appendLine("heard: \"$heard\"")
            appendLine(score.describe())
            appendLine("synth %.0f ms for %.0f ms audio".format(synthMs, audioMs))
            appendLine("recognise %.0f ms  RTF %.2f".format(recogMs, rtf))
            if (heard.isEmpty()) append("EMPTY RESULT — recognition path is broken")
        }.trim()
    }

    /** PTT pressed. */
    fun startTalking() {
        if (mode == SessionMode.PHONE) return // already listening
        if (_ui.value.isRecording) return
        beginCapture()
    }

    private fun beginCapture() {
        vad.reset()
        recorded.clear()
        asr?.start()

        if (!capture.start()) {
            _ui.update { it.copy(
                micError = capture.lastError ?: "Could not start the microphone",
            ) }
            return
        }

        _ui.update { it.copy(
            isRecording = true,
            micError = null,
            speechDetected = false,
            endpointDelayMs = null,
        ) }

        captureJob = scope.launch {
            capture.frames.collect { frame ->
                // Echo guard. In phone mode the microphone stays open while the far
                // end's message plays out of this phone's speaker — so without this the
                // device recognises its own output, transmits it, receives it back and
                // never stops. On loopback that loop is instant and infinite.
                //
                // Half-duplex is also simply what a walkie-talkie link is: you do not
                // talk while the other person is talking.
                if (_ui.value.isPlaying) {
                    vad.reset()
                    return@collect
                }

                recorded += frame.samples

                // Feed the recogniser as audio arrives rather than at the end. This is
                // what keeps the tail compute small when the speaker stops, and it is
                // where most of the latency budget is either won or lost.
                asr?.accept(frame.samples)

                when (val event = vad.accept(frame.samples)) {
                    is VadGate.Event.SpeechStart ->
                        _ui.update { it.copy(speechDetected = true) }

                    is VadGate.Event.SpeechEnd ->
                        // In push-to-talk the button release is the authority and this
                        // is ignored. In phone mode there is no button, so the detector
                        // decides where the sentence ended.
                        if (mode == SessionMode.PHONE) onPhoneModeEndpoint(event)

                    null -> Unit
                }

                _ui.update { it.copy(
                    framesCaptured = capture.framesCaptured,
                    framesDropped = capture.framesDropped,
                    capturedMs = AudioSpec.msForFrames(recorded.size),
                    peakLevel = vad.peakRms,
                    partialText = asr?.partial() ?: "",
                ) }
            }
        }
    }

    /**
     * PTT released: finish recognition and put the result on the wire.
     *
     * This is the whole product in one method — speech in on this device, ~60 bytes
     * across the link, speech out on the other. With the loopback transport selected
     * the same device does both ends, which makes the complete loop demonstrable on a
     * single phone.
     *
     * @param fallbackText sent when recognition produced nothing, so the transport and
     *   codec can still be exercised with no working model.
     */
    suspend fun stopTalkingAndTransmit(
        langId: Int,
        intent: Int,
        fallbackText: String = "",
    ) {
        if (!_ui.value.isRecording) return

        capture.stop()
        captureJob?.cancel()
        captureJob = null

        // Clear the recording flag NOW, before the suspending work below.
        //
        // This used to be set after recognition finished. Anything that made
        // asr.finish() slow or throw left isRecording stuck true — and because
        // startTalking() bails out when it is already true, the next press never
        // created a recognition stream. The button stayed lit and every later
        // utterance was silently dropped: fed to a stream that did not exist,
        // "recognised" in 1 ms as empty. Found on a real handset after a few presses.
        _ui.update { it.copy(isRecording = false, speechDetected = false) }

        // The user releasing the button is a more reliable end-of-utterance signal
        // than any detector, so it wins over the hangover timer.
        val end = vad.forceEnd()

        val asrStart = System.nanoTime()
        val recognised = withContext(Dispatchers.Default) { asr?.finish().orEmpty().trim() }
        val asrMs = (System.nanoTime() - asrStart) / 1_000_000.0

        val spokenMs = AudioSpec.msForFrames(recorded.size)
        // Real-time factor: how much compute each second of audio cost. Below 1.0 means
        // the device can keep up with live speech (PRD section 8).
        val rtf = if (spokenMs > 0) asrMs / spokenMs else null

        _ui.update { it.copy(
            isRecording = false,
            framesCaptured = capture.framesCaptured,
            framesDropped = capture.framesDropped,
            capturedMs = spokenMs,
            endpointDelayMs = end?.endpointDelayMs,
            peakLevel = vad.peakRms,
            recognisedText = recognised,
            asrMs = asrMs,
            asrRtf = rtf,
            micError = if (recorded.isEmpty()) "No audio was captured" else null,
        ) }

        val toSend = recognised.ifEmpty { fallbackText }
        if (toSend.isBlank()) {
            _ui.update { it.copy(lastError = "Nothing was recognised — nothing sent") }
            return
        }

        // Measure how it was said, not just what was said (PRD F-08). Three bytes,
        // and it is the difference between the far end hearing a calm readout and
        // hearing that the sender was in trouble.
        val prosody = withContext(Dispatchers.Default) {
            val flat = ShortArray(recorded.sumOf { it.size })
            var at = 0
            for (chunk in recorded) {
                chunk.copyInto(flat, at)
                at += chunk.size
            }
            ProsodyExtractor.extract(flat, symbolCount = toSend.length)
        }
        _ui.update { it.copy(prosody = prosody) }

        transmit(toSend, langId, intent, prosody)
    }

    /**
     * Stop holding the microphone, without touching playback.
     *
     * Called when the screen goes off. Playback is deliberately left alone: a DISTRESS
     * message must finish even if the user pockets the phone (PRD F-32).
     */
    fun releaseMicrophone() {
        capture.stop()
        captureJob?.cancel()
        captureJob = null
        if (_ui.value.isRecording) {
            vad.forceEnd()
            _ui.update { it.copy(isRecording = false) }
        }
    }

    /** Full teardown. Stops everything, including a protected message. */
    fun releaseAudio() {
        releaseMicrophone()
        player.stop(force = true)
        alertPolicy?.release()
        asr?.release()
        (renderer as? SherpaTtsEngine)?.release()
    }

    /**
     * What a received packet actually says.
     *
     * A phrase reference is an index, not words: it has to be looked up in the receiver's
     * own codebook before it means anything. Three things can go wrong, and all three
     * must degrade to something honest rather than to a confident wrong sentence:
     *
     *  - **no pack selected** — nothing to look up in;
     *  - **a shorter codebook than the sender's** — the id is past the end;
     *  - **a different codebook** — the id resolves, but to the wrong sentence.
     *
     * The first two are handled here by showing the raw reference instead of inventing
     * words. The third cannot be detected from the packet alone, which is why
     * [PhraseCodebook.fingerprint] exists and why the lists are append-only: the whole
     * scheme rests on both ends holding the identical file.
     */
    /**
     * Work out what a received message says in the listener's language.
     *
     * This is where cross-language delivery happens, and it costs nothing: the packet
     * already carried an index into a list both ends agree on, so a Tamil speaker's words
     * come out of a Telugu phone in Telugu with no translation model and no extra bytes.
     * See [Translator] for the invariant that makes it safe.
     */
    private fun deliveryOf(packet: Packet): Translator.Delivery = Translator.deliver(
        packet = packet,
        listener = currentPack?.phrases ?: PhraseCodebook.EMPTY,
        listenerLangId = currentPack?.id ?: LanguageId.UNSPECIFIED,
        speaker = installedPacks.firstOrNull { it.id == packet.langId }?.phrases,
    )

    /**
     * Encode a message and put it on the wire.
     *
     * @param text what to say. In Phase 5 this comes from the recogniser instead of
     *   from a text field, and the payload switches from UTF-8 to symbol ids.
     */
    suspend fun transmit(
        text: String,
        // No default: the caller must say which language this is. A default here is how
        // a build ends up labelling every packet English regardless of what was spoken.
        langId: Int,
        intent: Int = Intent.ROUTINE,
        prosody: ProsodyExtractor.Prosody = ProsodyExtractor.Prosody.UNMEASURED,
    ) {
        val tracker = LatencyTracker()
        tracker.mark(LatencyTracker.PTT_PRESSED)

        // Try the phrase codebook first.
        //
        // If both ends already hold this sentence in a numbered list, the whole message
        // is a 12-bit index: 16 bytes on the wire instead of 60-odd. Disaster traffic is
        // highly repetitive, so this hits often — but a miss is the ordinary case for
        // free speech, not a failure, and costs nothing but the lookup. See PhraseCodebook.
        // closestId, not idOf: an added politeness word or one misheard word would
        // otherwise send the sentence as free text, which cannot cross languages at all.
        val phraseId = currentPack?.phrases?.closestId(text)

        val payload: ByteArray
        val asText: Boolean
        if (phraseId != null) {
            payload = Symbols.encodePhraseRef(phraseId)
            asText = false
        } else {
            payload = text.toByteArray(Charsets.UTF_8)
            asText = true
            if (payload.size > PacketCodec.MAX_PAYLOAD) {
                _ui.update { it.copy(
                    lastError = "Message too long: ${payload.size} bytes, " +
                        "limit ${PacketCodec.MAX_PAYLOAD}"
                ) }
                return
            }
        }

        val packet = Packet(
            sessionId = sessionId,
            seq = seq,
            langId = langId,
            intent = intent,
            payload = payload,
            pitch = prosody.pitch,
            rate = prosody.rate,
            energy = prosody.energy,
            // Text mode is the fallback, not the goal: a codebook hit sends symbol ids
            // (one escape plus the id) and is where the large size win comes from.
            textMode = asText,
            isFinal = true,
        )

        val frame = PacketCodec.encode(packet)
        tracker.mark(LatencyTracker.ENCODED)

        pendingTrackers[seq] = tracker
        val accepted = transport.send(frame)
        tracker.mark(LatencyTracker.SENT)

        // Record on SEND, not only on receive.
        //
        // Over a point-to-point link the sender never gets its own packet back, so a
        // receive-only record meant the transmitting phone exported an empty CSV — the
        // one device whose measurements we most wanted. Round-trip time is filled in
        // later if the packet does come back (loopback only).
        records += TransmissionRecord(
            seq = packet.seq,
            langId = packet.langId,
            intent = packet.intent,
            wireBytes = frame.size,
            payloadSymbols = packet.payload.size,
            totalLatencyMs = null,
            transportName = transport.name,
            bearerBps = transport.nominalBitrate,
            utteranceMs = _ui.value.capturedMs,
            asrMs = _ui.value.asrMs,
            asrRtf = _ui.value.asrRtf,
            framesDropped = _ui.value.framesDropped,
            ok = accepted,
            // Name the encoding in the record. The efficiency figures are only
            // interpretable if a reader can tell a codebook hit from a spelled-out
            // message — reporting an averaged "compression ratio" over a mix of the two
            // would quietly credit the codebook for bytes it did not save.
            note = when {
                !accepted -> "transport rejected"
                phraseId != null -> "sent, phrase $phraseId"
                else -> "sent, text"
            },
        )

        log(
            LoggedMessage(
                atMs = System.currentTimeMillis(),
                incoming = false,
                text = text,
                original = null,
                langId = langId,
                intent = intent,
                wireBytes = frame.size,
                kind = if (phraseId != null) Translator.Kind.PHRASE_SAME_LANGUAGE
                else Translator.Kind.VERBATIM,
                packet = packet,
            )
        )

        _ui.update { it.copy(
            lastSentPacket = packet,
            lastSentHex = PacketCodec.hexDump(frame),
            lastSentBytes = frame.size,
            sentCount = it.sentCount + 1,
            lastError = if (accepted) null else "Transport rejected the frame",
        ) }

        seq = (seq + 1) and 0xFF
    }

    /**
     * Timers for sends awaiting their own echo.
     *
     * On a real point-to-point link nothing ever comes back, so these are never
     * claimed. Bounded for exactly that reason — an unbounded map here would grow for
     * as long as the app kept talking.
     */
    private val pendingTrackers = object : LinkedHashMap<Int, LatencyTracker>() {
        override fun removeEldestEntry(eldest: Map.Entry<Int, LatencyTracker>) = size > 64
    }

    /**
     * Recently seen (session, sequence) pairs, for duplicate suppression.
     *
     * Repeat-send FEC deliberately transmits the same packet several times, because at
     * 75 bytes redundancy is nearly free and a lossy radio link is not. That only works
     * if the receiver plays the message once — otherwise the "reliability" feature
     * announces every distress call three times.
     */
    private val recentlySeen = LinkedHashMap<Int, Long>()

    private fun isDuplicate(packet: Packet): Boolean {
        val key = (packet.sessionId shl 8) or packet.seq
        val now = System.nanoTime()
        val previous = recentlySeen[key]

        // Expire old entries so a wrapped sequence number is not mistaken for a repeat.
        recentlySeen.entries.removeAll { now - it.value > DEDUPE_WINDOW_NS }

        recentlySeen[key] = now
        return previous != null && now - previous <= DEDUPE_WINDOW_NS
    }

    private fun onFrameReceived(frame: ByteArray) {
        when (val result = PacketCodec.decode(frame)) {
            is DecodeResult.Success -> {
                val packet = result.packet

                // A beacon is not a message. Taken out here, before anything else looks
                // at it, so it is never counted as a transmission, never logged, never
                // shown and — worst of all — never spoken aloud. Every phone in range
                // announcing itself out loud every four seconds would be unusable.
                if (handleIfPresence(packet)) return

                if (isDuplicate(packet)) {
                    _ui.update { it.copy(
                        duplicatesSuppressed = it.duplicatesSuppressed + 1,
                    ) }
                    return
                }
                // Round-trip timing is only meaningful when the packet we just received
                // is one WE sent — which happens on loopback and nowhere else.
                //
                // Matching on sequence number alone was wrong: over a real link the far
                // end's packets carry their own sequence numbers starting at 0, so our
                // pending timer for seq 0 was being closed by the *other* phone's first
                // message. On two paired handsets that reported a 35-second latency for
                // a link that had actually delivered in milliseconds.
                //
                // Genuine mouth-to-ear latency across two devices needs a shared clock
                // (PRD section 10.6) and is not implemented. Reporting nothing is right;
                // reporting a fabricated number on a metric worth 20% is not.
                val isOwnEcho = packet.sessionId == sessionId
                val tracker = if (isOwnEcho) pendingTrackers.remove(packet.seq) else null
                tracker?.mark(LatencyTracker.RECEIVED)
                tracker?.mark(LatencyTracker.DECODED)
                val latency = tracker?.total()

                if (isOwnEcho && latency != null) {
                    // Our own packet came back: complete the record we wrote on send
                    // rather than adding a second row for the same transmission.
                    val i = records.indexOfLast { it.seq == packet.seq && it.totalLatencyMs == null }
                    if (i >= 0) records[i] = records[i].copy(totalLatencyMs = latency)
                } else {
                    // A message from the far end. Recorded so the receiving device has
                    // its own evidence of what arrived.
                    records += TransmissionRecord(
                        seq = packet.seq,
                        langId = packet.langId,
                        intent = packet.intent,
                        wireBytes = frame.size,
                        payloadSymbols = packet.payload.size,
                        totalLatencyMs = null,
                        transportName = transport.name,
                        bearerBps = transport.nominalBitrate,
                        utteranceMs = 0,
                        framesDropped = 0,
                        ok = true,
                        note = "received",
                    )
                }

                // Resolved once, outside the update block: _ui.update retries on
                // contention, and this does file-backed codebook lookups.
                val delivery = deliveryOf(packet)

                log(
                    LoggedMessage(
                        atMs = System.currentTimeMillis(),
                        incoming = true,
                        text = delivery.translated,
                        original = delivery.original,
                        langId = packet.langId,
                        intent = packet.intent,
                        wireBytes = frame.size,
                        kind = delivery.kind,
                        packet = packet,
                    )
                )

                _ui.update { it.copy(
                    lastReceivedPacket = packet,
                    lastReceivedText = delivery.translated,
                    lastDelivery = delivery,
                    lastReceivedBytes = frame.size,
                    receivedCount = it.receivedCount + 1,
                    lastLatencyMs = latency,
                    lastError = null,
                ) }
                onPacketReceived(packet)
            }

            is DecodeResult.Failure -> {
                _ui.update { it.copy(
                    errorCount = it.errorCount + 1,
                    lastError = "Dropped packet: ${result.error.message}",
                ) }
            }
        }
    }

    /**
     * The receive side: render the message and play it under the rules for its urgency
     * (PRD F-20, F-30 … F-32).
     *
     * In Phase 4 the renderer produces an attention tone rather than speech. Everything
     * around it — the volume override, the audio focus grab, the refusal to interrupt a
     * DISTRESS message — is real. Phase 5 swaps the renderer and changes nothing here.
     */
    private fun onPacketReceived(packet: Packet) {
        val policy = alertPolicy ?: return

        // Speak it in the listener's language, and then — when the sender used a
        // different one and that language is also installed here — in the sender's own
        // words as well.
        //
        // Two renderings of one message, not two messages. A listener who understands
        // both gets to check the translation against the original, which for a distress
        // call is a real safety property rather than a nicety: a phrase id resolving to
        // the wrong sentence is silent otherwise.
        val translated = renderer.render(packet)
        val original = originalAudioFor(packet)
        // Order matters, and so does dropping the empty one.
        //
        // A message that could not be translated arrives as the sender's own words, and
        // our voice cannot read another language's script: a Tamil voice handed Hindi
        // text skips every character and returns silence. Playing "silence, pause, the
        // sender's voice" makes a working message look broken, so an empty rendering is
        // left out and the sender's voice carries it alone.
        val pcm = listOfNotNull(
            translated.takeIf { it.isNotEmpty() },
            original?.takeIf { it.isNotEmpty() },
        ).let { parts ->
            when (parts.size) {
                0 -> ShortArray(0)
                1 -> parts[0]
                else -> parts[0] + gap() + parts[1]
            }
        }

        if (pcm.isEmpty()) {
            // Nothing can be spoken: the words are in a language whose voice is not on
            // this phone. The text is on screen, and saying so is better than a silence
            // the listener has to interpret.
            // The pack's own name when we have it, else the English name of the id.
            val name = installedPacks.firstOrNull { it.id == packet.langId }?.displayName
                ?: LanguageId.debugNameOf(packet.langId)
            _ui.update {
                it.copy(
                    lastError = "Shown only: this phone has no $name voice to read it aloud. " +
                        "Add $name in Settings to hear messages like this.",
                )
            }
            return
        }

        val accepted = policy.play(pcm, packet.intent) {
            _ui.update { it.copy(isPlaying = false, playingIntent = null) }
        }

        _ui.update { state ->
            if (accepted) {
                state.copy(
                    isPlaying = true,
                    playingIntent = packet.intent,
                    rendererName = renderer.name,
                )
            } else {
                // Refused because something uninterruptible is already playing. That is
                // the guarantee working, not a failure — say so plainly.
                state.copy(
                    lastError = "Held back: a ${Intent.nameOf(policy.playingIntent ?: 0)} " +
                        "message is playing and cannot be interrupted",
                )
            }
        }
    }

    /**
     * The sender's own words, rendered by the sender's language pack.
     *
     * Null whenever there is nothing to add: the sender used our language, the message was
     * plain text rather than a phrase reference, or that language is not installed here.
     * Building a voice engine per playback would be far too slow, so this only speaks for
     * languages already on the phone, and loads that pack's voice on demand.
     */
    private fun originalAudioFor(packet: Packet): ShortArray? {
        if (!speakOriginalToo) return null
        val mine = currentPack ?: return null
        if (packet.langId == mine.id) return null

        val senderPack = installedPacks.firstOrNull { it.id == packet.langId } ?: return null
        if (senderPack.tts?.isComplete != true) return null

        val engine = originalRenderers.getOrPut(senderPack.id) {
            SherpaTtsEngine(senderPack).also { if (!it.load()) return null }
        }
        val pcm = engine.render(packet)
        return pcm.takeIf { it.isNotEmpty() }
    }

    /** Half a second of silence, so the two renderings do not run together as one. */
    private fun gap(): ShortArray = ShortArray(AudioSpec.SAMPLE_RATE / 2)

    /**
     * Voices for languages other than the selected one, kept loaded once used.
     *
     * Cached because loading a VITS model takes seconds and a listener who wants the
     * original once will want it every time. Released with everything else.
     */
    private val originalRenderers = mutableMapOf<Int, SherpaTtsEngine>()

    /** Whether a translated message is also spoken in the sender's own language. */
    var speakOriginalToo: Boolean = true

    /**
     * Say the last received message again.
     *
     * In an emergency the first listen is often lost — to panic, to wind, to someone
     * talking over it. A message that cannot be repeated is a message that was not
     * really delivered, so this is a first-class control on the main screen, not a
     * hidden convenience.
     */
    fun replayLast() {
        val packet = _ui.value.lastReceivedPacket ?: return
        onPacketReceived(packet)
    }

    /**
     * Speak any message from the log again.
     *
     * Re-renders from the stored packet rather than replaying audio, because no audio was
     * ever kept — none crossed the link. One consequence is worth knowing rather than
     * hiding: a phrase-referenced message replayed **after switching language is spoken in
     * the new language**, because the id is resolved against whatever pack is selected
     * now. That is the translation, on demand, from history.
     */
    fun speak(message: LoggedMessage) {
        onPacketReceived(message.packet)
    }

    /** Stop playback, unless a DISTRESS message is protecting itself (PRD F-32). */
    fun stopPlayback(): Boolean = alertPolicy?.stop() ?: true

    /** Swap the transport without disturbing anything above it (TRD section 1.1). */
    suspend fun switchTransport(next: Transport) {
        transport.close()
        transport = next
        observe(next)
        // Timing measurements from the old link do not describe the new one.
        pendingTrackers.clear()
        next.connect()
    }

    companion object {
        /**
         * How long a (session, seq) pair is remembered. Long enough to catch repeat
         * sends of the same utterance, short enough that a wrapped 8-bit sequence
         * number in a long conversation is not mistaken for a duplicate.
         */
        private const val DEDUPE_WINDOW_NS = 3_000_000_000L
    }

    fun exportCsv(): String =
        (listOf(TransmissionRecord.CSV_HEADER) + records.map { it.toCsvRow() })
            .joinToString("\n")

    val recordCount: Int get() = records.size

    /**
     * Everything measured so far, as text for the screen and the report.
     *
     * Reports p50 and p95 rather than a best case. One good run is not evidence, and
     * the tail is where a walkie-talkie either feels responsive or does not.
     */
    fun summary(): String {
        if (records.isEmpty()) return "No transmissions recorded yet."
        val latencies = records.mapNotNull { it.totalLatencyMs }
        val rtfs = records.mapNotNull { it.asrRtf }
        val bytes = records.map { it.wireBytes.toDouble() }
        return buildString {
            appendLine("Transmissions: ${records.size}")
            appendLine(Percentiles.summarise("Latency", latencies))
            appendLine(Percentiles.summarise("Wire size", bytes, " B"))
            if (rtfs.isNotEmpty()) appendLine(Percentiles.summarise("ASR RTF", rtfs, ""))
            appendLine("Frames dropped: ${records.sumOf { it.framesDropped }}")
        }.trim()
    }
}

/**
 * How the link is operated (PRD section 6).
 *
 * Push-to-talk is the default because it is the mode that survives: half-duplex, no
 * microphone held open, and the button is an unambiguous end-of-utterance signal.
 * Phone mode is the higher-bandwidth, higher-battery option for when the link is good.
 */
enum class SessionMode {
    PUSH_TO_TALK,
    PHONE;

    fun label(): String = when (this) {
        PUSH_TO_TALK -> "Push to talk"
        PHONE -> "Phone"
    }
}

data class SessionUiState(
    val sessionId: Int = 0,
    val transportName: String = "-",
    val transportState: TransportState = TransportState.Idle,
    val lastSentPacket: Packet? = null,
    val lastSentHex: String = "",
    val lastSentBytes: Int = 0,
    val lastReceivedPacket: Packet? = null,
    val lastReceivedText: String = "",
    /**
     * How the last message was obtained — translated, verbatim, or unresolved.
     *
     * The screen reads this rather than guessing. Showing "translated" over a message that
     * merely arrived as plain text would be a lie the user cannot check.
     */
    val lastDelivery: Translator.Delivery? = null,
    val lastReceivedBytes: Int = 0,
    val sentCount: Int = 0,
    val receivedCount: Int = 0,
    val errorCount: Int = 0,
    val lastLatencyMs: Double? = null,
    val lastError: String? = null,

    // Phase 3 — audio
    val isRecording: Boolean = false,
    val isPlaying: Boolean = false,
    val speechDetected: Boolean = false,
    val framesCaptured: Int = 0,
    val framesDropped: Int = 0,
    val capturedMs: Int = 0,
    val endpointDelayMs: Int? = null,
    val peakLevel: Double = 0.0,
    val micError: String? = null,

    // Phase 4 — alerting
    val playingIntent: Int? = null,
    val rendererName: String = "",

    // Phase 5 — speech models
    val enginesLoading: Boolean = false,
    val asrReady: Boolean = false,
    val ttsReady: Boolean = false,
    val asrName: String = "-",
    val partialText: String = "",
    val recognisedText: String = "",
    val asrMs: Double? = null,
    val asrRtf: Double? = null,
    val bearerBps: Int? = null,
    val mode: SessionMode = SessionMode.PUSH_TO_TALK,
    val duplicatesSuppressed: Int = 0,
    val prosody: ProsodyExtractor.Prosody = ProsodyExtractor.Prosody.UNMEASURED,
    val selectedLangId: Int? = null,
    val selectedLangName: String = "-",
    val engineLoadMs: Double? = null,
    val engineError: String? = null,
)

/**
 * One entry in the recent-message list.
 *
 * Holds the [packet] so the entry can be replayed and re-spoken later — including in a
 * different language, since re-rendering resolves the phrase id against whatever pack is
 * selected at that moment. Replaying an old message after switching language is, in
 * effect, asking for the translation again.
 */
data class LoggedMessage(
    val atMs: Long,
    /** True for a message that arrived, false for one this phone sent. */
    val incoming: Boolean,
    /** What it says in the listener's language. */
    val text: String,
    /** The sender's own wording, when a translation happened. */
    val original: String?,
    val langId: Int,
    val intent: Int,
    val wireBytes: Int,
    val kind: com.itantra.codec.Translator.Kind,
    val packet: com.itantra.codec.Packet,
)

/** Cap on the in-memory message log. */
private const val MAX_LOG = 50

/**
 * Another phone running iTantra, heard on the current link.
 *
 * Identified by its session id, which is random per app run — so the same handset restarted
 * appears as a new peer. That is the honest behaviour: a stable device identity would mean
 * storing one, and this app deliberately keeps nothing.
 */
data class Peer(
    val sessionId: Int,
    val name: String,
    val langId: Int,
    val lastSeenMs: Long,
) {
    fun secondsAgo(nowMs: Long = System.currentTimeMillis()): Int =
        ((nowMs - lastSeenMs) / 1000).toInt().coerceAtLeast(0)
}

/** How often a phone announces itself. */
private const val BEACON_INTERVAL_MS = 4_000L

/**
 * How long a peer stays listed after its last beacon.
 *
 * Three missed beacons rather than one: a single dropped packet on a busy channel is
 * ordinary, and a peer flickering in and out of the list is worse than a slightly stale one.
 */
private const val PEER_TIMEOUT_MS = 13_000L

/**
 * The sequence number every beacon uses.
 *
 * Fixed and outside the range real messages walk through, so a beacon can never occupy a
 * seq that a spoken message needs. The relay suppresses duplicates by (sessionId, seq), and
 * a beacon stealing a seq would make the next real message from that phone look like one
 * already seen — and be silently dropped.
 */
private const val PRESENCE_SEQ = 255
