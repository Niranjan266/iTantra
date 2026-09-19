package com.itantra.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Several links at once, so two iTantra phones find each other on whichever one works.
 *
 * ## The problem this solves
 *
 * Every other link makes the user guess. Wi-Fi broadcast needs both phones on the same
 * network, and an access point may refuse to forward broadcast between clients. BLE
 * broadcast needs no network, but carries 24 bytes — a codebook phrase and nothing else.
 * Two people who pick different options, or the right option on the wrong network, see
 * "looking for other iTantra phones" for ever and conclude the app is broken.
 *
 * Running both removes the guess:
 *
 *  - **Sending** goes out on every link that is up. A frame too large for BLE is refused
 *    by BLE and still carried by Wi-Fi, so free speech is never lost to the smaller link.
 *  - **Receiving** listens on all of them, so a phone is found on whichever link reaches it.
 *  - **Duplicates** — the same message heard on both links — are removed by the
 *    [FloodRelay] above this, by `(sessionId, seq)`, exactly as it already removes the
 *    same message heard from two neighbours. Nothing new was needed for that.
 *
 * Connected if **any** link is up; failed only if **all** have failed. One radio being
 * unavailable (no Wi-Fi network, Bluetooth off) degrades the reach, not the app.
 */
class CompositeTransport(
    private val links: List<Transport>,
    scope: CoroutineScope,
) : Transport {

    init {
        require(links.isNotEmpty()) { "a composite needs at least one link" }
    }

    override val name: String get() = links.joinToString(" + ") { it.name }

    /** Unknown: the links differ, and whichever carries a frame sets its rate. */
    override val nominalBitrate: Int? get() = null

    override val carriesPresence: Boolean get() = links.any { it.carriesPresence }

    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + job)

    private val _state = MutableStateFlow<TransportState>(TransportState.Idle)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incoming: Flow<ByteArray> = _incoming

    /** Frames sent, per link name — for the diagnostics screen. */
    val sentBy = mutableMapOf<String, Int>()

    init {
        // Listen on every link from the start. A link that connects later is already
        // being collected, so nothing it receives before the next UI refresh is lost.
        for (link in links) {
            ownScope.launch { link.incoming.collect { _incoming.emit(it) } }
        }
        ownScope.launch {
            combine(links.map { it.state }) { states -> merge(states.toList()) }
                .collect { _state.value = it }
        }
    }

    /** Connect every link at once; none waits for another, and none can fail the rest. */
    override suspend fun connect() {
        _state.value = TransportState.Connecting
        coroutineScope {
            links.map { link -> async { runCatching { link.connect() } } }.awaitAll()
        }
    }

    /**
     * Send on every connected link. True if at least one carried it.
     *
     * A link that refuses (BLE with a frame over 24 bytes) or is down costs nothing and
     * does not make the send fail — that is the point of having more than one.
     */
    override suspend fun send(frame: ByteArray): Boolean {
        var any = false
        for (link in links) {
            if (!link.state.value.isConnected) continue
            val ok = runCatching { link.send(frame) }.getOrDefault(false)
            if (ok) {
                any = true
                synchronized(sentBy) { sentBy[link.name] = (sentBy[link.name] ?: 0) + 1 }
            }
        }
        return any
    }

    override suspend fun close() {
        job.cancel()
        for (link in links) runCatching { link.close() }
        _state.value = TransportState.Closed
    }

    companion object {
        /**
         * One state from several.
         *
         * Names the links that are up, so "Connected to everyone on this Wi-Fi + nearby
         * phones over Bluetooth" says exactly who can hear — and a missing one is visible
         * rather than silently absent.
         */
        internal fun merge(states: List<TransportState>): TransportState {
            val up = states.filterIsInstance<TransportState.Connected>()
            if (up.isNotEmpty()) return TransportState.Connected(up.joinToString(" + ") { it.peer })
            if (states.any { it == TransportState.Connecting }) return TransportState.Connecting
            val failed = states.filterIsInstance<TransportState.Failed>()
            if (failed.size == states.size) {
                return TransportState.Failed(failed.joinToString("; ") { it.reason })
            }
            if (states.all { it == TransportState.Closed }) return TransportState.Closed
            return TransportState.Idle
        }
    }
}
