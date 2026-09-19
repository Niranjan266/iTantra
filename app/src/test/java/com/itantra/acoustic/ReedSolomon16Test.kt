package com.itantra.acoustic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReedSolomon16Test {

    private val rnd = Random(26173)

    private fun randomData() = IntArray(ReedSolomon16.K) { rnd.nextInt(16) }

    /** Corrupt [count] distinct positions, each to a different value. */
    private fun corrupt(word: IntArray, count: Int): IntArray {
        val out = word.copyOf()
        val positions = (0 until ReedSolomon16.N).shuffled(rnd).take(count)
        for (p in positions) out[p] = out[p] xor (1 + rnd.nextInt(15))
        return out
    }

    @Test
    fun encodingIsSystematic() {
        val data = randomData()
        assertArrayEquals(data, ReedSolomon16.encode(data).copyOf(ReedSolomon16.K))
    }

    @Test
    fun cleanBlocksDecode() {
        repeat(500) {
            val data = randomData()
            assertArrayEquals(data, ReedSolomon16.decode(ReedSolomon16.encode(data)))
        }
    }

    @Test
    fun everyPatternOfUpToThreeErrorsIsCorrected() {
        for (errors in 1..3) {
            repeat(3000) {
                val data = randomData()
                val got = ReedSolomon16.decode(corrupt(ReedSolomon16.encode(data), errors))
                assertNotNull("$errors errors must be correctable", got)
                assertArrayEquals("$errors errors", data, got)
            }
        }
    }

    @Test
    fun everySinglePositionAndValueIsCorrected() {
        // Exhaustive for one error: all 15 positions × all 15 wrong values.
        val data = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)
        val word = ReedSolomon16.encode(data)
        for (p in 0 until 15) for (v in 1..15) {
            val bad = word.copyOf().also { it[p] = it[p] xor v }
            assertArrayEquals("position $p value $v", data, ReedSolomon16.decode(bad))
        }
    }

    @Test
    fun tooManyErrorsAreMostlyRefusedNotMiscorrected() {
        // Beyond three errors a decoder may be fooled into a different valid codeword —
        // unavoidable for any code — but it must usually refuse. The packet CRC above
        // catches the rest.
        var refused = 0
        var wrong = 0
        repeat(3000) {
            val data = randomData()
            val got = ReedSolomon16.decode(corrupt(ReedSolomon16.encode(data), 5))
            when {
                got == null -> refused++
                !got.contentEquals(data) -> wrong++
            }
        }
        assertTrue("refused $refused, miscorrected $wrong", refused > wrong * 3)
    }
}
