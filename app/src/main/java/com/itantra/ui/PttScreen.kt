package com.itantra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.codec.Intent
import com.itantra.codec.LanguageId
import com.itantra.lang.LanguagePack
import com.itantra.session.SessionMode
import com.itantra.session.SessionUiState
import com.itantra.transport.PairedDevice

/**
 * The walkie-talkie screen.
 *
 * Phase 1 deliberately shows the machinery: the hex dump and the byte counter are on
 * screen, not hidden behind a debug flag. The central claim of this project is a number
 * — "that sentence crossed the link in 61 bytes" — and a claim you cannot see is a
 * claim you cannot demonstrate.
 *
 * Phase 3 replaces the text field with the microphone. The button and the readout stay.
 */
@Composable
fun PttScreen(
    state: SessionUiState,
    transportChoice: TransportChoice,
    pairedDevices: List<PairedDevice>,
    bluetoothReady: Boolean,
    onChooseTransport: (TransportChoice) -> Unit,
    onRequestPermission: () -> Unit,
    onRequestMic: () -> Unit,
    micGranted: Boolean,
    packs: List<LanguagePack>,
    onSelectLanguage: (LanguagePack) -> Unit,
    onChooseBearer: (Int?) -> Unit,
    recordCount: Int,
    measurementSummary: String,
    onExportCsv: () -> Unit,
    selfTestResult: String,
    onSelfTest: () -> Unit,
    onSetMode: (SessionMode) -> Unit,
    onTalkStart: () -> Unit,
    onTransmit: (text: String, langId: Int, intent: Int) -> Unit,
) {
    // The fallback sentence follows the SELECTED LANGUAGE, from that pack's own self-test
    // phrase. It used to be a hard-coded English string whatever language was chosen,
    // which is wrong for a Tamil speaker on its face — and it also meant the fallback
    // could never match the Tamil codebook, so a Tamil sender always paid full price for
    // a sentence that had a three-byte reference sitting in the book.
    val pack = packs.firstOrNull { it.id == state.selectedLangId }
    var text by remember(pack?.id) {
        mutableStateOf(
            pack?.selfTestPhrase?.takeIf { it.isNotBlank() }
                ?: "Flood water is rising near the school, send boats"
        )
    }
    var intent by remember { mutableStateOf(Intent.ROUTINE) }
    var pressed by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("iTantra", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            "${state.selectedLangName} · session ${state.sessionId}",
            fontSize = 12.sp,
            color = Color.Gray,
        )

        Spacer(Modifier.height(12.dp))

        LanguagePicker(state = state, packs = packs, onSelect = onSelectLanguage)

        Spacer(Modifier.height(12.dp))

        ConnectionBar(
            selected = transportChoice,
            pairedDevices = pairedDevices,
            bluetoothReady = bluetoothReady,
            statusText = state.transportState.describe(),
            bearerBps = state.bearerBps,
            onChoose = onChooseTransport,
            onChooseBearer = onChooseBearer,
            onRequestPermission = onRequestPermission,
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Fallback text — used only if nothing is recognised") },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        // Both modes the problem statement asks for. Push-to-talk is the default: it is
        // half-duplex, holds no microphone open, and the button is an unambiguous
        // end-of-utterance signal.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SessionMode.entries.forEach { m ->
                FilterChip(
                    selected = state.mode == m,
                    onClick = { onSetMode(m) },
                    label = { Text(m.label(), fontSize = 12.sp) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Intent.ROUTINE, Intent.ALERT, Intent.DISTRESS).forEach { level ->
                FilterChip(
                    selected = intent == level,
                    onClick = { intent = level },
                    label = { Text(Intent.nameOf(level), fontSize = 12.sp) },
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // Push to talk. Held, not tapped — this is the walkie-talkie contract.
        Box(
            modifier = Modifier
                .size(180.dp)
                .clip(CircleShape)
                .background(
                    when {
                        pressed -> Color(0xFFD32F2F)
                        state.mode == SessionMode.PHONE && state.speechDetected ->
                            Color(0xFF2E7D32)
                        state.mode == SessionMode.PHONE -> Color(0xFF455A64)
                        intent == Intent.DISTRESS -> Color(0xFFEF6C00)
                        else -> Color(0xFF1565C0)
                    }
                )
                // In phone mode the microphone is already open and the detector decides
                // where sentences end, so the button becomes a status light rather than
                // a control. Keying it here would start a second capture on top of the
                // running one.
                .pointerInput(text, intent, state.mode) {
                    if (state.mode == SessionMode.PHONE) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            onTalkStart()
                            try {
                                awaitRelease()
                            } finally {
                                pressed = false
                                // The language comes from the loaded pack, never from
                                // a constant here. A hardcoded id would label every
                                // packet with one language regardless of what was
                                // spoken, and the receiver would render it wrongly.
                                onTransmit(
                                    text,
                                    state.selectedLangId ?: LanguageId.UNSPECIFIED,
                                    intent,
                                )
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when {
                    state.isPlaying -> "RECEIVING\n♪"
                    state.mode == SessionMode.PHONE && state.speechDetected -> "HEARING\nYOU"
                    state.mode == SessionMode.PHONE -> "PHONE MODE\nlistening"
                    state.isRecording && state.speechDetected -> "LISTENING\n●"
                    pressed -> "RECORDING\n…"
                    else -> "HOLD\nTO TALK"
                },
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(12.dp))
        AudioStatus(state, micGranted, onRequestMic)

        Spacer(Modifier.height(24.dp))

        MetricsHud(state)

        Spacer(Modifier.height(12.dp))

        MeasurementPanel(
            state = state,
            recordCount = recordCount,
            summary = measurementSummary,
            onExport = onExportCsv,
            selfTestResult = selfTestResult,
            onSelfTest = onSelfTest,
        )

        Spacer(Modifier.height(16.dp))

        if (state.lastSentHex.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Bytes on the wire", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        state.lastSentHex,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (state.lastReceivedText.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "Received (${state.lastReceivedBytes} bytes)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(state.lastReceivedText, fontSize = 15.sp)
                    state.lastReceivedPacket?.let { p ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${LanguageId.debugNameOf(p.langId)} · ${Intent.nameOf(p.intent)} · seq ${p.seq}",
                            fontSize = 11.sp,
                            color = Color.Gray,
                        )
                    }
                }
            }
        }

        state.lastError?.let { err ->
            Spacer(Modifier.height(12.dp))
            Text(err, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }

        Spacer(Modifier.height(32.dp))
    }
}
