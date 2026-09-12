package com.itantra.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.itantra.codec.FrameReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth RFCOMM transport — two phones, no network of any kind (PRD F-13).
 *
 * One device hosts and the other joins. RFCOMM gives a reliable ordered byte stream,
 * but no message boundaries, so every read goes through [FrameReader] to be cut back
 * into frames (see that class for why this is not as simple as it sounds).
 *
 * Nothing above [Transport] knows this class exists. Swapping to Wi-Fi Direct in
 * Phase 6, or wrapping this in the bandwidth throttle, changes only which object is
 * constructed (TRD section 1.1).
 *
 * The two devices must already be paired in Android's Bluetooth settings. Pairing is a
 * one-time operation the user performs in the OS, and in a real deployment it would be
 * done when the equipment is provisioned, not in the field.
 */
class BluetoothRfcommTransport(
    context: Context,
    private val scope: CoroutineScope,
    private val role: Role,
) : Transport {

    sealed class Role {
        /** Listen for an incoming connection. */
        object Host : Role()

        /** Connect out to an already-paired device. */
        data class Join(val address: String) : Role()
    }

    companion object {
        private const val TAG = "iTantra.BT"
        private const val SERVICE_NAME = "iTantra"

        /**
         * Fixed service UUID. Both ends must agree, so this is as frozen as the wire
         * format — changing it means an old build cannot talk to a new one.
         */
        val SERVICE_UUID: UUID = UUID.fromString("3f1a7c20-9b4e-4f6a-8d21-1c9e5b7a0d13")

        /**
         * RFCOMM's practical MTU is well above any frame we send: the largest possible
         * ITP-1 frame is 300 bytes. Reading in 1 kB chunks means a whole utterance
         * almost always arrives in a single read.
         */
        private const val READ_BUFFER = 1024
    }

    override val name: String = when (role) {
        is Role.Host -> "Bluetooth (host)"
        is Role.Join -> "Bluetooth"
    }

    /** RFCOMM in practice runs far above this; the honest answer is "not a bottleneck". */
    override val nominalBitrate: Int? = null

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _state = MutableStateFlow<TransportState>(TransportState.Idle)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

    private val frameReader = FrameReader()
    private val writeLock = Mutex()

    private var serverSocket: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private var input: InputStream? = null
    private var worker: Job? = null

    @Volatile
    private var closed = false

    @SuppressLint("MissingPermission") // Caller checks BLUETOOTH_CONNECT before constructing.
    override suspend fun connect() {
        val bt = adapter
        if (bt == null) {
            _state.value = TransportState.Failed("This device has no Bluetooth")
            return
        }
        if (!bt.isEnabled) {
            _state.value = TransportState.Failed("Bluetooth is switched off")
            return
        }

        closed = false
        _state.value = TransportState.Connecting

        worker = scope.launch(Dispatchers.IO) {
            try {
                val connected = when (role) {
                    is Role.Host -> acceptIncoming(bt)
                    is Role.Join -> connectOutward(bt, role.address)
                }
                if (connected == null) return@launch

                socket = connected
                input = connected.inputStream
                output = connected.outputStream
                frameReader.reset()

                val peer = peerLabel(connected.remoteDevice)
                _state.value = TransportState.Connected(peer)
                Log.i(TAG, "connected to $peer as $role")

                readLoop(connected.inputStream)
            } catch (e: IOException) {
                if (!closed) {
                    _state.value = TransportState.Failed(e.message ?: "connection failed")
                    Log.w(TAG, "transport failed", e)
                }
            } finally {
                cleanup()
                if (!closed && _state.value !is TransportState.Failed) {
                    _state.value = TransportState.Closed
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun acceptIncoming(bt: BluetoothAdapter): BluetoothSocket? {
        val server = bt.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
        serverSocket = server
        return try {
            // Blocks until the other phone joins, or until close() aborts it.
            server.accept()
        } catch (e: IOException) {
            if (closed) null else throw e
        } finally {
            // Point-to-point only: stop listening once someone is connected.
            runCatching { server.close() }
            serverSocket = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectOutward(bt: BluetoothAdapter, address: String): BluetoothSocket? {
        val device: BluetoothDevice = try {
            bt.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            _state.value = TransportState.Failed("Not a valid Bluetooth address")
            return null
        }
        // Discovery is expensive and will slow or break the connect attempt.
        runCatching { bt.cancelDiscovery() }

        val s = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
        s.connect()
        return s
    }

    private suspend fun readLoop(stream: InputStream) {
        val buf = ByteArray(READ_BUFFER)
        while (!closed) {
            val n = try {
                stream.read(buf)
            } catch (e: IOException) {
                if (!closed) Log.w(TAG, "read failed", e)
                -1
            }
            if (n <= 0) break

            // Cut the stream back into frames before anything upstream sees it.
            for (frame in frameReader.append(buf, n)) {
                _incoming.tryEmit(frame)
            }
        }
        if (!closed) {
            _state.value = TransportState.Failed("Peer disconnected")
        }
    }

    override suspend fun send(frame: ByteArray): Boolean {
        val out = output ?: return false
        return withContext(Dispatchers.IO) {
            writeLock.withLock {
                try {
                    out.write(frame)
                    out.flush()
                    true
                } catch (e: IOException) {
                    Log.w(TAG, "write failed", e)
                    _state.value = TransportState.Failed("Send failed: ${e.message}")
                    false
                }
            }
        }
    }

    override suspend fun close() {
        closed = true
        // Closing the sockets is what unblocks accept() and read().
        cleanup()
        worker?.cancel()
        worker = null
        _state.value = TransportState.Closed
    }

    private fun cleanup() {
        runCatching { serverSocket?.close() }
        runCatching { socket?.close() }
        serverSocket = null
        socket = null
        input = null
        output = null
    }

    @SuppressLint("MissingPermission")
    private fun peerLabel(device: BluetoothDevice): String =
        runCatching { device.name }.getOrNull() ?: device.address

    /** Bytes discarded while resynchronising after corruption. For the metrics HUD. */
    val resyncBytes: Int get() = frameReader.resyncBytes
}
