package com.itantra.telemetry

import java.text.Normalizer
import kotlin.math.min

/**
 * Word error rate — the single biggest component of the score (PRD section 8, 40%).
 *
 * WER is the standard measure of recognition accuracy:
 *
 *     WER = (substitutions + deletions + insertions) / words in the reference
 *
 * computed from the cheapest edit path between what was said and what was recognised.
 * Note it can exceed 100%: a recogniser that invents extra words is punished for them.
 *
 * ## Why this is written out rather than eyeballed
 *
 * "It seems to work" is not a number, and the submission is scored on numbers. This
 * runs on-device against a labelled set so the accuracy figure is a measurement taken
 * on the target hardware, not an estimate copied from a model card that was produced on
 * a workstation with different audio.
 *
 * Pure Kotlin and fully tested, because a scoring bug that flatters the result is far
 * worse than no measurement at all.
 */
object WordErrorRate {

    data class Result(
        val substitutions: Int,
        val deletions: Int,
        val insertions: Int,
        val referenceWords: Int,
        val correct: Int,
    ) {
        val errors: Int get() = substitutions + deletions + insertions

        /**
         * 0.0 is perfect. Above 1.0 is possible and means more errors than words —
         * which is real information, so it is not clamped.
         *
         * An empty reference with any output is defined as 1.0 rather than infinity,
         * so a batch average stays meaningful.
         */
        val wer: Double
            get() = when {
                referenceWords > 0 -> errors.toDouble() / referenceWords
                errors > 0 -> 1.0
                else -> 0.0
            }

        val accuracy: Double get() = (1.0 - wer).coerceAtLeast(0.0)

        fun describe(): String =
            "WER %.1f%% (S=%d D=%d I=%d of %d words)".format(
                wer * 100, substitutions, deletions, insertions, referenceWords,
            )
    }

    /**
     * Normalise before comparing.
     *
     * Recognisers do not emit punctuation or capitals, so scoring them on either would
     * measure formatting rather than accuracy. Unicode is normalised to NFC so that a
     * composed and a decomposed Devanagari vowel sign count as the same word — without
     * this, Indic scoring silently reports errors that a listener would never hear.
     */
    fun normalise(text: String): List<String> =
        Normalizer.normalize(text, Normalizer.Form.NFC)
            .lowercase()
            .replace(PUNCTUATION, " ")
            .split(WHITESPACE)
            .filter { it.isNotBlank() }

    private val PUNCTUATION = Regex("""[.,!?;:"'`()\[\]{}<>@#$%^&*_+=|\\/~—–।॥]""")
    private val WHITESPACE = Regex("""\s+""")

    fun score(reference: String, hypothesis: String): Result =
        scoreWords(normalise(reference), normalise(hypothesis))

    /**
     * Levenshtein alignment over words, tracking which operation each step used.
     *
     * O(n·m) in time and memory. Utterances here are a few dozen words, so the simple
     * full-matrix version is the right choice over anything cleverer.
     */
    fun scoreWords(reference: List<String>, hypothesis: List<String>): Result {
        val n = reference.size
        val m = hypothesis.size

        if (n == 0) {
            return Result(0, 0, m, 0, 0)
        }
        if (m == 0) {
            return Result(0, n, 0, n, 0)
        }

        // cost[i][j] = cheapest edits turning reference[0..i) into hypothesis[0..j)
        val cost = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) cost[i][0] = i
        for (j in 0..m) cost[0][j] = j

        for (i in 1..n) {
            for (j in 1..m) {
                val match = if (reference[i - 1] == hypothesis[j - 1]) 0 else 1
                cost[i][j] = min(
                    cost[i - 1][j - 1] + match,          // substitute or match
                    min(
                        cost[i - 1][j] + 1,              // deletion
                        cost[i][j - 1] + 1,              // insertion
                    ),
                )
            }
        }

        // Walk the matrix backwards to attribute each error to a category. Ties are
        // broken toward substitution, which is the convention scoring tools use.
        var subs = 0
        var dels = 0
        var ins = 0
        var correct = 0
        var i = n
        var j = m

        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 &&
                    cost[i][j] == cost[i - 1][j - 1] +
                    (if (reference[i - 1] == hypothesis[j - 1]) 0 else 1) -> {
                    if (reference[i - 1] == hypothesis[j - 1]) correct++ else subs++
                    i--; j--
                }

                i > 0 && cost[i][j] == cost[i - 1][j] + 1 -> {
                    dels++; i--
                }

                else -> {
                    ins++; j--
                }
            }
        }

        return Result(
            substitutions = subs,
            deletions = dels,
            insertions = ins,
            referenceWords = n,
            correct = correct,
        )
    }

    /**
     * Aggregate over a test set.
     *
     * Errors and words are summed before dividing, rather than averaging per-utterance
     * rates. Averaging the rates would let one short sentence with two mistakes weigh
     * as heavily as a long one that was perfect, which overstates or understates the
     * result depending on the set. This is how WER is defined and how any reviewer will
     * expect it to have been computed.
     */
    fun aggregate(results: List<Result>): Result = Result(
        substitutions = results.sumOf { it.substitutions },
        deletions = results.sumOf { it.deletions },
        insertions = results.sumOf { it.insertions },
        referenceWords = results.sumOf { it.referenceWords },
        correct = results.sumOf { it.correct },
    )
}
