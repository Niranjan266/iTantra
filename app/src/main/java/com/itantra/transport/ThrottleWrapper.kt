package com.itantra.transport

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Limits any transport to a fixed bitrate (PRD F-52).
 *
 * A decorator, not a transport of its own: it wraps whatever is underneath and holds
 * each frame for as long as that many bytes would actually occupy a narrow link. The
 * thing being wrapped does not know it is being throttled, and nothing above
 * [Transport] knows either — which is why this could be added in one file with no
 * changes anywhere else.
 *
 * ## What this is for
 *
 * This project ships no radio hardware (PRD section 5.1), but the deployment story
 * depends on the claim that a 75-byte packet crosses a link where voice cannot. Rather
 * than assert that, the throttle imposes the real constraint — 300 bps is a LoRa SF12
 * or satellite short-burst link — and lets the audience watch what happens.
 *
 * The demonstration is deliberately unkind to us: it uses a real measured packet size,
 * a published Opus bitrate, and a stopwatch. At 300 bps a three-second voice note needs
 * around two minutes of airtime and a sentence sent as language needs about two seconds.
 *
 * The delay is applied on send, before delegating, because that is where it happens on
 * a real half-duplex link: the radio is busy for the whole transmission and the sender
 * cannot do anything else until it finishes.
 */
class ThrottleWrapper(
    private val delegate: Transport,
    /** Link capacity. Must be positive; use the delegate directly for "no limit". */
    private val bitsPerSecond: Int,
) : Transport {

    init {
        require(bitsPerSecond > 0) { "bitrate must be positive: $bitsPerSecond" }
    }

    override val name: String = "${delegate.name} @ ${labelFor(bitsPerSecond)}"

    override val nominalBitrate: Int get() = bitsPerSecond

    override val state: StateFlow<TransportState> get() = delegate.state
    override val incoming: Flow<ByteArray> get() = delegate.incoming

    /** Airtime of the most recent frame, for the metrics readout. */
    var lastAirtimeMs: Long = 0
        private set

    /** Total time spent occupying the link this session. */
    var totalAirtimeMs: Long = 0
        private set

    override suspend fun connect() = delegate.connect()

    override suspend fun send(frame: ByteArray): Boolean {
        val airtime = LinkBudget.airtimeMs(frame.size, bitsPerSecond)
        lastAirtimeMs = airtime
        totalAirtimeMs += airtime

        // Occupy the link for as long as the bytes would really take. Suspending
        // rather than sleeping keeps the audio and UI threads free — the point is to
        // slow the link, not the phone.
        delay(airtime)

        return delegate.send(frame)
    }

    override suspend fun close() = delegate.close()

    /** What this link would cost for a spoken utterance of the given length. */
    fun comparisonFor(frameBytes: Int, utteranceSeconds: Double): LinkBudget.Comparison =
        LinkBudget.compare(frameBytes, utteranceSeconds, bitsPerSecond)

    companion object {
        fun labelFor(bps: Int): String =
            if (bps >= 1000) "%.1f kbps".format(bps / 1000.0) else "$bps bps"
    }
}
