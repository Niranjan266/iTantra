package com.itantra.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.itantra.audio.AudioPlayer
import com.itantra.codec.Intent
import kotlinx.coroutines.CoroutineScope

/**
 * Applies [AlertRules] to the device (PRD F-30 … F-33).
 *
 * The problem statement asks for alert messages to be "announced at highest volume
 * non-interruptible". That means deliberately overriding settings the user chose, which
 * is only defensible if two things hold:
 *
 *  1. It happens only for DISTRESS, never for ordinary traffic.
 *  2. The user's settings are put back afterwards, without fail.
 *
 * The second is the part that is easy to get wrong. Volume is restored in a `finally`
 * so that it happens even if playback throws, and the saved value is captured before
 * anything is changed. An app that leaves the alarm volume at maximum is a bug its user
 * discovers at six in the morning.
 */
class AlertPolicy(
    context: Context,
    private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "iTantra.Alert"
    }

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var focusRequest: AudioFocusRequest? = null
    private var savedVolume: Int? = null
    private var savedStream: Int? = null

    private var player: AudioPlayer? = null

    /** Urgency of whatever is playing right now, or null if nothing is. */
    var playingIntent: Int? = null
        private set

    /**
     * Play a message under the rules for its urgency.
     *
     * @return false if a currently playing message refused to be displaced. The caller
     *   should not treat that as an error — it is the non-interruptible guarantee doing
     *   its job.
     */
    fun play(samples: ShortArray, intent: Int, onFinished: () -> Unit = {}): Boolean {
        val current = playingIntent
        if (current != null && !AlertRules.shouldPreempt(intent, current)) {
            Log.i(TAG, "not preempting ${Intent.nameOf(current)} with ${Intent.nameOf(intent)}")
            return false
        }

        // Displacing something less urgent: stop it first.
        player?.stop(force = true)
        restoreVolume()

        val stream = when (AlertRules.streamFor(intent)) {
            AlertRules.Stream.ALARM -> AudioManager.STREAM_ALARM
            AlertRules.Stream.MEDIA -> AudioManager.STREAM_MUSIC
        }

        AlertRules.minimumVolumeFraction(intent)?.let { fraction ->
            raiseVolume(stream, fraction)
        }

        requestFocus(intent)

        val usage = if (AlertRules.streamFor(intent) == AlertRules.Stream.ALARM) {
            AudioAttributes.USAGE_ALARM
        } else {
            AudioAttributes.USAGE_MEDIA
        }

        val p = AudioPlayer(scope, usage = usage)
        player = p
        playingIntent = intent

        p.play(samples, lock = !AlertRules.isInterruptible(intent)) {
            try {
                abandonFocus()
                restoreVolume()
            } finally {
                playingIntent = null
                player = null
                onFinished()
            }
        }
        return true
    }

    /**
     * Stop playback if the current message allows it.
     *
     * @return false when a DISTRESS message is playing — that is the requirement, not
     *   a failure.
     */
    fun stop(): Boolean {
        val current = playingIntent ?: return true
        if (!AlertRules.isInterruptible(current)) return false
        player?.stop()
        return true
    }

    /** Teardown only. Ignores the non-interruptible guarantee to avoid leaking. */
    fun release() {
        player?.stop(force = true)
        player = null
        playingIntent = null
        abandonFocus()
        restoreVolume()
    }

    private fun raiseVolume(stream: Int, fraction: Double) {
        runCatching {
            val max = audioManager.getStreamMaxVolume(stream)
            val target = Math.round(max * fraction).toInt().coerceIn(0, max)
            val current = audioManager.getStreamVolume(stream)

            // A minimum, not an assignment: never turn the device down mid-emergency.
            if (current >= target) return

            // Save before changing, so restore always has the real original.
            savedVolume = current
            savedStream = stream
            audioManager.setStreamVolume(stream, target, 0)
            Log.i(TAG, "raised stream $stream from $current to $target (max $max)")
        }.onFailure {
            // Some OEM builds and DND policies refuse volume changes. The message must
            // still play, just quieter, rather than not at all.
            Log.w(TAG, "could not raise volume", it)
        }
    }

    private fun restoreVolume() {
        val stream = savedStream ?: return
        val volume = savedVolume ?: return
        runCatching { audioManager.setStreamVolume(stream, volume, 0) }
            .onFailure { Log.w(TAG, "could not restore volume", it) }
        savedStream = null
        savedVolume = null
    }

    private fun requestFocus(intent: Int) {
        val gain = if (AlertRules.isInterruptible(intent)) {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        } else {
            // Exclusive: asks the system to keep other apps quiet for the duration,
            // rather than merely ducking them.
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val usage = if (AlertRules.streamFor(intent) == AlertRules.Stream.ALARM) {
                    AudioAttributes.USAGE_ALARM
                } else {
                    AudioAttributes.USAGE_MEDIA
                }
                val req = AudioFocusRequest.Builder(gain)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(usage)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    // We must not pause a distress message because a call arrives.
                    .setWillPauseWhenDucked(false)
                    .build()
                focusRequest = req
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(null, AudioManager.STREAM_ALARM, gain)
            }
        }
    }

    private fun abandonFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        }
        focusRequest = null
    }
}
