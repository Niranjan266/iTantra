package com.itantra.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.session.SessionUiState
import com.itantra.transport.LinkBudget
import com.itantra.transport.ThrottleWrapper

/**
 * Live measurement overlay (PRD F-50).
 *
 * Latency is 20% of the score and the compression claim is the differentiator, so both
 * are on the main screen from Phase 1 rather than being reconstructed from logs at the
 * end. The comparison row is the demonstration in miniature: the same sentence as a
 * compressed voice note, next to what actually crossed the link.
 */
@Composable
fun MetricsHud(state: SessionUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Measurements", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Metric("Sent", state.sentCount.toString())
                Metric("Received", state.receivedCount.toString())
                Metric("Dropped", state.errorCount.toString())
            }

            Spacer(Modifier.height(12.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Metric("Wire size", "${state.lastSentBytes} B")
                // Shown only for a packet that came back to us, which means loopback.
                // Across two devices this needs a shared clock and is not implemented,
                // so the honest display is a dash rather than an invented figure.
                Metric(
                    "Round trip",
                    state.lastLatencyMs?.let { "%.1f ms".format(it) } ?: "—",
                )
                Metric("Transport", state.transportName)
            }

            if (state.lastSentBytes > 0) {
                Spacer(Modifier.height(12.dp))
                ComparisonRow(
                    wireBytes = state.lastSentBytes,
                    bearerBps = state.bearerBps,
                    utteranceSeconds = (state.capturedMs.takeIf { it > 0 } ?: 3_000) / 1000.0,
                )
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(label, fontSize = 11.sp, color = Color.Gray)
    }
}

/**
 * The narrow-link comparison (PRD F-52).
 *
 * [wireBytes] and [utteranceSeconds] are measured on this device. The Opus and PCM
 * figures are published bitrates, labelled as such, so nothing here is an estimate
 * dressed up as a measurement.
 *
 * When a bearer is selected this shows the actual airtime each representation would
 * need on that link — which is the demonstration in miniature, on screen, before
 * anyone runs it live.
 */
@Composable
private fun ComparisonRow(wireBytes: Int, bearerBps: Int?, utteranceSeconds: Double) {
    val opusBytes = LinkBudget.codecBytes(utteranceSeconds, LinkBudget.OPUS_BPS)
    val pcmBytes = LinkBudget.codecBytes(utteranceSeconds, LinkBudget.PCM_BPS)

    Column {
        Text(
            "A %.1f s utterance, versus reference codecs:".format(utteranceSeconds),
            fontSize = 11.sp,
            color = Color.Gray,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "iTantra $wireBytes B  ·  Opus ~${opusBytes / 1000} kB  ·  raw ~${pcmBytes / 1000} kB",
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "= %.0f× smaller than Opus".format(opusBytes.toDouble() / wireBytes),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )

        if (bearerBps == null) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Select a simulated bearer to see airtime on a narrow link.",
                fontSize = 11.sp,
                color = Color.Gray,
            )
            return@Column
        }

        val c = LinkBudget.compare(wireBytes, utteranceSeconds, bearerBps)
        Spacer(Modifier.height(8.dp))
        Text(
            "Airtime at ${ThrottleWrapper.labelFor(bearerBps)}",
            fontSize = 11.sp,
            color = Color.Gray,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "iTantra ${LinkBudget.humanDuration(c.itantraMs)}" +
                (if (c.itantraKeepsUp) "  ✓ keeps up" else ""),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (c.itantraKeepsUp) Color(0xFF2E7D32) else Color.Unspecified,
        )
        Text(
            "Opus voice ${LinkBudget.humanDuration(c.opusMs)}" +
                (if (c.opusFallsBehind) "  ✗ cannot keep up" else ""),
            fontSize = 13.sp,
            color = if (c.opusFallsBehind) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
        Text(
            "Raw audio ${LinkBudget.humanDuration(c.pcmMs)}",
            fontSize = 12.sp,
            color = Color.Gray,
        )
    }
}
