package com.itantra.acoustic

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Data over sound: an ITP-1 packet as a burst of tones, from a speaker to a microphone.
 *
 * ## Why sound
 *
 * A phone's radios — Wi-Fi, Bluetooth, cellular — only speak their own protocols, and
 * Android does not let an app put arbitrary signals on them. Sound is the one channel an
 * app controls down to the waveform. It needs no pairing, no network and no radio
 * permission; it works in airplane mode; and because the tones sit inside the 300–3400 Hz
 * band every voice radio carries, **the same burst crosses a walkie-talkie, a marine or
 * HF voice radio, or a satellite phone's voice channel** held up to the phone. That is the
 * problem statement turned inside out: a link too narrow for speech can still carry the
 * sixteen bytes that *describe* the speech.
 *
 * ## Signal
 *
 * 16 kHz mono (the app's native rate), in slots of 40 ms:
 *
 *  - **Data**: 16-FSK, one tone per slot carrying 4 bits, at 1500 + 62.5·v Hz
 *    (v = 0‥15, so 1500–2437.5 Hz). Each tone is an exact bin of the 512-sample analysis
 *    window, so the sixteen are orthogonal and do not leak into each other.
 *  - **Guard**: the first 8 ms of each slot is ignored by the receiver, so the tail of the
 *    previous tone echoing off the room does not smear into this one.
 *  - **Preamble**: eight slots alternating 1250 and 2750 Hz — outside the data band, so
 *    data can never be mistaken for the start of a frame.
 *  - **Phase-continuous**, and ramped in and out, so the burst does not click.
 *
 * ## Frame
 *
 * `[length byte][payload]` split into nibbles, cut into blocks of 9, each protected as
 * RS(15, 9) — see [ReedSolomon16]. Three wrong tones in every fifteen are corrected. The
 * first block carries the length, so the receiver knows how many blocks follow after
 * decoding only one.
 *
 * A 16-byte phrase packet: 4 blocks = 60 slots + 8 preamble = 2.7 s, about 47 bits/s of
 * payload. Slow — and on a channel with no other way through, sufficient.
 */
object AcousticModem {

    const val RATE = 16_000
    const val SLOT = 640                 // 40 ms
    const val GUARD = 128                // 8 ms, skipped by the receiver
    const val WINDOW = SLOT - GUARD      // 512 samples = 32 ms, 31.25 Hz bins

    const val PREAMBLE_SLOTS = 8

    /** Largest payload accepted. A longer message would take over half a minute. */
    const val MAX_PAYLOAD = 120

    private const val BIN_HZ = RATE.toDouble() / WINDOW    // 31.25
    const val DATA_BASE_BIN = 48                            // 1500 Hz
    const val DATA_BIN_STEP = 2                             // 62.5 Hz apart
    const val SYNC_A_BIN = 40                               // 1250 Hz
    const val SYNC_B_BIN = 88                               // 2750 Hz

    private const val AMPLITUDE = 0.6
    private const val RAMP = 160         // 10 ms fade at each end of the burst

    fun dataBin(symbol: Int) = DATA_BASE_BIN + DATA_BIN_STEP * symbol
    fun binHz(bin: Int) = bin * BIN_HZ

    /** Slots needed to send [payloadBytes] bytes, preamble included. */
    fun slotsFor(payloadBytes: Int): Int = PREAMBLE_SLOTS + blocksFor(payloadBytes) * ReedSolomon16.N

    fun blocksFor(payloadBytes: Int): Int = ceilDiv(2 * (payloadBytes + 1), ReedSolomon16.K)

    fun durationMs(payloadBytes: Int): Int = slotsFor(payloadBytes) * SLOT * 1000 / RATE

    /** The tone sequence for [payload]: preamble bins, then one data bin per symbol. */
    fun bins(payload: ByteArray): IntArray {
        require(payload.size in 1..MAX_PAYLOAD) { "payload must be 1..$MAX_PAYLOAD bytes" }
        val nibbles = ArrayList<Int>(2 * (payload.size + 1))
        fun addByte(b: Int) { nibbles += (b shr 4) and 0xF; nibbles += b and 0xF }
        addByte(payload.size)
        for (b in payload) addByte(b.toInt() and 0xFF)

        val out = ArrayList<Int>()
        repeat(PREAMBLE_SLOTS) { i -> out += if (i % 2 == 0) SYNC_A_BIN else SYNC_B_BIN }
        for (block in 0 until blocksFor(payload.size)) {
            val data = IntArray(ReedSolomon16.K) { i -> nibbles.getOrElse(block * ReedSolomon16.K + i) { 0 } }
            for (symbol in ReedSolomon16.encode(data)) out += dataBin(symbol)
        }
        return out.toIntArray()
    }

    /** Render [payload] as 16 kHz PCM, ready for the speaker. */
    fun modulate(payload: ByteArray): ShortArray {
        val bins = bins(payload)
        val out = ShortArray(bins.size * SLOT)
        var phase = 0.0
        for ((slot, bin) in bins.withIndex()) {
            val step = 2 * PI * binHz(bin) / RATE
            for (i in 0 until SLOT) {
                val n = slot * SLOT + i
                val edge = min(n, out.size - 1 - n)
                val gain = if (edge < RAMP) 0.5 - 0.5 * cos(PI * edge / RAMP) else 1.0
                out[n] = (AMPLITUDE * gain * sin(phase) * Short.MAX_VALUE).toInt().toShort()
                // Accumulated, never reset: a tone change mid-waveform is then a change of
                // slope, not a jump, and makes no click.
                phase += step
                if (phase > 2 * PI) phase -= 2 * PI
            }
        }
        return out
    }

    /**
     * Normalised energy of [bin] in 512 samples starting at [from]: about 1.0 for a pure
     * tone on that bin, near 0 for silence or a different tone. Normalising by the
     * window's own energy makes it independent of volume and distance.
     */
    fun toneScore(x: FloatArray, from: Int, bin: Int, windowEnergy: Double): Double {
        if (windowEnergy <= 1e-9) return 0.0
        val w = 2 * PI * bin / WINDOW
        val coeff = 2 * cos(w)
        var s1 = 0.0
        var s2 = 0.0
        for (i in from until from + WINDOW) {
            val s0 = x[i] + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
        return power / (WINDOW / 2.0 * windowEnergy)
    }

    fun energy(x: FloatArray, from: Int): Double {
        var e = 0.0
        for (i in from until from + WINDOW) e += x[i] * x[i]
        return e
    }

    private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b
}

/**
 * Listens to a stream of microphone samples and yields every frame it can decode.
 *
 * Feed it audio in any chunk size with [feed]; each call returns the payloads completed
 * by that audio. It never blocks and keeps only the samples it may still need, so it can
 * run on the microphone's own thread for hours.
 *
 * ## Finding a frame
 *
 * Every 5 ms it checks whether the last 32 ms is dominated by a sync tone. Only then does
 * the expensive part run: it tries every 2.5 ms alignment across one slot, scores all
 * eight preamble slots against the expected A/B pattern, and keeps the best. That
 * alignment then fixes the position of every data slot that follows — the two phones'
 * clocks drift by parts per million, which over a few seconds is a fraction of a sample.
 */
class AcousticDemodulator {

    /**
     * Samples not yet discarded, in [buf] from 0 until [count].
     *
     * Grown by doubling and compacted only when more than half is dead, so each sample is
     * copied a constant number of times. The first version appended with `buf += chunk`
     * and trimmed with a copy on every call — quadratic, and 22 seconds to digest a frame
     * fed one sample at a time.
     */
    private var buf = FloatArray(AcousticModem.SLOT * 64)
    private var count = 0
    /** Absolute sample index of buf[0], so positions survive trimming. */
    private var base = 0L
    private var searchFrom = 0L

    /** Frames that failed error correction — shown in diagnostics. */
    var rejected = 0
        private set

    fun feed(samples: ShortArray): List<ByteArray> {
        if (count + samples.size > buf.size) {
            buf = buf.copyOf(maxOf(buf.size * 2, count + samples.size))
        }
        for (s in samples) buf[count++] = s / 32768f
        val out = mutableListOf<ByteArray>()
        while (true) {
            val r = step() ?: break
            if (r.isNotEmpty()) out += r
        }
        trim()
        return out
    }

    private val end get() = base + count

    /** One unit of progress. Null = need more audio. Empty = progressed, nothing decoded. */
    /**
     * Where the data of the frame being received starts, once its preamble is aligned;
     * -1 while searching. Locked so the alignment — the one expensive computation — runs
     * once per frame, not again on every chunk that arrives while the frame is still
     * being spoken. Recomputing it made a frame fed in small chunks cost seconds of CPU.
     */
    private var lockedData = -1L
    /** The first block once decoded: it holds the length, so it is needed only once. */
    private var lockedFirst: IntArray? = null

    private fun step(): ByteArray? {
        if (lockedData >= 0) return continueFrame()

        val hop = 80L
        val pos = searchFrom
        if (pos + AcousticModem.WINDOW > end) return null

        val i = (pos - base).toInt()
        val e = AcousticModem.energy(buf, i)
        val a = AcousticModem.toneScore(buf, i, AcousticModem.SYNC_A_BIN, e)
        val b = AcousticModem.toneScore(buf, i, AcousticModem.SYNC_B_BIN, e)
        if (maxOf(a, b) < TRIGGER) {
            searchFrom = pos + hop
            return ByteArray(0)
        }

        // A sync tone is present somewhere near here. The first A slot started at most one
        // preamble earlier; wait until the whole preamble plus one slot is buffered.
        val earliest = pos - AcousticModem.SLOT.toLong() * 2
        val need = earliest + AcousticModem.SLOT.toLong() * (AcousticModem.PREAMBLE_SLOTS + 2)
        if (need > end) return null

        val start = bestPreambleStart(maxOf(earliest, base), pos + AcousticModem.SLOT)
        if (start == null) {
            searchFrom = pos + hop
            return ByteArray(0)
        }
        lockedData = start + AcousticModem.SLOT.toLong() * AcousticModem.PREAMBLE_SLOTS
        lockedFirst = null
        return continueFrame()
    }

    /** Decode the locked frame as far as the audio allows. Null = wait for more. */
    private fun continueFrame(): ByteArray? {
        val dataStart = lockedData
        val blockLen = AcousticModem.SLOT.toLong() * ReedSolomon16.N

        val first = lockedFirst ?: run {
            // The first block carries the length.
            if (dataStart + blockLen > end) return null
            val decoded = decodeBlock(dataStart)
            val length = decoded?.let { (it[0] shl 4) or it[1] }
            if (decoded == null || length !in 1..AcousticModem.MAX_PAYLOAD) {
                return abandon(dataStart)
            }
            lockedFirst = decoded
            decoded
        }

        val length = (first[0] shl 4) or first[1]
        val blocks = AcousticModem.blocksFor(length)
        val frameEnd = dataStart + blockLen * blocks
        if (frameEnd > end) return null

        val nibbles = ArrayList<Int>(blocks * ReedSolomon16.K)
        nibbles.addAll(first.toList())
        for (k in 1 until blocks) {
            val d = decodeBlock(dataStart + blockLen * k) ?: return abandon(frameEnd)
            nibbles.addAll(d.toList())
        }
        lockedData = -1
        lockedFirst = null
        searchFrom = frameEnd
        return ByteArray(length) { j -> ((nibbles[2 + 2 * j] shl 4) or nibbles[3 + 2 * j]).toByte() }
    }

    /** Give up on this frame — too damaged to correct — and resume searching at [from]. */
    private fun abandon(from: Long): ByteArray {
        rejected++
        lockedData = -1
        lockedFirst = null
        searchFrom = from
        return ByteArray(0)
    }

    /** Best alignment of the eight-slot A/B preamble within [from, to), or null. */
    private fun bestPreambleStart(from: Long, to: Long): Long? {
        var best = -1.0
        var bestAt = -1L
        var s = from
        while (s < to) {
            var score = 0.0
            var ok = true
            for (k in 0 until AcousticModem.PREAMBLE_SLOTS) {
                val at = s + AcousticModem.SLOT.toLong() * k + AcousticModem.GUARD
                if (at < base || at + AcousticModem.WINDOW > end) { ok = false; break }
                val i = (at - base).toInt()
                val e = AcousticModem.energy(buf, i)
                val want = if (k % 2 == 0) AcousticModem.SYNC_A_BIN else AcousticModem.SYNC_B_BIN
                val other = if (k % 2 == 0) AcousticModem.SYNC_B_BIN else AcousticModem.SYNC_A_BIN
                val w = AcousticModem.toneScore(buf, i, want, e)
                val o = AcousticModem.toneScore(buf, i, other, e)
                if (w <= o) { ok = false; break }
                score += w
            }
            if (ok && score > best) { best = score; bestAt = s }
            s += 40
        }
        return if (bestAt >= 0 && best / AcousticModem.PREAMBLE_SLOTS > PREAMBLE_MIN) bestAt else null
    }

    /** Read fifteen tones at [at] and correct them. */
    private fun decodeBlock(at: Long): IntArray? {
        val symbols = IntArray(ReedSolomon16.N) { k ->
            val i = (at + AcousticModem.SLOT.toLong() * k + AcousticModem.GUARD - base).toInt()
            val e = AcousticModem.energy(buf, i)
            var bestV = 0
            var bestS = -1.0
            for (v in 0 until 16) {
                val sc = AcousticModem.toneScore(buf, i, AcousticModem.dataBin(v), e)
                if (sc > bestS) { bestS = sc; bestV = v }
            }
            bestV
        }
        return ReedSolomon16.decode(symbols)
    }

    /** Drop audio that no future frame can need, keeping a preamble's worth behind. */
    private fun trim() {
        // A locked frame still needs everything from its data start onward.
        val anchor = if (lockedData >= 0) minOf(searchFrom, lockedData) else searchFrom
        val keepFrom = anchor - AcousticModem.SLOT.toLong() * 3
        val drop = (keepFrom - base).toInt()
        // Compact in place, and only once most of the buffer is dead.
        if (drop > 0 && drop <= count && drop > buf.size / 2) {
            buf.copyInto(buf, 0, drop, count)
            count -= drop
            base += drop
        }
    }

    private companion object {
        /** A sync tone holding this share of the window's energy wakes the search. */
        const val TRIGGER = 0.35
        /** Mean preamble score required to believe a frame has started. */
        const val PREAMBLE_MIN = 0.30
    }
}
