package com.itantra.codec

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The checksums are pinned against the standard "123456789" check values published for
 * each CRC variant. This matters: without an external reference the implementation
 * could be self-consistently wrong, round-trip perfectly in our own tests, and fail
 * against any other device or any reference decoder.
 */
class CrcTest {

    private val check = "123456789".toByteArray(Charsets.US_ASCII)

    @Test
    fun `crc8 matches the published check value`() {
        // CRC-8/ATM, poly 0x07, init 0x00 -> 0xF4
        assertEquals(0xF4, Crc.crc8(check))
    }

    @Test
    fun `crc16 matches the published check value`() {
        // CRC-16/CCITT-FALSE, poly 0x1021, init 0xFFFF -> 0x29B1
        assertEquals(0x29B1, Crc.crc16(check))
    }

    @Test
    fun `empty input gives the initial value`() {
        assertEquals(0x00, Crc.crc8(ByteArray(0)))
        assertEquals(0xFFFF, Crc.crc16(ByteArray(0)))
    }

    @Test
    fun `range arguments are honoured`() {
        val padded = byteArrayOf(0x7F) + check + byteArrayOf(0x7F)
        assertEquals(0xF4, Crc.crc8(padded, 1, padded.size - 1))
        assertEquals(0x29B1, Crc.crc16(padded, 1, padded.size - 1))
    }

    @Test
    fun `flipping any single bit changes crc16`() {
        val data = ByteArray(75) { (it * 7).toByte() }
        val original = Crc.crc16(data)
        for (byteIndex in data.indices) {
            for (bit in 0 until 8) {
                val corrupted = data.copyOf()
                corrupted[byteIndex] = (corrupted[byteIndex].toInt() xor (1 shl bit)).toByte()
                if (Crc.crc16(corrupted) == original) {
                    throw AssertionError("bit $bit of byte $byteIndex was not detected")
                }
            }
        }
    }
}
