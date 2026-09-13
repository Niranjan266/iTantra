package com.itantra.transport

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import com.itantra.codec.FrameReader
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
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Wi-Fi Direct: two phones form their own network, with no router and no internet.
 *
 * This follows the architecture the team's other prototype uses, and for the reason that
 * prototype chose it. Android negotiates a **group owner** when the group forms; that
 * device runs a TCP server and the other connects to it. Nobody has to decide who hosts.
 *
 * ## Why this exists when there is already a Bluetooth link
 *
 * Bluetooth RFCOMM needs one phone to listen and the other to dial, and the app cannot
 * tell which the user meant. Pick wrongly on both and neither is listening, which fails as
 *
 *     java.io.IOException: read failed, socket might closed or timeout, read ret: -1
 *
 * — a message that says nothing about the actual cause. That is the failure this removes:
 * group ownership is negotiated by the OS, so the roles cannot both be wrong.
 *
 * It also carries **any message size**. A legacy BLE advertisement holds 24 bytes, which is
 * a codebook phrase and nothing else; spoken free speech is around 108 bytes and was simply
 * refused. Over TCP there is no such ceiling, so anything a person says travels.
 *
 * ## Framing
 *
 * TCP is a stream with no message boundaries, exactly like RFCOMM, so the same
 * [FrameReader] cuts it back into packets — resynchronising on the ITP-1 magic byte and
 * validating the header checksum. Nothing new was needed for this bearer, which is the
 * benefit of having put framing below the transport rather than inside one.
 */
