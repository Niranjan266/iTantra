package com.itantra.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Sends each message several times, spread over a few seconds.
 *
 * ## Why repeat instead of retry
 *
 * The usual answer to an unreliable link is acknowledge-and-retransmit. That needs a
 * return path, and at the edge of range there may not be one: the far phone can hear us
 * while we cannot hear it, because the two radios and the two noise floors are not
 * symmetric. A sender waiting for an ACK that physically cannot arrive sends nothing.
 *
 * Blind repetition needs no return path at all. If one attempt gets through with
 * probability p, then n independent attempts get through with probability 1 − (1 − p)ⁿ:
 *
 * | single-try | 1 try | 3 tries | 5 tries | 10 tries |
 * |---|---|---|---|---|
 * | 10% | 10% | 27% | 41% | **65%** |
 * | 30% | 30% | 66% | 83% | **97%** |
 * | 50% | 50% | 88% | 97% | **99.9%** |
 *
 * The independence assumption is the weak part and is worth being honest about: fading is
 * correlated in time, so copies sent milliseconds apart can fail together and the real
 * figure is below the arithmetic. That is exactly why the copies are **spread over
 * seconds** rather than sent back to back — it is what buys the independence the sum
 * assumes.
 *
 * ## Why this is affordable here and nowhere else
 *
 * Repetition multiplies airtime, which is why no voice system does it. A 16-byte phrase
 * packet sent ten times is 160 bytes — still a thirtieth of one Opus frame. The codebook
 * is what turns "send it ten times" from absurd into cheap.
 *
 * Duplicates cost the receiver nothing: [FloodRelay] already suppresses repeats by
 * `(sessionId, seq)`, so the listener hears the message once however many copies arrive.
 * Without that suppression this class would make the far end speak ten times.
 */
class RepeatSender(
    private val inner: Transport,
    private val scope: CoroutineScope,
    /** Total transmissions per message, including the first. */
    private val copies: Int = 3,
    /**
     * Gap between copies.
     *
     * Long enough that a fade has plausibly passed — a walking sender or a moving
     * reflector changes the channel on a scale of hundreds of milliseconds. Sub-50 ms
     * gaps would largely repeat the same fade and buy much less than the table suggests.
     */
    private val gapMs: Long = 400,
) : Transport {

    init {
        require(copies >= 1) { "copies must be at least 1" }
    }

    override val name: String
        get() = if (copies > 1) "${inner.name} ×$copies" else inner.name

    override val nominalBitrate: Int? get() = inner.nominalBitrate

    override val carriesPresence: Boolean get() = inner.carriesPresence
    override val state: StateFlow<TransportState> get() = inner.state
    override val incoming: Flow<ByteArray> get() = inner.incoming

    override suspend fun connect() = inner.connect()

    /**
     * Send the first copy now and the rest in the background.
     *
     * The first send is awaited so the caller still learns whether the transport accepted
     * the frame at all, and so a distress message is not sitting behind a delay. The
     * remaining copies must not be awaited: holding the talk button's coroutine for
     * `copies × gapMs` would freeze the UI for over a second on a ten-copy setting.
     */
    override suspend fun send(frame: ByteArray): Boolean {
        val accepted = inner.send(frame)

        if (copies > 1) {
            scope.launch {
                repeat(copies - 1) {
                    delay(gapMs)
                    inner.send(frame)
                }
            }
        }
        return accepted
    }

    override suspend fun close() = inner.close()

    companion object {
        /**
         * Probability at least one of [copies] attempts arrives, given a per-try success
         * rate.
         *
         * Here so the UI can show the honest figure for the current setting instead of a
         * number written into a slide. Assumes independence, which [RepeatSender]'s own
         * documentation explains is optimistic.
         */
        fun deliveryProbability(perTry: Double, copies: Int): Double {
            require(perTry in 0.0..1.0) { "perTry must be a probability: $perTry" }
            require(copies >= 1) { "copies must be at least 1" }
            return 1.0 - Math.pow(1.0 - perTry, copies.toDouble())
        }
    }
}
