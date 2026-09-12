package com.itantra.transport

import com.itantra.codec.Intent
import com.itantra.codec.LanguageId
import com.itantra.codec.Packet
import com.itantra.codec.PacketCodec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two things about these tests are load-bearing and were each found the hard way.
 *
 *  1. Frames are injected with [FakeTransport.arrive], never a bare `emit`. A SharedFlow
 *     with replay 0 discards emissions while nothing is subscribed, and the relay
 *     subscribes from a coroutine started in its constructor.
 *
 *  2. Relays are built on the test scope, NOT `backgroundScope`. `advanceUntilIdle()`
 *     does not dispatch background coroutines, so a relay built there never processes a
 *     frame and every assertion silently reads zero. Each test therefore closes its
 *     relay, which is also the behaviour a real transport switch depends on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FloodRelayTest {

    // --- a transport that records what was sent and lets a test inject arrivals --------

    private class FakeTransport : Transport {
        override val name = "fake"
        override val nominalBitrate: Int? = null
        override val state = MutableStateFlow<TransportState>(TransportState.Idle)
        val arrivals = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
        override val incoming: Flow<ByteArray> get() = arrivals

        /**
         * Deliver a frame, waiting until something is actually listening.
         *
         * A SharedFlow with replay 0 DROPS emissions while it has no subscriber, and the
         * relay subscribes from a coroutine launched in its constructor. Emitting straight
         * after construction therefore loses the frame and every assertion reads zero —
         * which is exactly how this suite first failed, in fifteen tests at once.
         */
        suspend fun arrive(frame: ByteArray) {
            arrivals.subscriptionCount.first { it > 0 }
            arrivals.emit(frame)
        }
        val sent = mutableListOf<ByteArray>()
        var accept = true

        override suspend fun connect() {}
        override suspend fun send(frame: ByteArray): Boolean {
            sent += frame.copyOf()
            return accept
        }
        override suspend fun close() {}
    }

    private fun frame(sessionId: Int, seq: Int, phraseId: Int = 33): ByteArray =
        PacketCodec.encode(
            Packet(
                sessionId = sessionId,
                seq = seq,
                langId = LanguageId.ENGLISH,
                intent = Intent.DISTRESS,
                payload = com.itantra.codec.Symbols.encodePhraseRef(phraseId),
            )
        )

    // --- framing ----------------------------------------------------------------------

    @Test
    fun wrapAndUnwrapRoundTrip() {
        val f = frame(1, 0)
        val wrapped = MeshFrame.wrap(f, ttl = 5)
        assertEquals(f.size + 1, wrapped.size)
        assertEquals(5, MeshFrame.ttlOf(wrapped))
        assertTrue(f.contentEquals(MeshFrame.unwrap(wrapped)!!))
    }

    @Test
    fun decrementStopsAtZero() {
        val wrapped = MeshFrame.wrap(frame(1, 0), ttl = 1)
        val once = MeshFrame.decrement(wrapped)!!
        assertEquals(0, MeshFrame.ttlOf(once))
        // At zero it must return null, not clamp: a caller that ignored a number could
        // rebroadcast a dead frame for ever.
        assertNull(MeshFrame.decrement(once))
    }

    @Test
    fun unwrapRejectsRubbishOffTheAir() {
        // A BLE scan hears every beacon in range.
        assertNull(MeshFrame.unwrap(ByteArray(0)))
        assertNull(MeshFrame.unwrap(byteArrayOf(8)))
        assertNull(MeshFrame.unwrap(byteArrayOf(8, 1, 2, 3)))
    }

    @Test
    fun aPhrasePacketFitsOneLegacyBleAdvertisement() {
        // The constraint the whole connectionless design rests on: 16-byte packet + 1
        // byte of TTL against 24 usable bytes in a legacy advertisement.
        val wrapped = MeshFrame.wrap(frame(1, 0))
        assertEquals(17, wrapped.size)
        assertTrue("must fit a legacy advertisement", MeshFrame.fits(wrapped))
    }

    @Test
    fun aSpelledOutMessageDoesNotFitOneLegacyAdvertisement() {
        // Stated as a test because it is the honest limit of the broadcast path, and the
        // reason the codebook is load-bearing rather than an optimisation.
        val text = PacketCodec.encode(
            Packet(
                sessionId = 1, seq = 0, langId = LanguageId.ENGLISH, intent = Intent.ROUTINE,
                payload = "Flood water is rising near the school, send boats"
                    .toByteArray(Charsets.UTF_8),
                textMode = true,
            )
        )
        assertTrue(!MeshFrame.fits(MeshFrame.wrap(text)))
    }

    // --- relaying ---------------------------------------------------------------------

    @Test
    fun relaysANewMessageWithTtlReducedByOne() = runTest {
        val inner = FakeTransport()
        val relay = FloodRelay(inner, this, ttl = 8, jitter = FloodRelay.JitterPolicy.None)

        inner.arrive(MeshFrame.wrap(frame(7, 0), ttl = 8))
        advanceUntilIdle()

        assertEquals(1, inner.sent.size)
        assertEquals(7, MeshFrame.ttlOf(inner.sent[0]))
        assertEquals(1, relay.relayedCount)

        // Stops the relay's collector; runTest would otherwise
        // wait on it for ever.
        relay.close()
    }

    @Test
    fun deliversUpwardAsAPlainPacketFrame() = runTest {
        val inner = FakeTransport()
        val relay = FloodRelay(inner, this, jitter = FloodRelay.JitterPolicy.None)
        val received = mutableListOf<ByteArray>()
        val job = launch { relay.incoming.collect { received += it } }

        val original = frame(7, 0)
        inner.arrive(MeshFrame.wrap(original, ttl = 4))
        advanceUntilIdle()

        assertEquals(1, received.size)
        assertTrue("the TTL byte must be stripped", original.contentEquals(received[0]))
        job.cancel()

        // Stops the relay's collector; runTest would otherwise
        // wait on it for ever.
        relay.close()
    }

    @Test
    fun aRelayIsAlsoARecipient() = runTest {
        // A message must reach the person holding this phone whether or not it travels on.
        val inner = FakeTransport()
        val relay = FloodRelay(inner, this, ttl = 1, jitter = FloodRelay.JitterPolicy.None)
        val received = mutableListOf<ByteArray>()
        val job = launch { relay.incoming.collect { received += it } }

        // TTL 0: must NOT be relayed, must still be delivered.
        inner.arrive(MeshFrame.wrap(frame(7, 0), ttl = 0))
        advanceUntilIdle()

        assertEquals("delivered", 1, received.size)
        assertEquals("not relayed", 0, inner.sent.size)
        assertEquals(1, relay.expiredCount)
        job.cancel()

        // Stops the relay's collector; runTest would otherwise
        // wait on it for ever.
        relay.close()
    }

    @Test
    fun suppressesTheSameMessageHeardFromSeveralNeighbours() = runTest {
        // Without this, each copy triggers another rebroadcast and the channel saturates
        // within seconds. This is the broadcast storm.
        val inner = FakeTransport()
        val relay = FloodRelay(inner, this, jitter = FloodRelay.JitterPolicy.None)
        val received = mutableListOf<ByteArray>()
        val job = launch { relay.incoming.collect { received += it } }

        repeat(5) { inner.arrive(MeshFrame.wrap(frame(7, 0), ttl = 6)) }
        advanceUntilIdle()

        assertEquals("relayed once only", 1, inner.sent.size)
        assertEquals("spoken once only", 1, received.size)
        assertEquals(4, relay.suppressedCount)
        job.cancel()

        // Stops the relay's collector; runTest would otherwise
        // wait on it for ever.
        relay.close()
    }

    @Test
    fun differentMessagesFromTheSameSenderAreBothRelayed() = runTest {
        val inner = FakeTransport()
        val relay = FloodRelay(inner, this, jitter = FloodRelay.JitterPolicy.None)

        inner.arrive(MeshFrame.wrap(frame(7, 0), ttl = 6))
        inner.arrive(MeshFrame.wrap(frame(7, 1), ttl = 6))
        advanceUntilIdle()

        assertEquals(2, inner.sent.size)

        // Stops the relay's collector; runTest would otherwise
        // wait on it for ever.
        relay.close()
    }

    @Test
    fun sameSeqFromDifferentSendersAreBothRelayed() = runTest {
        // seq alone is not unique — every device starts at 0. Keying on seq only would
        // make the second phone to speak inaudible.
        val inner = FakeTransport()
        val relay = FloodRelay(inner, this, jitter = FloodRelay.JitterPolicy.None)

        inner.arrive(MeshFrame.wrap(frame(7, 0), ttl = 6))
        inner.arrive(MeshFrame.wrap(frame(9, 0), ttl = 6))
        advanceUntilIdle()

        assertEquals(2, inner.sent.size)

        // Stops the relay's collector; runTest would otherwise
        // wait on it for ever.
        relay.close()
    }

    @Test
    fun doesNotRelayItsOwnTransmission() = runTest {
        // On a broadcast medium we hear our own advertisement back.
        val inner = FakeTransport()
        val relay = FloodRelay(inner, this, jitter = FloodRelay.JitterPolicy.None)

        val mine = frame(7, 0)
        relay.send(mine)
        advanceUntilIdle()
        assertEquals("the original send", 1, inner.sent.size)

        inner.arrive(MeshFrame.wrap(mine, ttl = 7))
        advanceUntilIdle()

        assertEquals("must not rebroadcast our own message", 1, inner.sent.size)
        assertEquals(1, relay.suppressedCount)

        // Stops the relay's collector; runTest would otherwise
        // wait on it for ever.
        relay.close()
    }

    @Test
    fun ourOwnMessageIsNotDeliveredBackToUs() = runTest {
        val inner = FakeTransport()
        val relay = FloodRelay(inner, this, jitter = FloodRelay.JitterPolicy.None)
        val received = mutableListOf<ByteArray>()
        val job = launch { relay.incoming.collect { received += it } }

        val mine = frame(7, 0)
        relay.send(mine)
        inner.arrive(MeshFrame.wrap(mine, ttl = 7))
        advanceUntilIdle()

        assertEquals("our own words must not be spoken back at us", 0, received.size)
        job.cancel()

        // Stops the relay's collector; runTest would otherwise
        // wait on it for ever.
        relay.close()
    }

    @Test
    fun ignoresJunkWithoutSpendingACacheEntry() = runTest {
        val inner = FakeTransport()
        val relay = FloodRelay(inner, this, jitter = FloodRelay.JitterPolicy.None)
        val received = mutableListOf<ByteArray>()
        val job = launch { relay.incoming.collect { received += it } }

        inner.arrive(byteArrayOf(8, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66,
            0x77, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66))
        advanceUntilIdle()

        assertEquals(0, received.size)
        assertEquals(0, inner.sent.size)
        assertEquals("junk must not count as suppression", 0, relay.suppressedCount)
        job.cancel()

        // Stops the relay's collector; runTest would otherwise
        // wait on it for ever.
        relay.close()
    }

    @Test
    fun forgetsAnIdAfterTheRememberWindow() = runTest {
        // seq wraps after 256 messages. If ids were remembered for ever, a long session
        // would start suppressing genuine new messages.
        var clock = 1_000L
        val inner = FakeTransport()
        val relay = FloodRelay(
            inner, this, jitter = FloodRelay.JitterPolicy.None,
            rememberMs = 10_000, nowMs = { clock },
        )

        inner.arrive(MeshFrame.wrap(frame(7, 0), ttl = 6))
        advanceUntilIdle()
        assertEquals(1, inner.sent.size)

        clock += 20_000  // well past the window
        inner.arrive(MeshFrame.wrap(frame(7, 0), ttl = 6))
        advanceUntilIdle()

        assertEquals("a re-sent message after the window is new again", 2, inner.sent.size)

        // Stops the relay's collector; runTest would otherwise
        // wait on it for ever.
        relay.close()
    }

    @Test
    fun theSeenCacheIsBounded() = runTest {
        var clock = 1_000L
        val inner = FakeTransport()
        val relay = FloodRelay(
            inner, this, jitter = FloodRelay.JitterPolicy.None,
            maxRemembered = 8, nowMs = { clock },
        )

        // 40 distinct messages, all within the remember window.
        for (i in 0 until 40) {
            inner.arrive(MeshFrame.wrap(frame(sessionId = i % 256, seq = i), ttl = 2))
            clock += 1
        }
        advanceUntilIdle()
        assertEquals(40, inner.sent.size)

        // The earliest ids have been evicted, so they look new again. That is the
        // deliberate trade: bounded memory over perfect suppression.
        inner.arrive(MeshFrame.wrap(frame(sessionId = 0, seq = 0), ttl = 2))
        advanceUntilIdle()
        assertEquals(41, inner.sent.size)

        // Stops the relay's collector; runTest would otherwise
        // wait on it for ever.
        relay.close()
    }

    @Test
    fun aRejectedRebroadcastIsNotCounted() = runTest {
        val inner = FakeTransport()
        inner.accept = false
        val relay = FloodRelay(inner, this, jitter = FloodRelay.JitterPolicy.None)

        inner.arrive(MeshFrame.wrap(frame(7, 0), ttl = 6))
        advanceUntilIdle()

        assertEquals("attempted", 1, inner.sent.size)
        assertEquals("but not counted as relayed", 0, relay.relayedCount)

        // Stops the relay's collector; runTest would otherwise
        // wait on it for ever.
        relay.close()
    }

    // --- the claim: a message crosses a chain of phones -------------------------------

    @Test
    fun aMessageTraversesAChainOfTenPhones() = runTest {
        // The whole point of the feature, simulated: ten nodes in a line, each hearing
        // only its neighbours. Node 0 speaks; node 9 must hear it.
        //
        // This is the honest scope of the range claim — it shows the PROTOCOL carries a
        // message across nine hops with correct suppression and hop accounting. It says
        // nothing about metres, which depend on radios and terrain and can only be
        // measured outdoors with real phones.
        val n = 10
        val nodes = List(n) { FakeTransport() }
        val relays = nodes.map { t ->
            FloodRelay(t, this, ttl = MeshFrame.DEFAULT_TTL, jitter = FloodRelay.JitterPolicy.None)
        }
        val heard = BooleanArray(n)
        val jobs = relays.mapIndexed { i, r ->
            launch { r.incoming.collect { heard[i] = true } }
        }

        // Wire neighbours: whatever node i broadcasts is heard by i-1 and i+1.
        // Done by polling each node's outbox, which keeps the fake free of callbacks.
        val delivered = IntArray(n)
        suspend fun pump() {
            var moved = true
            var rounds = 0
            while (moved && rounds < 200) {
                moved = false
                rounds++
                for (i in 0 until n) {
                    while (delivered[i] < nodes[i].sent.size) {
                        val out = nodes[i].sent[delivered[i]++]
                        for (j in listOf(i - 1, i + 1)) {
                            if (j in 0 until n) nodes[j].arrive(out.copyOf())
                        }
                        moved = true
                        advanceUntilIdle()
                    }
                }
            }
        }

        relays[0].send(frame(sessionId = 42, seq = 0))
        advanceUntilIdle()
        pump()

        assertTrue("the far end of a ten-node chain must hear it", heard[9])
        assertTrue("every node in between must hear it", (1 until n).all { heard[it] })
        assertTrue("the sender must not hear its own words", !heard[0])

        // Each node rebroadcasts at most once, however many copies it heard.
        for (i in 1 until n) {
            assertTrue(
                "node $i rebroadcast ${nodes[i].sent.size} times",
                nodes[i].sent.size <= 1,
            )
        }
        jobs.forEach { it.cancel() }

        // Stops the relay's collector; runTest would otherwise
        // wait on it for ever.
        relays.forEach { it.close() }
    }

    @Test
    fun aChainLongerThanTheTtlDoesNotReachTheEnd() = runTest {
        // The hop limit is real and must be visible as a limit, not quietly ignored.
        val n = 6
        val nodes = List(n) { FakeTransport() }
        val relays = nodes.map {
            FloodRelay(it, this, ttl = 2, jitter = FloodRelay.JitterPolicy.None)
        }
        val heard = BooleanArray(n)
        val jobs = relays.mapIndexed { i, r -> launch { r.incoming.collect { heard[i] = true } } }

        val delivered = IntArray(n)
        relays[0].send(frame(sessionId = 42, seq = 0))
        advanceUntilIdle()
        var moved = true
        var rounds = 0
        while (moved && rounds < 100) {
            moved = false; rounds++
            for (i in 0 until n) {
                while (delivered[i] < nodes[i].sent.size) {
                    val out = nodes[i].sent[delivered[i]++]
                    for (j in listOf(i - 1, i + 1)) {
                        if (j in 0 until n) nodes[j].arrive(out.copyOf())
                    }
                    moved = true; advanceUntilIdle()
                }
            }
        }

        assertTrue("within the hop limit", heard[1] && heard[2])
        assertTrue("beyond it, nothing", !heard[4] && !heard[5])
        jobs.forEach { it.cancel() }

        // Stops the relay's collector; runTest would otherwise
        // wait on it for ever.
        relays.forEach { it.close() }
    }

    // --- repetition -------------------------------------------------------------------

    @Test
    fun repeatSenderSendsEveryCopy() = runTest {
        val inner = FakeTransport()
        val sender = RepeatSender(inner, this, copies = 5, gapMs = 400)

        assertTrue(sender.send(frame(1, 0)))
        assertEquals("the first copy goes immediately", 1, inner.sent.size)

        advanceUntilIdle()
        assertEquals(5, inner.sent.size)
        assertTrue("every copy identical", inner.sent.all { it.contentEquals(inner.sent[0]) })

        // Stops the relay's collector; runTest would otherwise
        // wait on it for ever.
        sender.close()
    }

    @Test
    fun repeatSenderWithOneCopyIsATransparentPassThrough() = runTest {
        val inner = FakeTransport()
        val sender = RepeatSender(inner, this, copies = 1)
        sender.send(frame(1, 0))
        advanceUntilIdle()
        assertEquals(1, inner.sent.size)
        assertEquals("fake", sender.name)

        // Stops the relay's collector; runTest would otherwise
        // wait on it for ever.
        sender.close()
    }

    @Test
    fun repeatedCopiesAreSpokenOnceByTheReceiver() = runTest {
        // Repetition would be useless if it made the far end speak ten times. It is
        // FloodRelay's suppression that makes it safe.
        val inner = FakeTransport()
        val relay = FloodRelay(inner, this, jitter = FloodRelay.JitterPolicy.None)
        val received = mutableListOf<ByteArray>()
        val job = launch { relay.incoming.collect { received += it } }

        val f = frame(11, 0)
        repeat(10) { inner.arrive(MeshFrame.wrap(f, ttl = 4)) }
        advanceUntilIdle()

        assertEquals(1, received.size)
        job.cancel()

        // Stops the relay's collector; runTest would otherwise
        // wait on it for ever.
        relay.close()
    }

    @Test
    fun deliveryProbabilityMatchesTheArithmetic() {
        // The figures quoted in RepeatSender's documentation, pinned so the docs cannot
        // drift from the code.
        assertEquals(0.10, RepeatSender.deliveryProbability(0.10, 1), 1e-9)
        assertEquals(0.651321, RepeatSender.deliveryProbability(0.10, 10), 1e-6)
        assertEquals(0.96875, RepeatSender.deliveryProbability(0.50, 5), 1e-9)
        assertEquals(1.0, RepeatSender.deliveryProbability(1.0, 1), 1e-9)
        assertEquals(0.0, RepeatSender.deliveryProbability(0.0, 50), 1e-9)
    }

    @Test
    fun meshNameDescribesTheStack() {
        // The CSV records transport name, so a reader can tell which configuration
        // produced a row.
        val inner = FakeTransport()
        assertNotNull(inner.name)
    }
}