class WifiDirectTransport(
    private val context: Context,
    private val scope: CoroutineScope,
) : Transport {

    companion object {
        private const val TAG = "iTantra.WifiDirect"

        /** The port the team's other prototype uses. Kept identical so the two interoperate. */
        const val TCP_PORT = 8765

        private const val CONNECT_TIMEOUT_MS = 8_000

        /** WPS primary device category for a telephone. */
        private const val WPS_TELEPHONE = 10

        /** Categories that are certainly not a phone: display, printer, storage, camera. */
        private val NOT_A_PHONE = setOf(7, 3, 5, 4)
    }

    override val name: String get() = "Wi-Fi Direct"

    /** Real-world Wi-Fi Direct throughput varies far too much to state as one number. */
    override val nominalBitrate: Int? get() = null

    private val _state = MutableStateFlow<TransportState>(TransportState.Idle)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incoming: Flow<ByteArray> = _incoming

    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + job)

    private val frameReader = FrameReader()
    private val writeLock = Mutex()

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var out: OutputStream? = null

    @Volatile var isGroupOwner: Boolean = false
        private set

    @SuppressLint("MissingPermission")
    override suspend fun connect() {
        val m = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (m == null) {
            _state.value = TransportState.Failed("This device has no Wi-Fi Direct")
            return
        }
        manager = m
        val ch = m.initialize(context, context.mainLooper, null)
        channel = ch

        _state.value = TransportState.Connecting

        // Both broadcasts matter, and missing either one leaves this silently stuck.
        //
        // PEERS_CHANGED is the one that is easy to omit: discovery takes seconds, so the
        // peer list is EMPTY at the moment discoverPeers() returns. Asking once — which is
        // what the first version of this did — finds nothing and never looks again, and
        // the log reads "no peers yet" for ever while the other phone is sitting right
        // there advertising itself.
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> tryConnectToAPeer(m, ch)
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION ->
                        m.requestConnectionInfo(ch) { info -> onGroupFormed(info) }
                }
            }
        }
        context.registerReceiver(receiver, filter)

        // Discovery, then connect to the first peer found.
        //
        // Deliberately simple: in the field there are two phones and a person who wants to
        // talk, not a list to browse. A peer picker belongs on the Devices screen, and the
        // radar there can call connectTo() once it exists.
        runCatching {
            m.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { Log.i(TAG, "discovering peers") }
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "discoverPeers failed: $reason")
                    _state.value = TransportState.Failed("Could not search for phones ($reason)")
                }
            })
        }.onFailure {
            Log.e(TAG, "Wi-Fi Direct setup failed", it)
            _state.value = TransportState.Failed("Wi-Fi Direct unavailable: ${it.message}")
        }
    }

    /**
     * Connect to the first peer discovery has turned up.
     *
     * Called on every peer-change broadcast rather than once, and guarded so the repeated
     * broadcasts Android sends do not start a second connection on top of the first.
     *
     * Picking the first peer is deliberate for two phones in a field. A chooser belongs on
     * the Devices screen, and can call into here when it exists.
     */
    @SuppressLint("MissingPermission")
    private fun tryConnectToAPeer(m: WifiP2pManager, ch: WifiP2pManager.Channel) {
        if (connecting || socket != null || server != null) return
        m.requestPeers(ch) { peers ->
            val peer = pickPeer(peers.deviceList.toList()) ?: run {
                Log.i(TAG, "peers changed; none of ${peers.deviceList.size} look like a phone")
                return@requestPeers
            }
            if (connecting) return@requestPeers
            connecting = true
            Log.i(TAG, "found ${peer.deviceName} (${peer.primaryDeviceType}); connecting")
            val config = WifiP2pConfig().apply { deviceAddress = peer.deviceAddress }
            m.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { Log.i(TAG, "connect() accepted") }
                override fun onFailure(reason: Int) {
                    connecting = false
                    Log.w(TAG, "connect failed: $reason")
                    _state.value = TransportState.Failed("Could not connect ($reason)")
                }
            })
        }
    }

    /** Guards against the repeated broadcasts Android sends while a group forms. */
    @Volatile private var connecting = false

    /**
     * Choose a peer that is plausibly another phone.
     *
     * The first version took whatever came first, and in a real room that was a **Samsung
     * television**: Wi-Fi Direct is in TVs, printers, speakers and cameras, and a TV
     * advertises itself long before a phone the user just switched on. Connecting to it
     * succeeds, forms a group, and then nothing ever arrives.
     *
     * `primaryDeviceType` is a WPS category string of the form `category-OUI-subcategory`.
     * Category 10 is Telephone; 7 is Display, 3 is Printer, 6 is Storage. Filtering to
     * telephones is not a guarantee — a device may report the wrong category — so an
     * unknown-category peer is still allowed, and only the categories that are definitely
     * not phones are refused.
     */
    private fun pickPeer(devices: List<android.net.wifi.p2p.WifiP2pDevice>): android.net.wifi.p2p.WifiP2pDevice? {
        if (devices.isEmpty()) return null
        fun category(d: android.net.wifi.p2p.WifiP2pDevice) =
            d.primaryDeviceType?.substringBefore('-')?.toIntOrNull()

        val phones = devices.filter { category(it) == WPS_TELEPHONE }
        if (phones.isNotEmpty()) return phones.first()

        // Nothing declared itself a phone. Take anything that is not obviously an
        // appliance rather than refusing outright, because category reporting is patchy.
        val notAppliances = devices.filter { category(it) !in NOT_A_PHONE }
        if (notAppliances.isNotEmpty()) return notAppliances.first()

        Log.i(TAG, "only appliances in range: " +
            devices.joinToString { "${it.deviceName}(${it.primaryDeviceType})" })
        return null
    }

    /**
     * The group exists. Open the socket for whichever end this device turned out to be.
     *
     * The OS decides group ownership, which is the whole point: neither user chooses, so
     * they cannot both choose the same thing.
     */
    private fun onGroupFormed(info: WifiP2pInfo) {
        if (!info.groupFormed) return
        if (socket != null || server != null) return // already wired up

        isGroupOwner = info.isGroupOwner
        val host = info.groupOwnerAddress?.hostAddress

        ownScope.launch(Dispatchers.IO) {
            runCatching {
                if (info.isGroupOwner) {
                    Log.i(TAG, "group owner — listening on $TCP_PORT")
                    val s = ServerSocket(TCP_PORT).also { server = it }
                    val accepted = s.accept()
                    attach(accepted, accepted.inetAddress?.hostAddress ?: "peer")
                } else {
                    Log.i(TAG, "client — connecting to $host:$TCP_PORT")
                    // The group owner's server may not be listening the instant the group
                    // forms, so a single attempt loses the race more often than not.
                    var attached = false
                    repeat(10) {
                        if (attached || !ownScope.isActive) return@repeat
                        runCatching {
                            val s = Socket()
                            s.connect(InetSocketAddress(host, TCP_PORT), CONNECT_TIMEOUT_MS)
                            attach(s, host ?: "group owner")
                            attached = true
                        }.onFailure { delay(700) }
                    }
                    if (!attached) error("could not reach the group owner")
                }
            }.onFailure {
                Log.e(TAG, "socket setup failed", it)
                _state.value = TransportState.Failed("Could not open the link: ${it.message}")
            }
        }
    }

    private suspend fun attach(s: Socket, peer: String) {
        socket = s
        // Nagle off: these are tiny packets sent rarely, and waiting to coalesce them
        // adds latency to a distress message for no bandwidth saving worth having.
        runCatching { s.tcpNoDelay = true }
        out = s.getOutputStream()
        _state.value = TransportState.Connected(peer)
        Log.i(TAG, "connected to $peer (group owner = $isGroupOwner)")
        readLoop(s.getInputStream())
    }

    private suspend fun readLoop(input: InputStream) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(4096)
        while (ownScope.isActive) {
            val n = runCatching { input.read(buffer) }.getOrElse { -1 }
            if (n < 0) break
            if (n == 0) continue
            // Same framing as the Bluetooth bearer: a stream carries no message
            // boundaries, so frames are cut out by magic byte and header checksum.
            frameReader.append(buffer, n).forEach { _incoming.emit(it) }
        }
        Log.i(TAG, "read loop ended")
        _state.value = TransportState.Closed
    }

    override suspend fun send(frame: ByteArray): Boolean {
        val o = out ?: return false
        return writeLock.withLock {
            runCatching {
                withContext(Dispatchers.IO) {
                    o.write(frame)
                    o.flush()
                }
                // Log the success, not only the failure. A transport that says nothing
                // when it works forces every question about delivery to be answered from
                // the receiving end — which is how the BLE service-UUID bug stayed hidden
                // for as long as it did.
                Log.i(TAG, "sent ${frame.size} B")
                true
            }.getOrElse {
                Log.e(TAG, "send failed", it)
                false
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun close() {
        job.cancel()
        runCatching { receiver?.let { context.unregisterReceiver(it) } }
        receiver = null
        runCatching { socket?.close() }
        runCatching { server?.close() }
        socket = null
        server = null
        out = null
        runCatching {
            val m = manager
            val ch = channel
            if (m != null && ch != null) m.removeGroup(ch, null)
        }
        _state.value = TransportState.Closed
    }
}
