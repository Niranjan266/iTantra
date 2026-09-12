package com.itantra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.session.SessionUiState

/**
 * Microphone status and audio measurements (PRD F-50).
 *
 * Frames dropped is on screen rather than in a log because "zero dropped frames" is a
 * claim that appears in the submission, and a claim that cannot be checked during the
 * demonstration is not worth making.
 */
@Composable
fun AudioStatus(
    state: SessionUiState,
    micGranted: Boolean,
    onRequestMic: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Microphone", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))

            if (!micGranted) {
                Text(
                    "Microphone permission is needed to record.",
                    fontSize = 12.sp,
                    color = Color.Gray,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = onRequestMic) {
                    Text("Grant microphone permission", fontSize = 13.sp)
                }
                return@Column
            }

            LevelMeter(state.peakLevel, active = state.isRecording)

            Spacer(Modifier.height(10.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Stat("Captured", "${state.capturedMs} ms")
                Stat("Frames", state.framesCaptured.toString())
                Stat(
                    "Dropped",
                    state.framesDropped.toString(),
                    warn = state.framesDropped > 0,
                )
            }

            state.endpointDelayMs?.let {
                Spacer(Modifier.height(8.dp))
                Text("Endpoint decided in $it ms", fontSize = 12.sp, color = Color.Gray)
            }

            Spacer(Modifier.height(10.dp))
            SpeechStatus(state)

            state.micError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * Recognition state and the words that came out (PRD F-50).
 *
 * The transcript is shown even though neither user of the finished product ever sees
 * text — during development and on the demo table it is the only way to tell a
 * recognition failure apart from a transport failure.
 */
@Composable
private fun SpeechStatus(state: SessionUiState) {
    when {
        state.enginesLoading -> {
            Text("Loading speech models…", fontSize = 12.sp, color = Color.Gray)
            return
        }

        state.engineError != null -> {
            Text(
                "Speech models unavailable: ${state.engineError}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                "Transport, codec and alerting still work.",
                fontSize = 11.sp,
                color = Color.Gray,
            )
            return
        }
    }

    val loaded = state.engineLoadMs?.let { " · loaded in %.1f s".format(it / 1000.0) } ?: ""
    Text(
        "ASR ${if (state.asrReady) "✓" else "✗"} ${state.asrName}   ·   " +
            "TTS ${if (state.ttsReady) "✓" else "✗"} ${state.rendererName}$loaded",
        fontSize = 11.sp,
        color = Color.Gray,
    )

    val live = state.partialText.takeIf { it.isNotBlank() }
    val done = state.recognisedText.takeIf { it.isNotBlank() }

    if (live != null && state.isRecording) {
        Spacer(Modifier.height(6.dp))
        Text("“$live”", fontSize = 14.sp, color = Color(0xFF1565C0))
    } else if (done != null) {
        Spacer(Modifier.height(6.dp))
        Text("Heard: “$done”", fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }

    if (state.asrMs != null) {
        Spacer(Modifier.height(4.dp))
        val rtf = state.asrRtf?.let { " · RTF %.2f".format(it) } ?: ""
        Text(
            "Recognition finished in %.0f ms%s".format(state.asrMs, rtf),
            fontSize = 11.sp,
            color = Color.Gray,
        )
    }
}

@Composable
private fun Stat(label: String, value: String, warn: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = if (warn) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
        Text(label, fontSize = 11.sp, color = Color.Gray)
    }
}

/**
 * Peak input level. 16-bit audio peaks at 32767, but speech rarely exceeds a few
 * thousand RMS, so the bar is scaled to that range to stay readable rather than
 * sitting near zero the whole time.
 */
@Composable
private fun LevelMeter(peakRms: Double, active: Boolean) {
    val fraction = (peakRms / 8000.0).coerceIn(0.0, 1.0).toFloat()
    Box(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0xFFE0E0E0))
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(10.dp)
                .background(if (active) Color(0xFF2E7D32) else Color(0xFF9E9E9E))
        )
    }
}
