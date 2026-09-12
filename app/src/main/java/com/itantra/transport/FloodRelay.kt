package com.itantra.transport

import com.itantra.codec.PacketCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Makes every phone a repeater, so a message travels further than any one radio reaches.
 *
 * This is the software answer to "how do I reach the next village". A single Bluetooth or
 * Wi-Fi Direct link is tens to low hundreds of metres, and no amount of protocol work
 * changes that — it is transmit power and physics. What *can* change is how many links a
 * message crosses. Ten phones spread along a road relay a distress call the length of that
 * road, with no tower, no router and no hardware.
 *
 * Wrapped around any [Transport], exactly like [ThrottleWrapper], so the relay and the
 * bearer know nothing about each other and either can be swapped alone.
 *
 * ## The three things that make flooding work rather than collapse
 *
 *  1. **Duplicate suppression.** Every node hears the same message from several
 *     neighbours. Without a seen-set, each copy triggers another rebroadcast and the
 *     channel is saturated within seconds — the classic broadcast storm. The key is
 *     `(sessionId, seq)`, which the ITP-1 header already carries for precisely this
 *     purpose.
 *  2. **A hop limit.** See [MeshFrame]. Without it a message loops between two nodes for
 *     ever, because a seen-set that is bounded in time will eventually forget it.
 *  3. **Random delay before rebroadcasting.** Easy to leave out and fatal. Every node that
 *     hears a message hears it at the same instant, so without jitter they all retransmit
 *     simultaneously and collide, and the more nodes there are the less the mesh carries.
 *
 * ## What this is not
 *
 * It is not routing. Nothing here knows a topology or picks a path; a message goes
 * everywhere, which is wasteful of airtime and exactly right for a distress call whose
 * destination is "anyone at all". It offers no delivery guarantee and no acknowledgement:
 * on a marginal link the answer to reliability is repetition ([RepeatSender]), not retries
 * that need a return path the sender may not have.
 */
class FloodRelay(
    private val inner: Transport,
    private val scope: CoroutineScope,
    /** Hops a message sent from this device may make. */
    private val ttl: Int = MeshFrame.DEFAULT_TTL,
    /**
     * How long a message id is remembered.
     *
     * Must outlast the time for a message to cross the mesh and come back, or a late copy
     * looks new and restarts the flood. Must also be short enough that `seq` wrapping
     * after 256 messages cannot collide with a still-remembered id.
     */
    private val rememberMs: Long = 60_000,
    /** Bounded so a long session or a noisy channel cannot grow this without limit. */
    private val maxRemembered: Int = 512,
    private val jitter: JitterPolicy = JitterPolicy.Default,
    /** Injected so tests can run without a clock. */
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : Transport {

    /**
     * How long a node waits before repeating what it just heard.
     *
     * A policy object rather than two numbers because it is the part most likely to need
     * tuning against a real crowd of phones, and the part a test must be able to pin.
     */
    interface JitterPolicy {
        /** Milliseconds to wait before rebroadcasting. */
        fun delayMs(): Long

        object Default : JitterPolicy {
            override fun delayMs(): Long = Random.nextLong(20, 200)
        }

        /** No delay. For tests only — in a real mesh this causes collisions. */
        object None : JitterPolicy {
            override fun delayMs(): Long = 0
        }
    }

    override val name: String get() = "${inner.name} + mesh(ttl $ttl)"

    override val nominalBitrate: Int? get() = inner.nominalBitrate

    override val state: StateFlow<TransportState> get() = inner.state

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    /** Only messages never seen before, and unwrapped back to plain ITP-1 frames. */
    override val incoming: Flow<ByteArray> = _incoming

    /** Insertion-ordered so the oldest id is the one evicted when the cache is full. */
    private val seen = LinkedHashMap<Int, Long>()

    private val lock = Any()

    /** Counters for the technical view — a mesh is unobservable without them. */
    @Volatile var relayedCount: Int = 0
        private set

    @Volatile var suppressedCount: Int = 0
        private set

    @Volatile var expiredCount: Int = 0
        private set

    /**
     * Owns every coroutine this relay starts, so [close] can stop all of them.
     *
     * A child of the caller's scope, so cancelling the caller still cancels us; a
     * [SupervisorJob] so one failed rebroadcast cannot tear down the collector.
     *
     * Without this the relay collects for ever with no way to stop. Switching transport
     * would leave the old relay listening and every frame would be handled once per
     * relay ever created — the same leak [com.itantra.session.SessionController] already
     * guards against when it swaps transports, and the reason it tracks its own jobs.
     */
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val relayScope = CoroutineScope(scope.coroutineContext + job)

    init {
        relayScope.launch {
            inner.incoming.collect { wrapped -> onWrapped(wrapped) }
        }
    }

    override suspend fun connect() = inner.connect()

    /**
     * Send a message of our own, and remember it.
     *
     * Remembering our own traffic is what stops an echo: on a broadcast medium we hear
     * our own advertisement, and without this entry we would treat it as a neighbour's
     * message and relay it.
     */
    override suspend fun send(frame: ByteArray): Boolean {
        idOf(frame)?.let { remember(it) }
        return inner.send(MeshFrame.wrap(frame, ttl))
    }

    /** Stops relaying, then closes the bearer. Safe to call more than once. */
    override suspend fun close() {
        job.cancel()
        inner.close()
    }

    private suspend fun onWrapped(wrapped: ByteArray) {
        val frame = MeshFrame.unwrap(wrapped) ?: return

        // Reject anything that is not really ours before spending a cache entry on it.
        // A BLE scan hears every beacon in range, and most of them are shop tags.
        val id = idOf(frame) ?: return

        if (!remember(id)) {
            // Heard this already, from another neighbour. Dropping it here is what keeps
            // the flood from multiplying.
            suppressedCount++
            return
        }

        // Deliver upward first. A relay is still a recipient, and a distress message must
        // reach the person holding this phone whether or not it travels any further.
        _incoming.emit(frame)

        val next = MeshFrame.decrement(wrapped)
        if (next == null) {
            expiredCount++
            return
        }

        // Rebroadcast on the scope, not inline: waiting out the jitter here would stall
        // the collector and delay every following frame behind this one.
        relayScope.launch {
            delay(jitter.delayMs())
            if (inner.send(next)) relayedCount++
        }
    }

    /**
     * The dedup key: sender plus sequence number, from the ITP-1 header.
     *
     * Returns null for anything that is not a valid packet, so junk off the air never
     * occupies the cache. Uses the real decoder rather than reading bytes by offset, so
     * the header CRC is checked — a corrupted id would otherwise poison the cache and
     * suppress a genuine later message.
     */
    private fun idOf(frame: ByteArray): Int? {
        val packet = PacketCodec.decode(frame).packetOrNull() ?: return null
        return (packet.sessionId shl 8) or packet.seq
    }

    /** True if this id is new. Also evicts expired and excess entries. */
    private fun remember(id: Int): Boolean = synchronized(lock) {
        val now = nowMs()

        val cutoff = now - rememberMs
        val stale = seen.entries.asSequence().takeWhile { it.value < cutoff }.map { it.key }.toList()
        for (k in stale) seen.remove(k)

        if (seen.containsKey(id)) return@synchronized false

        seen[id] = now
        while (seen.size > maxRemembered) {
            val oldest = seen.keys.firstOrNull() ?: break
            seen.remove(oldest)
        }
        true
    }
}
