package com.itantra.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * This class produces the number that 40% of the score depends on, so a bug that
 * flatters the result would be worse than having no measurement at all. Every case
 * below is one a reviewer could check by hand.
 */
class WordErrorRateTest {

    @Test
    fun `an exact match scores zero`() {
        val r = WordErrorRate.score("send boats to the school", "send boats to the school")
        assertEquals(0.0, r.wer, 0.0001)
        assertEquals(5, r.correct)
        assertEquals(0, r.errors)
    }

    @Test
    fun `one wrong word in five is twenty percent`() {
        val r = WordErrorRate.score("send boats to the school", "send boats to the temple")
        assertEquals(1, r.substitutions)
        assertEquals(0.2, r.wer, 0.0001)
    }

    @Test
    fun `a missing word counts as a deletion`() {
        val r = WordErrorRate.score("send boats to the school", "send boats to school")
        assertEquals(1, r.deletions)
        assertEquals(0, r.insertions)
        assertEquals(0.2, r.wer, 0.0001)
    }

    @Test
    fun `an invented word counts as an insertion`() {
        val r = WordErrorRate.score("send boats", "send more boats")
        assertEquals(1, r.insertions)
        assertEquals(0.5, r.wer, 0.0001)
    }

    @Test
    fun `errors of every kind are counted together`() {
        // ref: the flood is rising fast     (5 words)
        // hyp: the flood was rising very fast
        //      the=ok flood=ok is->was sub, rising=ok, +very ins, fast=ok
        val r = WordErrorRate.score("the flood is rising fast", "the flood was rising very fast")
        assertEquals(5, r.referenceWords)
        assertEquals(1, r.substitutions)
        assertEquals(1, r.insertions)
        assertEquals(0, r.deletions)
        assertEquals(0.4, r.wer, 0.0001)
    }

    @Test
    fun `error rate can exceed one hundred percent`() {
        // A recogniser that hallucinates must be punished, not capped at 100%.
        val r = WordErrorRate.score("help", "please send help immediately to the village")
        assertTrue("wer was ${r.wer}", r.wer > 1.0)
        assertEquals(0.0, r.accuracy, 0.0001)
    }

    @Test
    fun `recognising nothing scores one hundred percent`() {
        val r = WordErrorRate.score("send boats to the school", "")
        assertEquals(5, r.deletions)
        assertEquals(1.0, r.wer, 0.0001)
    }

    @Test
    fun `an empty reference is handled without dividing by zero`() {
        assertEquals(0.0, WordErrorRate.score("", "").wer, 0.0001)
        assertEquals(1.0, WordErrorRate.score("", "noise").wer, 0.0001)
    }

    // --- Normalisation ---

    @Test
    fun `punctuation and case are not counted as errors`() {
        // A recogniser emits neither, so scoring them would measure formatting.
        val r = WordErrorRate.score("Send boats, to the school!", "send boats to the school")
        assertEquals(0.0, r.wer, 0.0001)
    }

    @Test
    fun `extra whitespace is ignored`() {
        val r = WordErrorRate.score("  send   boats  ", "send boats")
        assertEquals(0.0, r.wer, 0.0001)
    }

    @Test
    fun `devanagari danda is treated as punctuation`() {
        val r = WordErrorRate.score("बाढ़ आ रही है।", "बाढ़ आ रही है")
        assertEquals(0.0, r.wer, 0.0001)
    }

    @Test
    fun `composed and decomposed indic text compare equal`() {
        // Without NFC normalisation these differ byte-wise while sounding identical,
        // and every Indic measurement would carry invented errors.
        val composed = "हि"          // हि  (ha + vowel sign i)
        val decomposed = "हि"        // same, written explicitly
        assertEquals(0.0, WordErrorRate.score(composed, decomposed).wer, 0.0001)
    }

    @Test
    fun `normalisation splits on script boundaries it should not invent`() {
        // Words must not be silently merged or split by the normaliser.
        assertEquals(listOf("send", "boats"), WordErrorRate.normalise("Send, boats."))
        assertEquals(4, WordErrorRate.normalise("बाढ़ आ रही है").size)
    }

    // --- Aggregation ---

    @Test
    fun `aggregate sums errors and words rather than averaging rates`() {
        // A short bad utterance must not outweigh a long good one.
        val short = WordErrorRate.score("help", "kelp")             // 1 error / 1 word
        val long = WordErrorRate.score(
            "the water is rising near the school please send boats now",
            "the water is rising near the school please send boats now",
        )                                                            // 0 errors / 11 words

        val total = WordErrorRate.aggregate(listOf(short, long))
        assertEquals(12, total.referenceWords)
        assertEquals(1, total.errors)
        // Correct: 1/12 = 8.3%. Averaging the rates would give (100% + 0%)/2 = 50%.
        assertEquals(1.0 / 12.0, total.wer, 0.0001)
    }

    @Test
    fun `aggregating nothing is zero rather than a crash`() {
        val total = WordErrorRate.aggregate(emptyList())
        assertEquals(0, total.referenceWords)
        assertEquals(0.0, total.wer, 0.0001)
    }

    @Test
    fun `the description reports every component`() {
        val d = WordErrorRate.score("send boats to the school", "send boats to the temple").describe()
        assertTrue(d, d.contains("20.0%"))
        assertTrue(d, d.contains("S=1"))
        assertTrue(d, d.contains("of 5 words"))
    }

    @Test
    fun `scoring is symmetric in cost but not in category`() {
        // Swapping the arguments turns deletions into insertions and vice versa, while
        // the total edit distance stays the same. A mix-up here would misreport which
        // way the recogniser is failing.
        val a = WordErrorRate.score("send boats to the school", "send boats to school")
        val b = WordErrorRate.score("send boats to school", "send boats to the school")
        assertEquals(a.errors, b.errors)
        assertEquals(a.deletions, b.insertions)
        assertEquals(a.insertions, b.deletions)
    }
}
