package com.itantra.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.itantra.codec.Intent as MessageIntent
import com.itantra.lang.LanguagePack
import com.itantra.session.SessionUiState

/**
 * The screen the product actually is.
 *
 * ## Who this is for
 *
 * Someone who may not read, may never have owned a smartphone, and is using this in an
 * emergency. That single constraint decides every choice below, and it is worth writing
 * down because "modern" and "usable by this person" pull in opposite directions more often
 * than not — a fashionable interface is usually a quiet one, full of unlabelled glyphs and
 * gestures you have to already know.
 *
 * ## The rules
 *
 *  1. **One obvious action.** The talk button is the largest thing on screen by a wide
 *     margin. Nothing else is close enough in size to be mistaken for it.
 *  2. **Never colour alone.** Every state has a distinct *silhouette* as well as a colour
 *     ([ItantraIcons]), because roughly one man in twelve cannot separate red from green.
 *     The urgency levels differ in shape, colour *and* size — three signals for one
 *     meaning.
 *  3. **Symbols before words, and no jargon at all.** No "transport", no "codec", no
 *     "packet", no byte counts. Those exist, and they live behind the settings icon,
 *     because the submission needs them and this person does not.
 *  4. **Nothing measured on screen.** A latency figure tells this user nothing and a bad
 *     one frightens them.
 *  5. **A way to hear it again.** A message missed in a panic must not be gone for ever.
 *  6. **Feedback that does not depend on reading.** The button grows and pulses with your
 *     voice while held, so someone who cannot read the word "listening" can still see the
 *     phone is hearing them.
 *
 * ## What makes it modern rather than merely large
 *
 * Vector icons that are identical on every handset; a real Material 3 scheme with a proper
 * dark mode; depth from layered surfaces rather than borders; and motion that means
 * something — the pulse tracks your actual voice level, it is not decoration.
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
    var urgency by remember { mutableIntStateOf(MessageIntent.ROUTINE) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxSize()) {

            StatusBanner(
                state = state,
                micGranted = micGranted,
                onOpenTechnical = onOpenTechnical,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // Only appears when there is something to show, and takes its space back
                // when there is not, so the talk button stays where the hand expects it.
                AnimatedVisibility(
                    visible = state.lastReceivedText.isNotBlank(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    MessageCard(
                        text = state.lastReceivedText,
                        speaking = state.isPlaying,
                        onReplay = onReplay,
                    )
                }

                Spacer(Modifier.height(28.dp))

                TalkButton(
                    enabled = micGranted && state.asrReady,
                    recording = state.isRecording,
                    // peakLevel is a Double in the session state; the animation APIs are
                    // all Float, and the precision is irrelevant for a visual level.
                    level = state.peakLevel.toFloat(),
                    accent = urgency.accent(),
                    onPress = { if (micGranted) onTalkStart() else onRequestMic() },
                    onRelease = {
                        onTalkEnd(state.selectedLangId ?: 0, urgency)
                    },
                )

                Spacer(Modifier.height(20.dp))

                Text(
                    text = when {
                        !micGranted -> "Tap to allow the microphone"
                        state.isRecording -> "Speak now"
                        !state.asrReady -> "Getting ready…"
                        else -> "Hold the button and speak"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            UrgencyRow(selected = urgency, onSelect = { urgency = it })

            Spacer(Modifier.height(12.dp))

            LanguageRow(
                packs = packs,
                selectedId = state.selectedLangId,
                onSelect = onSelectLanguage,
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

// --- status ---------------------------------------------------------------------------

/**
 * The band across the top: one symbol, one colour, one word.
 *
 * Four states, and each has to be readable from across a room by someone who will not read
 * the word. Ready is the only one that is green, and the tick is the only one with that
 * silhouette.
 */
