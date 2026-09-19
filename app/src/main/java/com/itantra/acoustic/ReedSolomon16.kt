package com.itantra.acoustic

/**
 * Reed-Solomon RS(15, 9) over GF(16): corrects any 3 wrong symbols in 15.
 *
 * ## Why this code, for a sound link
 *
 * The acoustic modem sends one of 16 tones per symbol, so a symbol is a 4-bit nibble —
 * exactly one element of GF(16). A reflection off a wall or a clap in the room does not
 * flip a single bit; it corrupts a whole tone. Reed-Solomon corrects *symbols*, whatever
 * happened to the bits inside them, which makes it the natural fit: one tone lost is one
 * symbol error, however many of its four bits were wrong.
 *
 * Nine data nibbles plus six check nibbles per block. Six checks buy three corrections —
 * the most any code of this length can offer for that overhead (RS codes are MDS).
 *
 * Field: GF(2^4) with primitive polynomial x^4 + x + 1. Generator roots α^1 … α^6.
 * Decoding: syndromes, Berlekamp–Massey, Chien search, Forney.
 */
object ReedSolomon16 {

    const val N = 15
    const val K = 9
    const val PARITY = N - K           // 6
    const val T = PARITY / 2           // 3 correctable symbols

    private val EXP = IntArray(30)
    private val LOG = IntArray(16)

    init {
        var x = 1
        for (i in 0 until 15) {
            EXP[i] = x
            LOG[x] = i
            x = x shl 1
            if (x and 0x10 != 0) x = x xor 0x13   // x^4 + x + 1
        }
        for (i in 15 until 30) EXP[i] = EXP[i - 15]
    }

    private fun mul(a: Int, b: Int): Int =
        if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

    private fun div(a: Int, b: Int): Int {
        require(b != 0) { "division by zero in GF(16)" }
        return if (a == 0) 0 else EXP[(LOG[a] - LOG[b] + 15) % 15]
    }

    private fun pow(a: Int, e: Int): Int =
        if (a == 0) 0 else EXP[((LOG[a] * e) % 15 + 15) % 15]

    /** g(x) = (x - α)(x - α²)…(x - α⁶), highest degree first. */
    private val GENERATOR: IntArray = run {
        var g = intArrayOf(1)
        for (i in 1..PARITY) {
            val root = EXP[i]
            val next = IntArray(g.size + 1)
            for (j in g.indices) {
                next[j] = next[j] xor g[j]
                next[j + 1] = next[j + 1] xor mul(g[j], root)
            }
            g = next
        }
        g
    }

    /**
     * Systematic encoding: the 9 data nibbles, then 6 check nibbles.
     * Index 0 is the highest-degree coefficient and is transmitted first.
     */
    fun encode(data: IntArray): IntArray {
        require(data.size == K) { "need $K data nibbles, got ${data.size}" }
        require(data.all { it in 0..15 }) { "nibbles only" }
        // Remainder of data(x)·x^6 divided by g(x), by synthetic division.
        val work = IntArray(N)
        data.copyInto(work)
        for (i in 0 until K) {
            val coef = work[i]
            if (coef == 0) continue
            for (j in 1 until GENERATOR.size) work[i + j] = work[i + j] xor mul(GENERATOR[j], coef)
        }
        val out = IntArray(N)
        data.copyInto(out)
        for (i in K until N) out[i] = work[i]
        return out
    }

    /**
     * Correct up to three symbol errors in place of a copy.
     *
     * @return the 9 data nibbles, or null if the block has more errors than can be
     *   corrected. Null is the safe answer: a sound link that delivered a wrongly
     *   "corrected" distress message would be worse than one that stayed silent, and the
     *   packet's own CRC is checked again above this as a second line.
     */
    fun decode(received: IntArray): IntArray? {
        require(received.size == N) { "need $N nibbles, got ${received.size}" }
        val r = received.copyOf()

        // Syndromes S_j = r(α^j), j = 1..6. All zero means no detectable error.
        val s = IntArray(PARITY) { j -> evalAt(r, EXP[j + 1]) }
        if (s.all { it == 0 }) return r.copyOf(K)

        // Berlekamp–Massey: the shortest LFSR generating the syndromes is the error
        // locator Λ(x), lowest degree first.
        var lambda = intArrayOf(1)
        var prev = intArrayOf(1)
        var l = 0
        var m = 1
        var b = 1
        for (n in 0 until PARITY) {
            var d = s[n]
            for (i in 1..l) if (i < lambda.size) d = d xor mul(lambda[i], s[n - i])
            if (d == 0) {
                m++
                continue
            }
            val coef = div(d, b)
            val shifted = IntArray(prev.size + m)
            for (i in prev.indices) shifted[i + m] = mul(prev[i], coef)
            val next = IntArray(maxOf(lambda.size, shifted.size))
            for (i in lambda.indices) next[i] = lambda[i]
            for (i in shifted.indices) next[i] = next[i] xor shifted[i]
            if (2 * l <= n) {
                prev = lambda
                l = n + 1 - l
                b = d
                m = 1
            } else {
                m++
            }
            lambda = next
        }
        val degree = lambda.indexOfLast { it != 0 }
        if (degree != l || l > T) return null

        // Chien search: position p (transmission index) is in error when Λ(α^-e) = 0,
        // where e = N-1-p is that symbol's power of x.
        val positions = mutableListOf<Int>()
        for (p in 0 until N) {
            val e = N - 1 - p
            if (evalLow(lambda, pow(EXP[1], -e)) == 0) positions += p
        }
        if (positions.size != l) return null   // locator does not factor: too many errors

        // Forney: Ω(x) = S(x)Λ(x) mod x^6, and with first root α^1 the error value is
        // e = Ω(X⁻¹) / Λ'(X⁻¹) in characteristic 2.
        val omega = IntArray(PARITY)
        for (i in 0 until PARITY) {
            var acc = 0
            for (j in 0..minOf(i, lambda.size - 1)) acc = acc xor mul(s[i - j], lambda[j])
            omega[i] = acc
        }
        // Formal derivative in characteristic 2 keeps only odd-degree terms.
        val dLambda = IntArray(maxOf(1, lambda.size - 1))
        for (i in 1 until lambda.size) if (i % 2 == 1) dLambda[i - 1] = lambda[i]

        for (p in positions) {
            val e = N - 1 - p
            val xInv = pow(EXP[1], -e)
            val den = evalLow(dLambda, xInv)
            if (den == 0) return null
            r[p] = r[p] xor div(evalLow(omega, xInv), den)
        }

        // Verify: a correction that does not produce a codeword was a miscorrection.
        for (j in 0 until PARITY) if (evalAt(r, EXP[j + 1]) != 0) return null
        return r.copyOf(K)
    }

    /** Evaluate a polynomial stored highest degree first. */
    private fun evalAt(poly: IntArray, x: Int): Int {
        var acc = 0
        for (c in poly) acc = mul(acc, x) xor c
        return acc
    }

    /** Evaluate a polynomial stored lowest degree first. */
    private fun evalLow(poly: IntArray, x: Int): Int {
        var acc = 0
        for (i in poly.indices.reversed()) acc = mul(acc, x) xor poly[i]
        return acc
    }
}
