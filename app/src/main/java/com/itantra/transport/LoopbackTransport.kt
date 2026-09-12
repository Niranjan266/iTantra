package com.itantra.transport

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sends every frame straight back into the receive path on the same device.
 *
 * This is not a toy. It is the reason the project can be built in a sensible order:
 * the complete pipeline — capture, recognition, encoding, decoding, synthesis,
 * playback — can be developed and measured on ONE phone, with no pairing, no second
 * device, and no radio flakiness sitting in the way of every test.
 *
 * Bluetooth then becomes a swap of one object rather than a prerequisite for daily
 * work. It also stays in the shipped build as the demo fallback if pairing fails on
 * stage (PRD section 11).
 */
class LoopbackTransport : Transport {

    override val name = "Loopback"
    override val nominalBitrate: Int? = null

    private val _state = MutableStateFlow<TransportState>(TransportState.Idle)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

    override suspend fun connect() {
        _state.value = TransportState.Connected("this device")
    }

    override suspend fun send(frame: ByteArray): Boolean {
        if (!_state.value.isConnected) return false
        // Copy, so the receive path can never observe a buffer the sender still owns.
        return _incoming.tryEmit(frame.copyOf())
    }

    override suspend fun close() {
        _state.value = TransportState.Closed
    }
}
