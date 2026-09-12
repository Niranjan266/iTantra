package com.itantra.alert

import com.itantra.codec.Intent

/**
 * What each urgency level is allowed to do to the device (PRD F-30 … F-33).
 *
 * The problem statement is unusually specific here: "alert type messages will be
 * announced at highest volume non-interruptible". That is a requirement about
 * overriding the user's own settings, which is a serious thing for an app to do — so
 * the rules live in one place, are stated explicitly, and are unit-tested, rather than
 * being scattered as conditionals through the playback code.
 *
 * No Android imports. [AlertPolicy] applies these decisions; this file only makes them.
 */
object AlertRules {

    /**
     * Which audio stream to play on.
     *
     * The alarm stream is the only one that still sounds when the phone is on silent,
     * which is the entire point for a distress message. Routine traffic has no business
     * there and stays on the media stream.
     */
    enum class Stream { MEDIA, ALARM }

    fun streamFor(intent: Int): Stream = when (intent) {
        Intent.DISTRESS, Intent.ALERT -> Stream.ALARM
        else -> Stream.MEDIA
    }

    /**
     * Minimum volume, as a fraction of the stream maximum, or null to leave the user's
     * setting completely alone.
     *
     * Note these are *minimums*: if the user already has it louder, it is not turned
     * down. Quietening a device during an emergency would be a defect.
     */
    fun minimumVolumeFraction(intent: Int): Double? = when (intent) {
        Intent.DISTRESS -> 1.0   // maximum, as the problem statement requires
        Intent.ALERT -> 0.7
        else -> null             // routine traffic respects the user entirely
    }

    /**
     * Can the user, or a newly arrived message, stop this one part-way?
     *
     * Only DISTRESS is protected. Making everything uninterruptible would be hostile
     * and would also mean a burst of routine chatter could block a real emergency.
     */
    fun isInterruptible(intent: Int): Boolean = intent != Intent.DISTRESS

    /** May this message be heard even in silent mode / Do Not Disturb? */
    fun overridesSilentMode(intent: Int): Boolean = intent == Intent.DISTRESS

    /**
     * Should an arriving message displace one that is already playing?
     *
     * A more urgent message wins; an equal or less urgent one waits. Combined with
     * [isInterruptible], this means nothing at all can cut off a DISTRESS message.
     */
    fun shouldPreempt(arriving: Int, playing: Int): Boolean {
        if (!isInterruptible(playing)) return false
        return arriving > playing
    }

    /**
     * Whether the user's volume must be put back afterwards.
     *
     * Anything that raises the volume must restore it. An app that silently leaves the
     * alarm volume at maximum is a bug the user discovers at 6 a.m.
     */
    fun mustRestoreVolume(intent: Int): Boolean = minimumVolumeFraction(intent) != null
}
