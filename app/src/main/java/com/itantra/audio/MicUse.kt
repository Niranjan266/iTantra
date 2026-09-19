package com.itantra.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Who has the microphone.
 *
 * Two parts of the app want it: speech capture while the talk button is held, and the
 * sound link listening for tone bursts all the rest of the time. Android does not promise
 * that two recorders in one app both receive audio — on some phones the second silences
 * the first — so the listener steps aside whenever speech is being captured, and returns
 * when it ends. Speech always wins: it is what the user is doing right now.
 */
object MicUse {
    private val _speechCapture = MutableStateFlow(false)

    /** True while [AudioCapture] is recording speech. */
    val speechCapture: StateFlow<Boolean> = _speechCapture.asStateFlow()

    internal fun speechStarted() { _speechCapture.value = true }
    internal fun speechStopped() { _speechCapture.value = false }
}
