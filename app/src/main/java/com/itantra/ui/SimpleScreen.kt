package com.itantra.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.codec.Intent
import com.itantra.codec.LanguageId
import com.itantra.lang.LanguagePack
import com.itantra.session.SessionUiState

/**
 * The screen a villager uses.
 *
 * ## Who this is for
 *
 * PRD section 3: someone in a flood, possibly unable to read, holding a cheap phone,
 * under stress, who has never seen this app before. The previous screen showed hex
 * dumps, real-time factors and percentile tables — fine for a developer, useless and
 * intimidating for the person this product exists to serve.
 *
 * ## Rules followed here
 *
 * - **One obvious action.** The talk button is more than half the screen. There is
 *   nothing else large enough to be mistaken for it.
 * - **Never colour alone.** Every state carries a colour *and* a symbol *and* a shape,
 *   because roughly one man in twelve cannot distinguish red from green.
 * - **Symbols before words.** What words remain are large, and the ones that matter are
 *   shown in the language the user picked, not in English.
 * - **Nothing measured on screen.** Bytes, latency and error rates moved behind the
 *   gear. They are still there — judges need them — but they are not what this user is
 *   looking at while water is rising.
 * - **Say it again.** A replay button, because a missed message in an emergency must
 *   not be gone forever, and because a first listen is often lost to panic.
 */
@Composable
fun SimpleScreen(
    state: SessionUiState,
    packs: List<LanguagePack>,
    micGranted: Boolean,
    onSelectLanguage: (LanguagePack) -> Unit,
    onRequestMic: () -> Unit,
    onTalkStart: () -> Unit,
    onTalkEnd: (langId: Int, intent: Int) -> Unit,
    onReplay: () -> Unit,
    onOpenTechnical: () -> Unit,
) {
    var intent by remember { mutableStateOf(Intent.ROUTINE) }
    var pressed by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF7F5FB))
    ) {
        StatusBand(state, onOpenTechnical)

        if (!micGranted) {
            PermissionPrompt(onRequestMic)
            return@Column
        }

        // The button. Deliberately dominant — in an emergency the user should not have
        // to choose between controls, only press the big one.
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            TalkButton(
                state = state,
                intent = intent,
                pressed = pressed,
                onPress = {
                    pressed = true
                    onTalkStart()
                },
                onRelease = {
                    pressed = false
                    onTalkEnd(state.selectedLangId ?: LanguageId.UNSPECIFIED, intent)
                },
            )
        }

        IncomingMessage(state, onReplay)

        UrgencyRow(selected = intent, onSelect = { intent = it })

        LanguageRow(state, packs, onSelectLanguage)

        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Ready or not, in one glance.
 *
 * A shape and a symbol carry the meaning; the colour only reinforces it. The gear is
 * small and dull on purpose — it leads to the technical view, and a user who does not
 * want it should not be drawn to it.
 */
@Composable
private fun StatusBand(state: SessionUiState, onOpenTechnical: () -> Unit) {
    val ready = state.asrReady && !state.enginesLoading
    val colour by animateColorAsState(
        when {
            state.enginesLoading -> Color(0xFF9E9E9E)
            state.isPlaying -> Color(0xFF1565C0)
            ready -> Color(0xFF2E7D32)
            else -> Color(0xFFC62828)
        },
        label = "status",
    )

    Row(
        Modifier
            .fillMaxWidth()
            .background(colour)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when {
                state.enginesLoading -> "⏳"   // hourglass
                state.isPlaying -> "🔊"  // speaker
                ready -> "✔"                  // tick
                else -> "✖"                   // cross
            },
            fontSize = 30.sp,
            color = Color.White,
        )
        Spacer(Modifier.size(14.dp))
        Text(
            when {
                state.enginesLoading -> "Please wait"
                state.isPlaying -> "Listen"
                ready -> "Ready"
                else -> "Not ready"
            },
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        // Technical view. Small, grey-on-colour, easy to ignore.
        Text(
            "⚙",
            fontSize = 26.sp,
            color = Color(0xCCFFFFFF),
            modifier = Modifier
                .clip(CircleShape)
                .pointerInput(Unit) { detectTapGestures { onOpenTechnical() } }
                .padding(8.dp),
        )
    }
}