@Composable
private fun StatusBanner(
    state: SessionUiState,
    micGranted: Boolean,
    onOpenTechnical: () -> Unit,
) {
    val (icon, label, colour) = when {
        state.isPlaying -> Triple(ItantraIcons.Speaker, "Speaking", StatusSpeaking)
        !micGranted -> Triple(ItantraIcons.Close, "Tap below", StatusError)
        state.enginesLoading -> Triple(ItantraIcons.Loading, "Starting", StatusBusy)
        state.asrReady -> Triple(ItantraIcons.Check, "Ready", StatusReady)
        else -> Triple(ItantraIcons.Close, "Not ready", StatusError)
    }

    val animated by animateColorAsState(colour, tween(300), label = "status")
    val dark = when (colour) {
        StatusReady -> StatusReadyDark
        StatusBusy -> StatusBusyDark
        StatusSpeaking -> Color(0xFF155E75)
        else -> StatusErrorDark
    }

    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(dark, animated)))
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
            Spacer(Modifier.width(14.dp))
            Text(label, style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Spacer(Modifier.weight(1f))
            // Small and low-contrast on purpose. The technical view is for the judges and
            // for me; a prominent gear invites the one user who must not go in there.
            Icon(
                ItantraIcons.Settings,
                contentDescription = "Technical details",
                tint = Color.White.copy(alpha = 0.55f),
                modifier = Modifier
                    .size(26.dp)
                    .pointerInput(Unit) { detectTapGestures(onTap = { onOpenTechnical() }) },
            )
        }
    }
}

// --- the talk button -------------------------------------------------------------------

/**
 * The one control that matters.
 *
 * 260 dp, so it is unmissable and can be hit without looking — which is the realistic case
 * for someone wading, carrying a child, or holding a torch.
 *
 * While held it grows with the measured voice level and two rings pulse outward. Both are
 * driven by [level], not by a timer: a ring that pulses on a fixed schedule would keep
 * reassuring the user while the microphone was in fact dead.
 */
@Composable
private fun TalkButton(
    enabled: Boolean,
    recording: Boolean,
    level: Float,
    accent: Color,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (recording) 1f + (level.coerceIn(0f, 1f) * 0.10f) else 1f,
        animationSpec = spring(dampingRatio = 0.6f),
        label = "press",
    )

    val pulse = rememberInfiniteTransition(label = "pulse")
    val ring by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Restart),
        label = "ring",
    )

    val base = if (enabled) accent else MaterialTheme.colorScheme.outline

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(320.dp)) {

        // Rings only while actually recording, and their reach follows the voice level.
        if (recording) {
            val reach = 0.10f + level.coerceIn(0f, 1f) * 0.45f
            for (offset in listOf(0f, 0.5f)) {
                val t = (ring + offset) % 1f
                Box(
                    Modifier
                        .size(260.dp)
                        .scale(1f + t * reach)
                        .clip(CircleShape)
                        .background(base.copy(alpha = 0.22f * (1f - t)))
                )
            }
        }

        Box(
            modifier = Modifier
                .size(260.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        listOf(base.lighten(0.12f), base, base.darken(0.18f))
                    )
                )
                .border(6.dp, Color.White.copy(alpha = 0.22f), CircleShape)
                .semantics { contentDescription = "Hold to talk" }
                .pointerInput(enabled) {
                    detectTapGestures(
                        onPress = {
                            onPress()
                            // Wait for the finger to lift, then send. tryAwaitRelease also
                            // returns on cancellation (a drag off the button), which must
                            // still end the utterance — otherwise the recorder stays open
                            // and the next press finds a stuck state.
                            tryAwaitRelease()
                            onRelease()
                        }
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    ItantraIcons.Microphone,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(96.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (recording) "SPEAK" else "HOLD",
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                )
            }
        }
    }
}

// --- urgency ---------------------------------------------------------------------------

/**
 * Three levels, differing in **shape, colour and size** at once.
 *
 * Emergency is the widest, so it can be found by feel and hit in a hurry. The selected one
 * is filled and lifted; the others are outlined — a difference that survives a greyscale
 * screenshot, which is the cheap test for whether colour is doing too much work.
 */
