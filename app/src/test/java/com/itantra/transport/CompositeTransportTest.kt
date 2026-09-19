package com.itantra.transport

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositeTransportTest {

    /** A link that accepts frames up to [limit] bytes, like BLE's 24 or Wi-Fi's 1400. */
    private class FakeLink(
        override val name: String,
        private val limit: Int,
        initial: TransportState = TransportState.Connected(name),
    ) : Transport {
        override val nominalBitrate: Int? = null
        val stateFlow = MutableStateFlow(initial)
        override val state: StateFlow<TransportState> = stateFlow
        val inbox = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8)
        override val incoming = inbox
        val sent = mutableListOf<ByteArray>()
        override suspend fun connect() {}
        override suspend fun send(frame: ByteArray): Boolean {
            if (frame.size > limit) return false
            sent += frame
            return true
        }
        override suspend fun close() { stateFlow.value = TransportState.Closed }
    }

    @Test
    fun aSmallFrameGoesOutOnEveryLink() = runTest {
        val wifi = FakeLink("wifi", 1400)
        val ble = FakeLink("ble", 24)
        val c = CompositeTransport(listOf(wifi, ble), backgroundScope)
        assertTrue(c.send(ByteArray(16)))
        assertEquals(1, wifi.sent.size)
        assertEquals(1, ble.sent.size)
    }

    @Test
    fun aFrameTooBigForBleStillTravelsOverWifi() = runTest {
        val wifi = FakeLink("wifi", 1400)
        val ble = FakeLink("ble", 24)
        val c = CompositeTransport(listOf(wifi, ble), backgroundScope)
        assertTrue("free speech must not be lost to the smaller link", c.send(ByteArray(108)))
        assertEquals(1, wifi.sent.size)
        assertEquals(0, ble.sent.size)
    }

    @Test
    fun aLinkThatIsDownIsSkippedNotFatal() = runTest {
        val wifi = FakeLink("wifi", 1400, TransportState.Failed("no Wi-Fi network"))
        val ble = FakeLink("ble", 24)
        val c = CompositeTransport(listOf(wifi, ble), backgroundScope)
        assertTrue(c.send(ByteArray(16)))
        assertEquals(0, wifi.sent.size)
        assertEquals(1, ble.sent.size)
    }

    @Test
    fun sendFailsOnlyWhenNoLinkCarriesIt() = runTest {
        val ble = FakeLink("ble", 24)
        val c = CompositeTransport(listOf(ble), backgroundScope)
        assertFalse(c.send(ByteArray(100)))
    }

    @Test
    fun framesFromEveryLinkAreReceived() = runTest {
        val wifi = FakeLink("wifi", 1400)
        val ble = FakeLink("ble", 24)
        val c = CompositeTransport(listOf(wifi, ble), backgroundScope)
        val got = mutableListOf<Int>()
        backgroundScope.launch { c.incoming.collect { got += it[0].toInt() } }
        runCurrent()
        wifi.inbox.emit(byteArrayOf(1))
        ble.inbox.emit(byteArrayOf(2))
        runCurrent()
        assertEquals(listOf(1, 2), got)
    }

    @Test
    fun connectedIfAnyLinkIsUpFailedOnlyIfAllFail() {
        val up = TransportState.Connected("everyone on this Wi-Fi")
        val down = TransportState.Failed("Bluetooth is off")
        assertEquals(up, CompositeTransport.merge(listOf(up, down)))
        assertTrue(CompositeTransport.merge(listOf(down, down)) is TransportState.Failed)
        assertEquals(TransportState.Connecting,
            CompositeTransport.merge(listOf(TransportState.Connecting, down)))
    }

    @Test
    fun theStateNamesEveryLinkThatIsUp() = runTest {
        val wifi = FakeLink("wifi", 1400)
        val ble = FakeLink("ble", 24)
        val c = CompositeTransport(listOf(wifi, ble), backgroundScope)
        runCurrent()
        assertEquals(TransportState.Connected("wifi + ble"), c.state.value)
        ble.stateFlow.value = TransportState.Failed("off")
        runCurrent()
        assertEquals(TransportState.Connected("wifi"), c.state.value)
    }
}
