package com.itantra.transport

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.itantra.acoustic.AcousticDemodulator
import com.itantra.acoustic.AcousticModem
import com.itantra.audio.MicUse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The sound link: packets as tone bursts from the speaker, heard by the microphone.
 *
 * See [AcousticModem] for the signal. This class is the plumbing around it:
 *
 *  - **Half duplex.** While this phone is sending, it does not listen, so it never
 *    decodes its own burst as a message from someone else. A short tail after each burst
 *    lets the room's echo die away before listening resumes.
 *  - **Speech has priority.** While the talk button is held the microphone belongs to
 *    speech capture; the listener releases it and reopens it afterwards (see [MicUse]).
 *  - **No presence beacons.** Every other link announces itself every few seconds. Here
 *    that would be an audible chirp every few seconds, for ever, so [carriesPresence] is
 *    false and a sound link lists no peers — it simply delivers what it hears.
 *
 * Range is what the ear would suggest: across a room at normal volume, further at full
 * volume or held to the microphone of a walkie-talkie, which then carries it as far as the
 * radio reaches.
 */
class AcousticTransport(
    private val context: Context,
    private val scope: CoroutineScope,
) : Transport {

    companion object {
        private const val TAG = "iTantra.Sound"

        /** Silence after a burst before listening again, for the room's echo to fade. */
        private const val TAIL_MS = 250L

        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    override val name: String get() = "Sound"

    /** Payload rate for a phrase packet: 16 bytes in 2.72 s. */
    override val nominalBitrate: Int get() = 47

    override val carriesPresence: Boolean get() = false

    private val _state = MutableStateFlow<TransportState>(TransportState.Idle)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    override val incoming: Flow<ByteArray> = _incoming

    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + job)

    private val sendLock = Mutex()
    @Volatile private var transmitting = false
    @Volatile private var listenJob: Job? = null

    /** Bursts heard and decoded, and ones too damaged to correct — for diagnostics. */
    @Volatile var framesHeard = 0
        private set
    @Volatile var framesRejected = 0
        private set

    override suspend fun connect() {
        if (!hasPermission(context)) {
            _state.value = TransportState.Failed("The sound link needs the microphone permission")
            return
        }
        _state.value = TransportState.Connected("anyone who can hear this phone")
        // Follow speech capture: listen whenever the talk button is not held.
        ownScope.launch {
            MicUse.speechCapture.collect { talking ->
                if (talking) stopListening() else startListening()
            }
        }
    }

    @SuppressLint("MissingPermission") // checked in connect()
    private fun startListening() {
        if (listenJob?.isActive == true) return
        listenJob = ownScope.launch(Dispatchers.IO) {
            val minBuffer = AudioRecord.getMinBufferSize(
                AcousticModem.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            val rec = runCatching {
                AudioRecord(
                    micSource(),
                    AcousticModem.RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, AcousticModem.SLOT * 2 * 4),
                )
            }.getOrNull()
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                rec?.release()
                _state.value = TransportState.Failed("The microphone is in use by another app")
                return@launch
            }
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            var demod = AcousticDemodulator()
            val chunk = ShortArray(AcousticModem.SLOT)
            try {
                rec.startRecording()
                Log.i(TAG, "listening")
                while (isActive) {
                    val n = rec.read(chunk, 0, chunk.size)
                    if (n <= 0) break
                    if (transmitting) {
                        // Our own burst. A fresh demodulator afterwards means a frame can
                        // never be stitched together across the gap.
                        demod = AcousticDemodulator()
                        continue
                    }
                    for (frame in demod.feed(chunk.copyOf(n))) {
                        framesHeard++
                        Log.i(TAG, "heard a ${frame.size} B frame")
                        _incoming.emit(frame)
                    }
                    framesRejected = demod.rejected
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
                Log.i(TAG, "stopped listening")
            }
        }
    }

    /**
     * The least-processed microphone the phone offers.
     *
     * UNPROCESSED where the phone declares it: no noise suppression, no gain control, no
     * echo cancellation. Otherwise VOICE_RECOGNITION, which Android's compatibility rules
     * require to have noise suppression off. Either processing step treats a steady tone
     * as noise to be removed — tested on a laptop, whose microphone array erased the
     * entire preamble of a burst played from its own speakers.
     */
    private fun micSource(): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        val unprocessed = am?.getProperty(
            android.media.AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED
        ) == "true"
        return if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED
        else MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    private fun stopListening() {
        listenJob?.cancel()
        listenJob = null
    }

    /**
     * Play [frame] as a tone burst. Returns when the burst has finished.
     *
     * Refuses frames over [AcousticModem.MAX_PAYLOAD] bytes: at 47 bit/s a long free-speech
     * packet would take over twenty seconds. Codebook phrases — 16 bytes — are what this
     * link is for.
     */
    override suspend fun send(frame: ByteArray): Boolean {
        if (!_state.value.isConnected) return false
        if (frame.isEmpty() || frame.size > AcousticModem.MAX_PAYLOAD) {
            Log.w(TAG, "a ${frame.size} B frame is too long for the sound link")
            return false
        }
        return sendLock.withLock {
            withContext(Dispatchers.IO) {
                val pcm = AcousticModem.modulate(frame)
                val track = runCatching {
                    AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(AcousticModem.RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(pcm.size * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()
                }.getOrNull() ?: return@withContext false

                transmitting = true
                try {
                    if (track.write(pcm, 0, pcm.size) != pcm.size) return@withContext false
                    track.play()
                    Log.i(TAG, "sending ${frame.size} B as ${AcousticModem.durationMs(frame.size)} ms of tones")
                    delay(AcousticModem.durationMs(frame.size).toLong() + TAIL_MS)
                    true
                } finally {
                    runCatching { track.stop() }
                    track.release()
                    transmitting = false
                }
            }
        }
    }

    override suspend fun close() {
        stopListening()
        job.cancel()
        _state.value = TransportState.Closed
    }
}
