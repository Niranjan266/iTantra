package com.itantra.audio

/**
 * The audio format, fixed once for the whole system (TRD section 5).
 *
 * 16 kHz mono 16-bit is not a preference — it is what every open speech model expects
 * as input. Capturing at anything else would mean resampling on the hot path, on a
 * low-end phone, for no benefit.
 */
object AudioSpec {

    const val SAMPLE_RATE = 16_000

    /**
     * 20 ms per frame. Small enough that endpointing is responsive and the latency
     * budget's "mic buffer 30 ms" line is achievable; large enough that per-frame
     * overhead stays negligible.
     */
    const val FRAME_MS = 20

    const val SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_MS / 1000  // 320
    const val BYTES_PER_SAMPLE = 2
    const val BYTES_PER_FRAME = SAMPLES_PER_FRAME * BYTES_PER_SAMPLE  // 640

    fun framesForMs(ms: Int): Int = ms / FRAME_MS
    fun msForFrames(frames: Int): Int = frames * FRAME_MS
}

/**
 * One captured frame.
 *
 * [capturedAtNanos] is stamped the instant the frame leaves the microphone, from the
 * monotonic clock. Every latency figure the project reports is anchored to this — a
 * timestamp taken later, after queueing, would quietly flatter every measurement.
 */
data class AudioFrame(
    val samples: ShortArray,
    val index: Int,
    val capturedAtNanos: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is AudioFrame && index == other.index &&
            capturedAtNanos == other.capturedAtNanos &&
            samples.contentEquals(other.samples)

    override fun hashCode(): Int =
        31 * (31 * samples.contentHashCode() + index) + capturedAtNanos.hashCode()
}
