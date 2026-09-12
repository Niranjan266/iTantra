package com.itantra.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exported CSV is the evidence behind every measured claim in the submission, so
 * it has to survive being opened in a spreadsheet and it has to report the tail rather
 * than the best case.
 */
class MetricsTest {

    private fun record(
        seq: Int = 1,
        latency: Double? = 800.0,
        transport: String = "Loopback",
        note: String = "",
    ) = TransmissionRecord(
        seq = seq, langId = 0, intent = 0,
        wireBytes = 75, payloadSymbols = 62,
        totalLatencyMs = latency,
        transportName = transport,
        ok = true,
        note = note,
    )

    // --- CSV integrity ---

    @Test
    fun `header and row have the same number of columns`() {
        val headers = TransmissionRecord.CSV_HEADER.split(",").size
        val fields = record().toCsvRow().split(",").size
        assertEquals("header/row mismatch would silently shift every column", headers, fields)
    }

    @Test
    fun `a transport name containing a comma cannot break the row`() {
        // "Bluetooth (host) @ 2.4 kbps" is fine, but a device name is user-supplied
        // and may contain anything at all.
        val row = record(transport = "Nokia, model 3").toCsvRow()
        assertTrue("field was not quoted: $row", row.contains("\"Nokia, model 3\""))
        // Quoting means the column count is preserved for a naive splitter too.
        assertTrue(row.contains("\""))
    }

    @Test
    fun `quotes inside a field are escaped by doubling`() {
        val row = record(transport = "the \"good\" phone").toCsvRow()
        assertTrue(row, row.contains("\"the \"\"good\"\" phone\""))
    }

    @Test
    fun `a missing latency becomes an empty cell rather than the word null`() {
        val row = record(latency = null).toCsvRow()
        assertTrue("row was: $row", !row.contains("null"))
        assertTrue(row.contains(",,"))
    }

    @Test
    fun `newlines in a note cannot create a phantom row`() {
        val row = record(note = "line one\nline two").toCsvRow()
        assertTrue("unquoted newline would split the row", row.startsWith("1,"))
        assertTrue(row.contains("\"line one\nline two\""))
    }

    // --- Percentiles ---

    @Test
    fun `percentiles use nearest rank`() {
        val values = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0)
        assertEquals(5.0, Percentiles.of(values, 50.0)!!, 0.0001)
        assertEquals(10.0, Percentiles.of(values, 95.0)!!, 0.0001)
        assertEquals(1.0, Percentiles.of(values, 1.0)!!, 0.0001)
        assertEquals(10.0, Percentiles.of(values, 100.0)!!, 0.0001)
    }

    @Test
    fun `percentiles report a value that was actually measured`() {
        // No interpolation: p95 must be a real observation, not an average of two.
        val values = listOf(10.0, 20.0, 900.0)
        assertTrue(Percentiles.of(values, 95.0)!! in values)
        assertTrue(Percentiles.of(values, 50.0)!! in values)
    }

    @Test
    fun `unsorted input is handled`() {
        assertEquals(5.0, Percentiles.of(listOf(9.0, 1.0, 5.0), 50.0)!!, 0.0001)
    }

    @Test
    fun `no samples gives null rather than zero`() {
        // Zero would read as "instant", which is the opposite of "unknown".
        assertNull(Percentiles.of(emptyList(), 50.0))
    }

    @Test
    fun `a single sample is its own every percentile`() {
        assertEquals(42.0, Percentiles.of(listOf(42.0), 50.0)!!, 0.0001)
        assertEquals(42.0, Percentiles.of(listOf(42.0), 95.0)!!, 0.0001)
    }

    @Test
    fun `an out of range percentile is rejected`() {
        for (bad in listOf(-1.0, 101.0)) {
            runCatching { Percentiles.of(listOf(1.0), bad) }
                .onSuccess { throw AssertionError("percentile $bad was accepted") }
        }
    }

    @Test
    fun `the summary reports the tail and not just the median`() {
        val s = Percentiles.summarise("Latency", listOf(100.0, 110.0, 120.0, 2_000.0))
        assertTrue(s, s.contains("p50"))
        assertTrue("a summary without p95 hides the tail", s.contains("p95"))
        assertTrue(s, s.contains("max"))
        assertTrue(s, s.contains("n=4"))
    }

    @Test
    fun `summarising nothing says so instead of inventing zeros`() {
        assertEquals("Latency: no samples", Percentiles.summarise("Latency", emptyList()))
    }
}
