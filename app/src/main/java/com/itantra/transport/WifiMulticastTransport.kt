package com.itantra.transport

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * Everyone on the same Wi-Fi hears everyone, over UDP multicast.
 *
 * ## Why this exists alongside the BLE mesh
 *
 * BLE broadcast reaches phones with no network of any kind, which is the harder and more
 * important case — but a legacy advertisement carries **24 usable bytes**. That is a
 * codebook phrase and nothing else: a spelled-out sentence is around 108 bytes and is
 * refused. This bearer's datagrams carry ~1400, so **free speech travels** as well as
 * codebook phrases.
 *
 * It is one-to-many by nature. A datagram sent to the group address is delivered to every
 * phone that has joined it — no pairing, no group owner, no connection, and no list of
 * peers to maintain. Whoever is running the app and listening, hears it.
 *
 * ## What "offline" means here, precisely
 *
 * This needs a Wi-Fi network, but **not the internet**. A phone's own hotspot with no SIM,
 * a router with its uplink unplugged, or any local network works identically. Nothing here
 * resolves a hostname, contacts a server, or leaves the subnet: the destination is a
 * multicast group address, which routers do not forward off-link by default and which this
 * code never asks to be forwarded.
 *
 * It does, however, mean the app must now declare the `INTERNET` permission, because
 * Android requires it for **any** socket including a purely local one. That weakens a
 * claim the project used to be able to make structurally, and the honest version is now:
 * no server, no account, no DNS lookup, and no hostname anywhere in the source — rather
 * than "it is impossible". The BLE bearer remains available for when that distinction
 * matters, and needs no such permission.
 *
 * ## The multicast lock
 *
 * Android drops multicast and broadcast packets in the Wi-Fi driver to save power unless a
 * [WifiManager.MulticastLock] is held. Without it, sending appears to work perfectly and
 * **nothing is ever received** — a failure that looks like a network problem rather than a
 * missing lock.
 */
