package com.itantra.codec

/**
 * Turns a byte stream back into frames.
 *
 * Bluetooth RFCOMM, a serial cable and a radio link all deliver a stream of bytes with
 * no message boundaries. A single read may return half a frame, three frames, or the
 * tail of one and the head of the next. Something has to put the boundaries back, and
 * that is this class.
 *
 * It also has to survive corruption. If a byte is lost on a noisy link, every following
 * byte is shifted and the reader is desynchronised. Recovery works because every frame
 * begins with the MAGIC byte 0xA7 and carries a header checksum: on any failure the
 * reader discards one byte, hunts for the next plausible MAGIC, and tries again. That
 * is why MAGIC exists in the format at all (TRD section 4.2).
 *
 * Not thread-safe. Each transport owns one instance, used only from its read loop.
 */
class FrameReader(
    /**
     * Hard cap on buffered bytes. If this much accumulates without yielding a frame the
     * stream is hopeless — the reader drops the oldest byte and keeps hunting rather
     * than growing without bound. Two maximum frames is generous.
     */
    private val maxBuffer: Int = PacketCodec.MAX_FRAME_SIZE * 2,
) {

    private var buffer = ByteArray(0)

    /** Bytes thrown away while resynchronising. Surfaced in the metrics overlay. */
    var resyncBytes: Int = 0
        private set

    /** Frames whose header parsed but whose length was implausible. */
    var discardedFrames: Int = 0
        private set

    fun reset() {
        buffer = ByteArray(0)
        resyncBytes = 0
        discardedFrames = 0
    }

    /**
     * Feed freshly read bytes and take whatever complete frames fall out.
     *
     * @return complete frames, in order. Each is a standalone byte array ready for
     *   [PacketCodec.decode] — this class does not validate CRC-16, so a frame returned
     *   here may still be rejected downstream. Framing and validation stay separate on
     *   purpose: one decoder, in one place.
     */
    fun append(bytes: ByteArray, length: Int = bytes.size): List<ByteArray> {
        buffer = if (buffer.isEmpty()) {
            bytes.copyOf(length)
        } else {
            val merged = ByteArray(buffer.size + length)
            buffer.copyInto(merged)
            bytes.copyInto(merged, buffer.size, 0, length)
            merged
        }

        val frames = mutableListOf<ByteArray>()
        var pos = 0

        while (true) {
            // Hunt for a frame start.
            val magicAt = indexOfMagic(pos)
            if (magicAt < 0) {
                // Nothing usable. Keep the last byte in case it is a MAGIC split
                // across this read and the next.
                resyncBytes += (buffer.size - pos)
                pos = buffer.size
                break
            }
            if (magicAt > pos) {
                resyncBytes += (magicAt - pos)
                pos = magicAt
            }

            if (buffer.size - pos < PacketCodec.HEADER_SIZE) break // header incomplete

            val frameSize = PacketCodec.peekFrameSize(buffer, pos)
            if (frameSize == null) {
                // Looked like a frame start but the header did not hold up. Step over
                // this byte and hunt again.
                resyncBytes++
                pos++
                continue
            }
            if (frameSize > PacketCodec.MAX_FRAME_SIZE) {
                discardedFrames++
                resyncBytes++
                pos++
                continue
            }
            if (buffer.size - pos < frameSize) break // frame incomplete, wait for more

            frames += buffer.copyOfRange(pos, pos + frameSize)
            pos += frameSize
        }

        buffer = if (pos >= buffer.size) ByteArray(0) else buffer.copyOfRange(pos, buffer.size)

        // Backstop against an adversarial or badly broken stream.
        if (buffer.size > maxBuffer) {
            val overflow = buffer.size - maxBuffer
            resyncBytes += overflow
            buffer = buffer.copyOfRange(overflow, buffer.size)
        }

        return frames
    }

    /** Bytes currently held waiting for the rest of their frame. For diagnostics. */
    fun pendingBytes(): Int = buffer.size

    private fun indexOfMagic(from: Int): Int {
        for (i in from until buffer.size) {
            if ((buffer[i].toInt() and 0xFF) == PacketCodec.MAGIC) return i
        }
        return -1
    }
}
