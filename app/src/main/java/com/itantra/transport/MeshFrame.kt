package com.itantra.transport

import com.itantra.codec.PacketCodec

/**
 * One hop's worth of framing around an ITP-1 packet.
 *
 * ```
 *   byte 0      TTL        hops remaining; 0 means "do not relay this again"
 *   byte 1..n   ITP-1      the packet exactly as [PacketCodec] produced it
 * ```
 *
 * ## Why TTL is here and not in the packet
 *
 * A time-to-live is a property of **this hop**, not of the message. The sender's words,
 * language and urgency are the same however many phones the message crossed to arrive;
 * the hop count is link bookkeeping that changes at every relay.
 *
 * Keeping it outside the packet buys three things:
 *
 *  - The ITP-1 header stays frozen. It has no spare byte and its flags nibble has no
 *    spare bit, so a TTL field could not have been added without a new protocol version.
 *  - A relay can decrement the TTL **without re-encoding the packet or recomputing its
 *    CRC**. The packet's checksum therefore still proves the packet came from its author
 *    intact, across any number of relays — which it could not if every hop rewrote it.
 *  - A transport that does its own forwarding can ignore this byte entirely.
 *
 * ## One byte, deliberately
 *
 * The overhead is 1 byte on a 16-byte phrase packet. That matters because the target
 * bearer is a **BLE advertisement**: legacy advertising gives about 24 usable bytes, so
 * 16 + 1 fits and a 62-byte spelled-out message does not. The codebook is what makes
 * connectionless broadcast possible at all — see [com.itantra.codec.PhraseCodebook].
 */
object MeshFrame {

    /** Bytes of mesh framing added to a packet. */
    const val OVERHEAD = 1

    /**
     * Hops a message may make by default.
     *
     * 8 is chosen against the arithmetic of the medium, not for roundness. Each hop is
     * 30–200 m depending on radio and terrain, so 8 hops is a few hundred metres to a
     * kilometre or two — the scale of a village to its neighbour. Raising it does not buy
     * distance for free: flooding costs one rebroadcast per node per message, so airtime
     * grows with hops and nodes together, and on a shared channel a large TTL in a dense
     * crowd is how a mesh drowns itself.
     */
    const val DEFAULT_TTL = 8

    /** The largest TTL the single byte can carry. */
    const val MAX_TTL = 255

    /**
     * Usable bytes in one legacy BLE advertisement, after the flags and service-data
     * AD structures are accounted for.
     *
     * 31 total − 3 (flags AD) − 4 (service-data AD header + 16-bit UUID) = 24. Quoted
     * here because it is the constraint that decides whether a message can travel
     * connectionlessly, and [fits] is the check that depends on it.
     */
    const val BLE_LEGACY_CAPACITY = 24

    fun wrap(frame: ByteArray, ttl: Int = DEFAULT_TTL): ByteArray {
        require(ttl in 0..MAX_TTL) { "ttl out of range: $ttl" }
        val out = ByteArray(frame.size + OVERHEAD)
        out[0] = ttl.toByte()
        frame.copyInto(out, OVERHEAD)
        return out
    }

    /** The TTL of a wrapped frame, or null if there is not even a TTL byte. */
    fun ttlOf(wrapped: ByteArray): Int? =
        if (wrapped.isEmpty()) null else wrapped[0].toInt() and 0xFF

    /**
     * The ITP-1 frame inside, or null if this is not a plausible mesh frame.
     *
     * Validated rather than trusted: a BLE scan picks up every beacon in range, most of
     * which are shop tags and headphones. The packet's own MAGIC and header CRC do the
     * real rejecting, so this only has to be cheap and not crash.
     */
    fun unwrap(wrapped: ByteArray): ByteArray? {
        if (wrapped.size < OVERHEAD + PacketCodec.MIN_FRAME_SIZE) return null
        return wrapped.copyOfRange(OVERHEAD, wrapped.size)
    }

    /**
     * The same frame with its TTL reduced by one, or null when it must not be relayed.
     *
     * Null at TTL 0 is the thing that stops a flood being infinite, so it is a return
     * value rather than a clamp: a caller cannot accidentally rebroadcast a dead frame
     * by ignoring a number.
     */
    fun decrement(wrapped: ByteArray): ByteArray? {
        val ttl = ttlOf(wrapped) ?: return null
        if (ttl == 0) return null
        val out = wrapped.copyOf()
        out[0] = (ttl - 1).toByte()
        return out
    }

    /** Whether a wrapped frame fits one legacy BLE advertisement. */
    fun fits(wrapped: ByteArray): Boolean = wrapped.size <= BLE_LEGACY_CAPACITY
}