class WifiMulticastTransport(
    private val context: Context,
    private val scope: CoroutineScope,
) : Transport {

    companion object {
        private const val TAG = "iTantra.Wifi"

        /**
         * Group address and port.
         *
         * 239.x.x.x is the administratively-scoped block — reserved for private use and
         * not routed onto the wider internet, which is exactly the property wanted here.
         */
        private const val GROUP = "239.7.7.7"
        private const val PORT = 47707

        /**
         * Datagram ceiling.
         *
         * Well inside the usual 1500-byte Ethernet MTU so a frame is never fragmented:
         * a fragmented datagram is lost entirely if any fragment is, which on a congested
         * Wi-Fi channel is a needless way to lose a distress message.
         */
        const val MAX_DATAGRAM = 1400
    }

    override val name: String get() = "Wi-Fi broadcast"

    /**
     * Null rather than a guess.
     *
     * Wi-Fi link rates vary from a few megabits to hundreds depending on radio, distance
     * and contention. Reporting one number would put a fabricated figure into the
     * measurements CSV; the throttle wrapper is the honest way to state a bearer rate.
     */
    override val nominalBitrate: Int? get() = null

    private val _state = MutableStateFlow<TransportState>(TransportState.Idle)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incoming: Flow<ByteArray> = _incoming

    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + job)

    private var socket: MulticastSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private var group: InetAddress? = null

    /**
     * Addresses we have received from, and send a unicast copy to.
     *
     * Multicast and broadcast are both discretionary on Wi-Fi: an access point may forward
     * unicast between clients happily — proven here with ping — while dropping group and
     * broadcast traffic to save airtime. Once a peer has been heard from even once, a
     * direct copy reaches it whatever the access point thinks of multicast.
     *
     * This cannot bootstrap discovery on its own, which is why all three are sent: group,
     * broadcast, and unicast to everyone already known.
     */
    private val knownPeers = java.util.concurrent.ConcurrentHashMap<String, Long>()

    @Volatile var datagramsSent: Int = 0
        private set

    @Volatile var datagramsReceived: Int = 0
        private set

    override suspend fun connect() {
        _state.value = TransportState.Connecting

        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi == null) {
            _state.value = TransportState.Failed("This device has no Wi-Fi")
            return
        }

        runCatching {
            withContext(Dispatchers.IO) {
                // Without this the driver silently discards every inbound multicast
                // packet. See the class note — this is the single most common way this
                // transport appears broken while looking correct.
                lock = wifi.createMulticastLock("itantra").apply {
                    setReferenceCounted(true)
                    acquire()
                }

                val addr = InetAddress.getByName(GROUP)
                group = addr

                val s = MulticastSocket(PORT)
                s.reuseAddress = true
                // Required before a datagram may be sent to a broadcast address. Without
                // it every broadcast send fails — and the first version wrapped that send
                // in a runCatching that discarded the exception, so the fallback path
                // looked active while doing nothing at all.
                s.broadcast = true
                // Our own datagrams come back to us. Left ON deliberately: the relay
                // already suppresses duplicates by (sessionId, seq), and hearing our own
                // transmission is how a sender can tell the radio actually sent it.
                s.loopbackMode = false
                s.timeToLive = 1 // stay on this subnet; never routed onward

                val iface = firstWifiInterface()
                if (iface != null) {
                    s.networkInterface = iface
                    s.joinGroup(InetSocketAddress(addr, PORT), iface)
                } else {
                    @Suppress("DEPRECATION")
                    s.joinGroup(addr)
                }
                socket = s
            }
            true
        }.onFailure {
            Log.e(TAG, "could not join the group", it)
            _state.value = TransportState.Failed("Could not join the Wi-Fi group: ${it.message}")
            releaseLock()
            return
        }

        _state.value = TransportState.Connected("everyone on this Wi-Fi")
        Log.i(TAG, "joined $GROUP:$PORT")

        ownScope.launch(Dispatchers.IO) { receiveLoop() }
    }

    private suspend fun receiveLoop() {
        val s = socket ?: return
        val buffer = ByteArray(MAX_DATAGRAM)
        while (ownScope.isActive) {
            val packet = DatagramPacket(buffer, buffer.size)
            val ok = runCatching { s.receive(packet) }.isSuccess
            if (!ok) {
                // A closed socket during shutdown is normal; anything else ends the loop
                // too, and close() is what reports state.
                break
            }
            datagramsReceived++
            packet.address?.hostAddress?.let { knownPeers[it] = System.currentTimeMillis() }
            // Logged because its absence is the whole diagnosis: a phone that sends
            // happily and receives nothing is almost always the access point refusing to
            // forward multicast between wireless clients, not a fault in this code.
            Log.i(TAG, "received ${packet.length} B from ${packet.address?.hostAddress}")
            // Copy: the buffer is reused on the next iteration, so handing the array
            // straight on would let a later datagram overwrite an earlier message.
            _incoming.emit(packet.data.copyOf(packet.length))
        }
    }

    override suspend fun send(frame: ByteArray): Boolean {
        val s = socket ?: return false
        val addr = group ?: return false

        if (frame.size > MAX_DATAGRAM) {
            Log.w(TAG, "frame of ${frame.size} B exceeds the $MAX_DATAGRAM B datagram limit")
            return false
        }

        return runCatching {
            withContext(Dispatchers.IO) {
                s.send(DatagramPacket(frame, frame.size, addr, PORT))
                // Send to the subnet broadcast address as well.
                //
                // Many consumer access points drop multicast between wireless clients —
                // IGMP snooping with no querier, or client isolation — and the symptom is
                // exactly this: sending succeeds, nothing is ever received, and the code
                // looks wrong when the network is the problem. Broadcast survives some of
                // the configurations multicast does not, and costs one extra datagram.
                broadcastAddress()?.let { b ->
                    runCatching { s.send(DatagramPacket(frame, frame.size, b, PORT)) }
                        .onFailure { Log.w(TAG, "broadcast to ${b.hostAddress} failed", it) }
                }
                // And directly to anyone already heard from.
                knownPeers.keys.forEach { host ->
                    runCatching {
                        s.send(DatagramPacket(
                            frame, frame.size, InetAddress.getByName(host), PORT))
                    }.onFailure { Log.w(TAG, "unicast to $host failed: ${it.message}") }
                }
            }
            datagramsSent++
            Log.i(TAG, "sent ${frame.size} B to $GROUP:$PORT")
            true
        }.getOrElse {
            Log.e(TAG, "send failed", it)
            false
        }
    }

    override suspend fun close() {
        job.cancel()
        runCatching {
            withContext(Dispatchers.IO) {
                socket?.let { s ->
                    runCatching { group?.let { g -> @Suppress("DEPRECATION") s.leaveGroup(g) } }
                    s.close()
                }
            }
        }
        socket = null
        releaseLock()
        _state.value = TransportState.Closed
    }

    private fun releaseLock() {
        runCatching { if (lock?.isHeld == true) lock?.release() }
        lock = null
    }

    /**
     * The Wi-Fi interface, explicitly.
     *
     * Letting the OS choose picks whichever interface it likes, which on a phone with
     * mobile data active is often not the one the other phones are on. Naming it is the
     * difference between a group everyone is in and several groups of one.
     */
    /**
     * The subnet broadcast address of the Wi-Fi interface, if it has one.
     *
     * Not 255.255.255.255: Android drops that on some versions, while the interface's own
     * directed broadcast (192.168.31.255 for a /24) is delivered.
     */
    private fun broadcastAddress(): InetAddress? = runCatching {
        firstWifiInterface()?.interfaceAddresses
            ?.firstOrNull { it.broadcast != null }
            ?.broadcast
    }.getOrNull()

    private fun firstWifiInterface(): NetworkInterface? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().firstOrNull {
            it.isUp && it.supportsMulticast() && !it.isLoopback &&
                it.name.startsWith("wlan")
        }
    }.getOrNull()
}
