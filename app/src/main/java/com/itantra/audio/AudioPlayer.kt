package com.itantra.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Speaker playback (PRD F-20).
 *
 * Streaming mode, so Phase 5 can start playing the first clause of an utterance while
 * the rest is still being synthesised (PRD F-21). Writing a whole buffer, as Phase 3
 * does, is just the degenerate case of that.
 *
 * [usage] is a constructor parameter because the DISTRESS path in Phase 4 needs to play
 * on the alarm stream to override silent mode (PRD F-31, F-33). The rest of the audio
 * path does not change between the two cases, so this is the only knob it exposes.
 */
class AudioPlayer(
    private val scope: CoroutineScope,
    private val usage: Int = AudioAttributes.USAGE_MEDIA,
    private val contentType: Int = AudioAttributes.CONTENT_TYPE_SPEECH,
) {

    companion object {
        private const val TAG = "iTantra.Player"
    }

    private var track: AudioTrack? = null
    private var job: Job? = null

    @Volatile
    private var playing = false

    /**
     * Set while an uninterruptible message is playing. Phase 4's alert policy uses this
     * to refuse to stop a DISTRESS message part-way (PRD F-32).
     */
    @Volatile
    var isLocked: Boolean = false
        private set

    val isPlaying: Boolean get() = playing

    var lastError: String? = null
        private set

    /**
     * Play a complete PCM buffer.
     *
     * @param lock refuse to be stopped until playback finishes. Used for DISTRESS.
     * @param onFinished called on completion, whether it played out or was stopped.
     */
    fun play(samples: ShortArray, lock: Boolean = false, onFinished: () -> Unit = {}) {
        stop(force = true)

        val minBuffer = AudioTrack.getMinBufferSize(
            AudioSpec.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            lastError = "16 kHz mono playback is not supported on this device"
            onFinished()
            return
        }
        val bufferBytes = maxOf(minBuffer, AudioSpec.BYTES_PER_FRAME * 8)

        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(contentType)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AudioSpec.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: UnsupportedOperationException) {
            lastError = "Could not open the speaker: ${e.message}"
            onFinished()
            return
        }

        if (t.state != AudioTrack.STATE_INITIALIZED) {
            lastError = "Speaker is unavailable"
            runCatching { t.release() }
            onFinished()
            return
        }

        track = t
        playing = true
        isLocked = lock
        lastError = null
        t.play()

        job = scope.launch(Dispatchers.IO) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                var offset = 0
                while (playing && offset < samples.size) {
                    val n = t.write(
                        samples, offset,
                        minOf(AudioSpec.SAMPLES_PER_FRAME, samples.size - offset),
                    )
                    if (n <= 0) {
                        Log.w(TAG, "AudioTrack.write returned $n")
                        break
                    }
                    offset += n
                }
                // Let the hardware buffer drain rather than cutting the tail off.
                if (playing) runCatching { t.stop() }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "playback failed", e)
            } finally {
                isLocked = false
                playing = false
                runCatching { t.release() }
                if (track === t) track = null
                onFinished()
            }
        }
    }

    /**
     * Stop playback.
     *
     * A locked message refuses to stop: that is the point of DISTRESS. [force] exists
     * only for teardown, where the alternative is leaking an [AudioTrack].
     *
     * @return true if playback was actually stopped.
     */
    fun stop(force: Boolean = false): Boolean {
        if (isLocked && !force) return false
        playing = false
        job?.cancel()
        job = null
        track?.let { t ->
            runCatching { if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.pause() }
            runCatching { t.flush() }
            runCatching { t.release() }
        }
        track = null
        isLocked = false
        return true
    }
}