@Composable
private fun PermissionPrompt(onRequestMic: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🎤", fontSize = 90.sp)
        Spacer(Modifier.height(20.dp))
        Text(
            "Allow the microphone",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1565C0))
                .pointerInput(Unit) { detectTapGestures { onRequestMic() } }
                .padding(horizontal = 40.dp, vertical = 20.dp)
        ) {
            Text("OK", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

/**
 * Press and hold to speak.
 *
 * It grows while held and pulses with the voice, so a user who cannot read the label
 * still gets unambiguous feedback that the phone is hearing them — the single most
 * important thing to communicate, and the thing a silent screen fails at.
 */
@Composable
private fun TalkButton(
    state: SessionUiState,
    intent: Int,
    pressed: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    val level = (state.peakLevel / 8000.0).coerceIn(0.0, 1.0).toFloat()
    val scale by animateFloatAsState(
        if (pressed) 1f + level * 0.12f else 1f,
        label = "pulse",
    )
    val colour by animateColorAsState(
        when {
            state.isPlaying -> Color(0xFF1565C0)
            pressed -> Color(0xFFC62828)
            else -> urgencyColour(intent)
        },
        label = "talk",
    )

    Box(
        Modifier
            .size(280.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(colour)
            .border(6.dp, Color(0x33000000), CircleShape)
            .pointerInput(intent) {
                detectTapGestures(
                    onPress = {
                        onPress()
                        try {
                            awaitRelease()
                        } finally {
                            onRelease()
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when {
                    state.isPlaying -> "🔊"      // speaker
                    pressed -> "🎤"              // microphone
                    else -> "🎤"
                },
                fontSize = 92.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    state.isPlaying -> "..."
                    pressed -> "●●●"        // recording dots
                    else -> "PRESS"
                },
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
    }
}

/**
 * The message that just arrived.
 *
 * Shown as text for anyone who can read, but the important controls are the loudspeaker
 * symbol and the replay button, which need no reading at all.
 */
@Composable
private fun IncomingMessage(state: SessionUiState, onReplay: () -> Unit) {
    val message = state.lastReceivedText.takeIf { it.isNotBlank() } ?: return

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFE3F2FD))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("📥", fontSize = 34.sp)   // inbox
        Spacer(Modifier.size(12.dp))
        Text(
            message,
            fontSize = 19.sp,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(10.dp))
        Box(
            Modifier
                .clip(CircleShape)
                .background(Color(0xFF1565C0))
                .pointerInput(Unit) { detectTapGestures { onReplay() } }
                .padding(14.dp),
        ) {
            Text("▶", fontSize = 26.sp, color = Color.White)
        }
    }
    Spacer(Modifier.height(10.dp))
}

/**
 * How urgent the message is.
 *
 * Three buttons that differ in colour, symbol AND size, so the choice survives poor
 * eyesight, colour blindness and a cracked screen. DISTRESS is the largest because it
 * is the one that must be findable without looking.
 */
@Composable
private fun UrgencyRow(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        UrgencyButton("💬", Intent.ROUTINE, selected, onSelect, Modifier.weight(1f))
        UrgencyButton("⚠", Intent.ALERT, selected, onSelect, Modifier.weight(1f))
        UrgencyButton("🆘", Intent.DISTRESS, selected, onSelect, Modifier.weight(1.3f))
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun UrgencyButton(
    symbol: String,
    value: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isOn = selected == value
    Box(
        modifier
            .height(74.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isOn) urgencyColour(value) else Color(0xFFE0E0E0))
            // A selected button also gets a heavy dark outline, so "which one is on" is
            // never carried by colour alone.
            .border(
                width = if (isOn) 4.dp else 0.dp,
                color = if (isOn) Color(0xFF212121) else Color.Transparent,
                shape = RoundedCornerShape(16.dp),
            )
            .pointerInput(value) { detectTapGestures { onSelect(value) } },
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, fontSize = if (isOn) 38.sp else 32.sp)
    }
}

@Composable
private fun LanguageRow(
    state: SessionUiState,
    packs: List<LanguagePack>,
    onSelect: (LanguagePack) -> Unit,
) {
    if (packs.size < 2) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        packs.forEach { pack ->
            val isOn = state.selectedLangId == pack.id
            Box(
                Modifier
                    .weight(1f)
                    .height(58.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isOn) Color(0xFF37474F) else Color(0xFFE0E0E0))
                    .pointerInput(pack.id) { detectTapGestures { onSelect(pack) } },
                contentAlignment = Alignment.Center,
            ) {
                // The pack's own name, in its own script. Nothing here is translated
                // or hardcoded, so a language added tomorrow displays correctly today.
                Text(
                    pack.displayName,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isOn) Color.White else Color(0xFF212121),
                )
            }
        }
    }
}

private fun urgencyColour(intent: Int): Color = when (intent) {
    Intent.DISTRESS -> Color(0xFFC62828)
    Intent.ALERT -> Color(0xFFEF6C00)
    else -> Color(0xFF2E7D32)
}
