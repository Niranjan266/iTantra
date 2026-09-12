package com.itantra.telemetry

/**
 * Stage timestamps for one transmission (TRD section 6).
 *
 * Measurement is a product feature here, not instrumentation bolted on at the end
 * (PRD section 7.6). Latency is 20% of the evaluation score, and a number that was
 * measured on the device beats a number that was estimated in a spreadsheet.
 *
 * Times are nanoseconds from [System.nanoTime], which is monotonic. Wall-clock time is
 * never used for durations — an NTP correction mid-utterance would silently corrupt
 * every reading.
 */
class LatencyTracker {

    private val marks = LinkedHashMap<String, Long>()

    fun mark(stage: String) {
        marks[stage] = System.nanoTime()
    }

    fun reset() = marks.clear()

    /** Milliseconds between two marks, or null if either was never recorded. */
    fun between(from: String, to: String): Double? {
        val a = marks[from] ?: return null
        val b = marks[to] ?: return null
        return (b - a) / 1_000_000.0
    }

    /** Milliseconds from the first mark to the last. */
    fun total(): Double? {
        if (marks.size < 2) return null
        val values = marks.values
        return (values.last() - values.first()) / 1_000_000.0
    }

    /** Every consecutive stage gap, in order, for the on-screen breakdown. */
    fun breakdown(): List<Pair<String, Double>> {
        val entries = marks.entries.toList()
        if (entries.size < 2) return emptyList()
        return (1 until entries.size).map { i ->
            entries[i].key to (entries[i].value - entries[i - 1].value) / 1_000_000.0
        }
    }

    companion object {
        // Stage names, kept as constants so the CSV columns cannot drift.
        const val PTT_PRESSED = "ptt_pressed"
        const val CAPTURE_START = "capture_start"
        const val SPEECH_END = "speech_end"
        const val ASR_DONE = "asr_done"
        const val ENCODED = "encoded"
        const val SENT = "sent"
        const val RECEIVED = "received"
        const val DECODED = "decoded"
        const val TTS_FIRST_CHUNK = "tts_first_chunk"
        const val AUDIO_OUT = "audio_out"
    }
}

/** One completed transmission, as it will appear in the exported CSV (PRD F-51). */
data class TransmissionRecord(
    val seq: Int,
    val langId: Int,
    val intent: Int,
    val wireBytes: Int,
    val payloadSymbols: Int,
    val totalLatencyMs: Double?,
    val transportName: String,
    val ok: Boolean,
    val bearerBps: Int? = null,
    val utteranceMs: Int = 0,
    val asrMs: Double? = null,
    val asrRtf: Double? = null,
    val framesDropped: Int = 0,
    val note: String = "",
) {
    companion object {
        const val CSV_HEADER =
            "seq,lang_id,intent,wire_bytes,payload_symbols,total_latency_ms," +
                "transport,bearer_bps,utterance_ms,asr_ms,asr_rtf,frames_dropped,ok,note"
    }

    fun toCsvRow(): String = listOf(
        seq, langId, intent, wireBytes, payloadSymbols,
        totalLatencyMs.fmt(),
        transportName.csv(),
        bearerBps ?: "",
        utteranceMs,
        asrMs.fmt(),
        asrRtf?.let { "%.3f".format(it) } ?: "",
        framesDropped,
        ok,
        note.csv(),
    ).joinToString(",")
}

private fun Double?.fmt(): String = this?.let { "%.2f".format(it) } ?: ""

/** Commas and quotes in a field would corrupt the row; quote them properly. */
private fun String.csv(): String =
    if (any { it == ',' || it == '"' || it == '\n' }) {
        "\"" + replace("\"", "\"\"") + "\""
    } else {
        this
    }

/**
 * Summary statistics over a set of transmissions (PRD section 10.6).
 *
 * Reports p50 and p95, not a best case. A single cherry-picked run is not evidence,
 * and the tail is where a walkie-talkie either feels responsive or does not.
 */
object Percentiles {

    /**
     * Nearest-rank percentile. With a handful of samples this is more honest than
     * interpolating between them, which invents a value never actually measured.
     */
    fun of(values: List<Double>, percentile: Double): Double? {
        require(percentile in 0.0..100.0) { "percentile out of range: $percentile" }
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val rank = Math.ceil(percentile / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    fun summarise(label: String, values: List<Double>, unit: String = "ms"): String {
        if (values.isEmpty()) return "$label: no samples"
        return "%s: n=%d  p50=%.1f%s  p95=%.1f%s  max=%.1f%s".format(
            label, values.size,
            of(values, 50.0)!!, unit,
            of(values, 95.0)!!, unit,
            values.max(), unit,
        )
    }
}
