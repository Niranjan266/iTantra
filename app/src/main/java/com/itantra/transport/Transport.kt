package com.itantra.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The seam of the entire system (TRD section 1.1).
 *
 * Everything above this interface deals in speech. Everything below it deals in bytes.
 * Neither side knows anything about the other. Swapping Bluetooth for Wi-Fi Direct, or
 * wrapping either in the bandwidth throttle used for the narrow-link demonstration, is
 * a change to one object and nothing else — that is the whole reason this exists.
 *
 * This project is software-only (PRD section 5.1). The interface would also accommodate
 * a bearer that reaches an external radio, but no such implementation is planned or
 * built, and nothing here depends on one.
 *
 * Implementations must be safe to call from any thread and must never block the caller
 * of [send].
 */
interface Transport {

    /** Short name for the UI and for the metrics CSV. */
    val name: String

    /** Nominal capacity in bits per second, or null if unknown/unlimited. */
    val nominalBitrate: Int?

    val state: StateFlow<TransportState>

    /** Frames received from the far end. One emission per frame. */
    val incoming: Flow<ByteArray>

    /** Begin connecting. Returns once the attempt has started, not once connected. */
    suspend fun connect()

    /**
     * Queue a frame for transmission. Must not block.
     * @return true if the frame was accepted for sending.
     */
    suspend fun send(frame: ByteArray): Boolean

    suspend fun close()
}

sealed class TransportState {
    object Idle : TransportState()
    object Connecting : TransportState()
    data class Connected(val peer: String) : TransportState()
    data class Failed(val reason: String) : TransportState()
    object Closed : TransportState()

    val isConnected: Boolean get() = this is Connected

    fun describe(): String = when (this) {
        Idle -> "Not connected"
        Connecting -> "Connecting…"
        is Connected -> "Connected to $peer"
        is Failed -> "Failed: $reason"
        Closed -> "Closed"
    }
}
