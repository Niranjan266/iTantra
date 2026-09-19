package com.itantra.acoustic

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The real modem through real air — run by hand, skipped in the normal suite.
 *
 *     ITANTRA_AIR_DIR=build/air ./gradlew testDebugUnitTest --tests '*OverTheAirTest*'
 *
 * Step one writes `tx.raw` (the app's own modulator output). Something outside the JVM
 * plays it through a speaker and records a microphone into `rx.raw` (16 kHz mono s16le).
 * Step two decodes `rx.raw` with the app's own demodulator and prints what it heard.
 */
class OverTheAirTest {

    private val dir = System.getenv("ITANTRA_AIR_DIR")?.let(::File)
    val payload = "iTantra over air".toByteArray()   // 16 bytes, a phrase packet's size

    @Test
    fun writeTransmission() {
        assumeTrue(dir != null)
        dir!!.mkdirs()
        val pcm = AcousticModem.modulate(payload)
        val bb = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { bb.putShort(it) }
        File(dir, "tx.raw").writeBytes(bb.array())
    }

    @Test
    fun decodeRecording() {
        assumeTrue(dir != null && File(dir, "rx.raw").exists())
        val bytes = File(dir, "rx.raw").readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val pcm = ShortArray(bytes.size / 2) { bb.short }
        val d = AcousticDemodulator()
        val frames = mutableListOf<ByteArray>()
        var i = 0
        while (i < pcm.size) {
            val n = minOf(640, pcm.size - i)
            frames += d.feed(pcm.copyOfRange(i, i + n)); i += n
        }
        val report = buildString {
            appendLine("recording: ${pcm.size / 16000.0} s, peak ${pcm.maxOf { kotlin.math.abs(it.toInt()) }}")
            appendLine("frames decoded: ${frames.size}, rejected: ${d.rejected}")
            frames.forEach { appendLine("  \"${String(it)}\" match=${it.contentEquals(payload)}") }
        }
        File(dir, "result.txt").writeText(report)
    }
}
