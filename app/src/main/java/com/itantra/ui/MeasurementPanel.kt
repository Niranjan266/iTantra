package com.itantra.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.prosody.ProsodyExtractor
import com.itantra.session.SessionUiState

/**
 * Measurements and export (PRD F-51, F-53).
 *
 * The point of this panel is that the submission's numbers leave the phone as a file
 * rather than as a photograph of a screen. A judge can ask for the raw data, and there
 * is raw data.
 */
@Composable
fun MeasurementPanel(
    state: SessionUiState,
    recordCount: Int,
    summary: String,
    onExport: () -> Unit,
    selfTestResult: String,
    onSelfTest: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Measurements", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))

            Text(
                summary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )

            if (state.duplicatesSuppressed > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Duplicates suppressed: ${state.duplicatesSuppressed}",
                    fontSize = 11.sp,
                    color = Color.Gray,
                )
            }

            ProsodyRow(state)

            if (selfTestResult.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text("Speech self-test", fontSize = 11.sp, color = Color.Gray)
                Spacer(Modifier.height(2.dp))
                Text(
                    selfTestResult,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExport, enabled = recordCount > 0) {
                    Text("Export CSV ($recordCount)", fontSize = 13.sp)
                }
                OutlinedButton(onClick = onSelfTest) {
                    Text("Self-test", fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * How the last utterance was said (PRD F-08).
 *
 * Shown because it is otherwise invisible: three bytes that survive the trip and tilt
 * the far end's voice. Without this on screen there is no way to tell during a demo
 * whether prosody was measured or silently skipped.
 */
@Composable
private fun ProsodyRow(state: SessionUiState) {
    val p = state.prosody
    if (p.pitch == 0 && p.rate == 0 && p.energy == 0) return

    val hz = ProsodyExtractor.dequantisePitch(p.pitch)
    val rate = ProsodyExtractor.dequantiseRate(p.rate)
    val db = ProsodyExtractor.dequantiseEnergyDb(p.energy)

    Spacer(Modifier.height(8.dp))
    Text("Prosody sent (3 bytes)", fontSize = 11.sp, color = Color.Gray)
    Spacer(Modifier.height(2.dp))
    Text(
        buildString {
            append(hz?.let { "pitch %.0f Hz".format(it) } ?: "pitch —")
            append("  ·  ")
            append(rate?.let { "rate %.1f sym/s".format(it) } ?: "rate —")
            append("  ·  ")
            append(db?.let { "level %.0f dB".format(it) } ?: "level —")
        },
        fontSize = 12.sp,
    )
}
