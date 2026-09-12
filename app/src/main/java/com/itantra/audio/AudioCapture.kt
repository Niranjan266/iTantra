package com.itantra.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Microphone capture (PRD F-01).
 *
 * Reads 20 ms frames from [AudioRecord] on a dedicated urgent-audio thread and hands
 * them out as a flow. It does no analysis of its own: the rule from TRD section 5 is
 * that audio threads never touch a model and model threads never touch the audio
 * device. A dropped frame is survivable; a blocked capture thread is an audible glitch.
 *
 * Dropped frames are counted rather than hidden, because "0 dropped" is a claim the
 * project has to be able to make with evidence.
 */
class AudioCapture(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "iTantra.Capture"

        /**
         * VOICE_RECOGNITION rather than MIC: it asks the platform not to apply the
         * aggressive AGC and noise suppression tuned for phone calls, which help a
         * human listener and hurt a recogniser.
         */
        private const val AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    private val _frames = MutableSharedFlow<AudioFrame>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val frames: Flow<AudioFrame> = _frames.asSharedFlow()

    private var record: AudioRecord? = null
    private var job: Job? = null

    @Volatile
    private var running = false

    var framesCaptured: Int = 0
        private set

    /** Frames the consumer could not keep up with. Must stay at zero. */
    var framesDropped: Int = 0
        private set

    var lastError: String? = null
        private set

    val isRunning: Boolean get() = running

    /**
     * @return true if the microphone opened. False means the permission is missing or
     *   another app holds the microphone — both recoverable, neither a crash.
     */
    @SuppressLint("MissingPermission") // Caller checks RECORD_AUDIO first.
    fun start(): Boolean {
        if (running) return true

        framesCaptured = 0
        framesDropped = 0
        lastError = null

        val minBuffer = AudioRecord.getMinBufferSize(
            AudioSpec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            lastError = "16 kHz mono capture is not supported on this device"
            return false
        }
        // Four frames of headroom so a scheduling hiccup does not lose audio.
        val bufferBytes = maxOf(minBuffer, AudioSpec.BYTES_PER_FRAME * 4)

        val rec = try {
            AudioRecord(
                AUDIO_SOURCE,
                AudioSpec.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (e: SecurityException) {
            lastError = "Microphone permission denied"
            return false
        } catch (e: IllegalArgumentException) {
            lastError = "Could not open the microphone: ${e.message}"
            return false
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            lastError = "Microphone is unavailable (another app may be using it)"
            runCatching { rec.release() }
            return false
        }

        record = rec
        running = true

        try {
            rec.startRecording()
        } catch (e: IllegalStateException) {
            lastError = "Could not start recording: ${e.message}"
            stop()
            return false
        }

        job = scope.launch(Dispatchers.IO) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ShortArray(AudioSpec.SAMPLES_PER_FRAME)
            var index = 0

            while (running) {
                val read = try {
                    rec.read(buf, 0, buf.size)
                } catch (e: IllegalStateException) {
                    break
                }
                if (read <= 0) {
                    if (read < 0) Log.w(TAG, "AudioRecord.read returned $read")
                    break
                }

                val frame = AudioFrame(
                    samples = buf.copyOf(read),
                    index = index++,
                    capturedAtNanos = System.nanoTime(),
                )
                framesCaptured++
                if (!_frames.tryEmit(frame)) framesDropped++
            }
        }

        return true
    }

    fun stop() {
        running = false
        job?.cancel()
        job = null
        record?.let { rec ->
            runCatching { if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop() }
            runCatching { rec.release() }
        }
        record = null
    }
}
