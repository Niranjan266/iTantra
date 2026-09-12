package com.itantra.codec

/**
 * Checksums for the ITP-1 wire format (TRD section 4.2).
 *
 * Two separate checks, deliberately:
 *  - CRC-8 over the 10-byte header, so a receiver can reject a bad packet before it has
 *    read the payload. On a raw serial link this is what stops a corrupted PAYLOAD_LEN
 *    from making the reader consume the next packet's bytes.
 *  - CRC-16 over the whole frame, so single-bit corruption anywhere is caught.
 *
 * Both implementations are pinned by the standard "123456789" check values in
 * CrcTest, so this file can never drift without a test failing.
 */
object Crc {

    /**
     * CRC-8/ATM. Polynomial 0x07, initial value 0x00, no reflection, no final XOR.
     * Check value for the ASCII string "123456789" is 0xF4.
     */
    fun crc8(data: ByteArray, from: Int = 0, until: Int = data.size): Int {
        var crc = 0x00
        for (i in from until until) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) {
                    ((crc shl 1) xor 0x07) and 0xFF
                } else {
                    (crc shl 1) and 0xFF
                }
            }
        }
        return crc and 0xFF
    }

    /**
     * CRC-16/CCITT-FALSE. Polynomial 0x1021, initial value 0xFFFF, no reflection,
     * no final XOR. Check value for the ASCII string "123456789" is 0x29B1.
     */
    fun crc16(data: ByteArray, from: Int = 0, until: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in from until until) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) {
                    ((crc shl 1) xor 0x1021) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc and 0xFFFF
    }
}