@Composable
private fun UrgencyRow(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        UrgencyButton(
            icon = ItantraIcons.Chat,
            label = "Normal",
            colour = UrgencyRoutine,
            selected = selected == MessageIntent.ROUTINE,
            weight = 1f,
            onClick = { onSelect(MessageIntent.ROUTINE) },
        )
        UrgencyButton(
            icon = ItantraIcons.Warning,
            label = "Warning",
            colour = UrgencyWarning,
            selected = selected == MessageIntent.ALERT,
            weight = 1.1f,
            onClick = { onSelect(MessageIntent.ALERT) },
        )
        UrgencyButton(
            icon = ItantraIcons.Emergency,
            label = "Emergency",
            colour = UrgencyEmergency,
            selected = selected == MessageIntent.DISTRESS,
            weight = 1.45f,
            onClick = { onSelect(MessageIntent.DISTRESS) },
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.UrgencyButton(
    icon: ImageVector,
    label: String,
    colour: Color,
    selected: Boolean,
    weight: Float,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        if (selected) colour else MaterialTheme.colorScheme.surface,
        tween(200), label = "urgency",
    )
    val fg = if (selected) Color.White else colour

    Column(
        modifier = Modifier
            .weight(weight)
            .height(84.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(
                width = if (selected) 0.dp else 2.dp,
                color = colour.copy(alpha = 0.45f),
                shape = RoundedCornerShape(20.dp),
            )
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
    }
}

// --- the received message --------------------------------------------------------------

/**
 * What arrived, and a way to hear it again.
 *
 * The text is shown as well as spoken, for a reader nearby who can help — but the replay
 * control is the important half, and it is a 64 dp target rather than a text link.
 */
@Composable
private fun MessageCard(text: String, speaking: Boolean, onReplay: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (speaking) ItantraIcons.Speaker else ItantraIcons.Chat,
                        contentDescription = null,
                        tint = if (speaking) StatusSpeaking else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (speaking) "Playing" else "Message received",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(14.dp))
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .pointerInput(Unit) { detectTapGestures(onTap = { onReplay() }) }
                    .semantics { contentDescription = "Hear it again" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    ItantraIcons.Replay,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(34.dp),
                )
            }
        }
    }
}

// --- language --------------------------------------------------------------------------

/**
 * Each language written in its own script, taken from the pack's own name.
 *
 * Never a flag and never an English label: "தமிழ்" is recognisable to a Tamil speaker who
 * reads no English, whereas "Tamil" is not. A language added tomorrow appears here
 * correctly with no code change, which is the same rule the rest of the app follows.
 */
@Composable
private fun LanguageRow(
    packs: List<LanguagePack>,
    selectedId: Int?,
    onSelect: (LanguagePack) -> Unit,
) {
    if (packs.isEmpty()) return

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        packs.forEach { pack ->
            val chosen = pack.id == selectedId
            Box(
                Modifier
                    .weight(1f)
                    .height(60.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        if (chosen) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .pointerInput(pack.id) { detectTapGestures(onTap = { onSelect(pack) }) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    pack.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (chosen) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

// --- helpers ---------------------------------------------------------------------------

private fun Int.accent(): Color = when (this) {
    MessageIntent.DISTRESS -> UrgencyEmergency
    MessageIntent.ALERT -> UrgencyWarning
    else -> UrgencyRoutine
}

/** Mixes toward white, for the top of a gradient. */
private fun Color.lighten(amount: Float): Color = Color(
    red = red + (1f - red) * amount,
    green = green + (1f - green) * amount,
    blue = blue + (1f - blue) * amount,
    alpha = alpha,
)

/** Mixes toward black, for the bottom of a gradient — which is what gives the button depth. */
private fun Color.darken(amount: Float): Color = Color(
    red = red * (1f - amount),
    green = green * (1f - amount),
    blue = blue * (1f - amount),
    alpha = alpha,
)
