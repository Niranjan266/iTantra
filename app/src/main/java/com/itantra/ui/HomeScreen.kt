package com.itantra.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.itantra.codec.Translator
import com.itantra.lang.LanguagePack
import com.itantra.session.SessionUiState
import com.itantra.transport.TransportState

/**
 * Home — the Stitch "Home (Push to Talk)" screen.
 *
 * Laid out to match the design, and **wired to real state throughout**: every badge,
 * count and label below is read from the session rather than hard-coded. Where the design
 * shows something this app cannot know — a peer's name, their distance in metres, a GPS
 * sector — the element is either driven by what we do know or left out. A screen that
 * displays a confident "50m away" it invented is worse than one that says nothing.
 *
 * The pieces the design specifies and this implements:
 *
 *  - status header with the green **Offline Ready** pill
 *  - a connection card naming the live link
 *  - the **LIVE TRANSLATION** card — see [TranslationCard]; this one is real, and free
 *  - the large **Hold to Talk** button, pulsing with measured voice level
 *  - the received-message card, showing both wordings when a translation happened
 *  - a mesh card fed by the relay's own counters
 */
@Composable
fun HomeScreen(
    state: SessionUiState,
    packs: List<LanguagePack>,
    micGranted: Boolean,
    urgency: Int,
    onUrgency: (Int) -> Unit,
    onSelectLanguage: (LanguagePack) -> Unit,
    onRequestMic: () -> Unit,
    onTalkStart: () -> Unit,
    onTalkEnd: (langId: Int, intent: Int) -> Unit,
    onReplay: () -> Unit,
) {
    val scroll = rememberScrollState()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))

        StatusHeader(state = state, micGranted = micGranted)

        Spacer(Modifier.height(10.dp))
        RoleBanner(state)

        Spacer(Modifier.height(10.dp))
        ConnectionCard(state)

        Spacer(Modifier.height(12.dp))
        TranslationCard(state = state, packs = packs, onSelectLanguage = onSelectLanguage)

        Spacer(Modifier.height(24.dp))

        Text(
            text = when {
                !micGranted -> "Allow the microphone"
                state.isRecording -> "Listening…"
                !state.asrReady -> "Getting ready…"
                else -> "Ready to talk?"
            },
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (state.isRecording) "Release when you're finished"
            else "Press & hold the button to speak",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(16.dp))

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TalkButton(
                enabled = micGranted && state.asrReady,
                recording = state.isRecording,
                level = state.peakLevel.toFloat(),
                accent = urgencyColour(urgency),
                onPress = { if (micGranted) onTalkStart() else onRequestMic() },
                onRelease = { onTalkEnd(state.selectedLangId ?: 0, urgency) },
            )
        }

        Spacer(Modifier.height(16.dp))
        UrgencyRow(selected = urgency, onSelect = onUrgency)

        AnimatedVisibility(
            visible = state.lastReceivedText.isNotBlank(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column {
                Spacer(Modifier.height(16.dp))
                MessageCard(state = state, onReplay = onReplay)
            }
        }

        Spacer(Modifier.height(16.dp))
        MeshCard(state)

        Spacer(Modifier.height(24.dp))
    }
}

// --- header ----------------------------------------------------------------------------

@Composable
private fun StatusHeader(state: SessionUiState, micGranted: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MsIcons.Podcasts, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text("iTantra", style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.width(10.dp))

        val ready = micGranted && state.asrReady
        Pill(
            icon = if (ready) MsIcons.OfflineBolt else MsIcons.Sync,
            // The design's pill says "Offline Ready". It stays honest here because the app
            // declares no INTERNET permission at all — there is nothing to be online for.
            label = when {
                state.isPlaying -> "Speaking"
                ready -> "Offline Ready"
                state.enginesLoading -> "Starting"
                !micGranted -> "Needs mic"
                else -> "Not ready"
            },
            container = if (ready) StatusReady else MaterialTheme.colorScheme.surfaceContainerHigh,
            content = if (ready) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Pill(icon: ImageVector, label: String, container: Color, content: Color) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = content, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = content, maxLines = 1)
    }
}

/** The design's card: white, hairline outline, 18 dp corners. Depth from the outline and
 *  the surface step, not from a shadow — which is how the Stitch screens are built. */
@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) = Surface(
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = Modifier.fillMaxWidth(),
) {
    Column(Modifier.padding(14.dp), content = content)
}

// --- connection ------------------------------------------------------------------------

@Composable
private fun ConnectionCard(state: SessionUiState) = Card {
    val st = state.transportState
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (st.isConnected) MsIcons.Link else MsIcons.WifiFind,
            null,
            tint = if (st.isConnected) StatusReady else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            // The peer's own name, from the transport. The design shows a person's name;
            // this shows whatever the link actually reports, which for a broadcast bearer
            // is "anyone in range" — true, where a fabricated name would not be.
            when (st) {
                is TransportState.Connected -> "Connected to ${st.peer}"
                is TransportState.Connecting -> "Connecting…"
                is TransportState.Failed -> st.reason
                else -> "Not connected"
            },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(MsIcons.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            "Local ${state.transportName} · internet not required",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- translation -----------------------------------------------------------------------

/**
 * The design's headline feature, and it is genuinely free.
 *
 * "You speak Tamil / they hear English" is not a translation model running here — it is
 * the consequence of sending an **index into a list both ends agree on**. The same 16-byte
 * packet arrives and each phone renders line 33 of its own codebook. See
 * [com.itantra.codec.Translator].
 *
 * The honesty this card owes the user is about **coverage**, not mechanism: only phrases
 * in the codebook translate, so the subtitle states how many this language actually has
 * rather than implying everything said will cross languages.
 */
@Composable
private fun TranslationCard(
    state: SessionUiState,
    packs: List<LanguagePack>,
    onSelectLanguage: (LanguagePack) -> Unit,
) = Card {
    val mine = packs.firstOrNull { it.id == state.selectedLangId }
    val theirs = state.lastDelivery?.let { d ->
        packs.firstOrNull { it.id == d.fromLangId }
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(MsIcons.GTranslate, null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("LIVE TRANSLATION", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        val phrases = mine?.phrases?.translatedCount ?: 0
        Pill(
            icon = if (phrases > 0) MsIcons.Check else MsIcons.Close,
            label = if (phrases > 0) "$phrases phrases" else "none yet",
            container = if (phrases > 0) StatusReady
            else MaterialTheme.colorScheme.surfaceContainerHigh,
            content = if (phrases > 0) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(10.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        LangPane(
            caption = "You speak, and are heard as",
            name = mine?.displayName ?: "—",
            modifier = Modifier.weight(1f),
        )
        Icon(MsIcons.SyncAlt, null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp).padding(horizontal = 2.dp))
        LangPane(
            caption = "They hear, in their own choice",
            // Deliberately not a promise about the far end. THE LISTENER decides what
            // they hear, by picking their own language on their own phone — this device
            // cannot and should not choose for them. Before a message arrives we do not
            // even know what they picked.
            name = theirs?.displayName ?: "whatever they chose",
            modifier = Modifier.weight(1f),
        )
    }

    if (packs.size > 1) {
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            packs.forEach { pack ->
                val chosen = pack.id == state.selectedLangId
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (chosen) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                        .pointerInput(pack.id) {
                            detectTapGestures(onTap = { onSelectLanguage(pack) })
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        pack.displayName,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (chosen) Color.White
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun LangPane(caption: String, name: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(10.dp),
    ) {
        Text(caption, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(name, style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground, maxLines = 1)
    }
}

// --- talk button -----------------------------------------------------------------------

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
        if (recording) 1f + (level.coerceIn(0f, 1f) * 0.10f) else 1f,
        spring(dampingRatio = 0.6f), label = "press",
    )
    val pulse = rememberInfiniteTransition(label = "pulse")
    val ring by pulse.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "ring",
    )
    val base = if (enabled) accent else MaterialTheme.colorScheme.outline

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(260.dp)) {
        // Rings track the measured voice level, not a timer. A ring that pulsed on a
        // schedule would keep reassuring the user while the microphone was dead.
        if (recording) {
            val reach = 0.10f + level.coerceIn(0f, 1f) * 0.45f
            for (offset in listOf(0f, 0.5f)) {
                val t = (ring + offset) % 1f
                Box(
                    Modifier
                        .size(200.dp)
                        .scale(1f + t * reach)
                        .clip(CircleShape)
                        .background(base.copy(alpha = 0.20f * (1f - t)))
                )
            }
        }
        Box(
            Modifier
                .size(200.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(base.lighten(0.10f), base)))
                .border(5.dp, Color.White.copy(alpha = 0.30f), CircleShape)
                .semantics { contentDescription = "Hold to talk" }
                .pointerInput(enabled) {
                    detectTapGestures(onPress = {
                        onPress()
                        // tryAwaitRelease also returns on cancellation (a drag off the
                        // button), which must still end the utterance — otherwise the
                        // recorder stays open and the next press finds a stuck state.
                        tryAwaitRelease()
                        onRelease()
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    if (recording) MsIcons.GraphicEq else MsIcons.Mic,
                    null, tint = Color.White, modifier = Modifier.size(64.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (recording) "Speaking" else "Hold to Talk",
                    style = MaterialTheme.typography.titleMedium, color = Color.White,
                )
            }
        }
    }
}

// --- urgency ---------------------------------------------------------------------------

@Composable
private fun UrgencyRow(selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        UrgencyChip(MsIcons.Chat, "Normal", UrgencyRoutine,
            selected == com.itantra.codec.Intent.ROUTINE, 1f,
            { onSelect(com.itantra.codec.Intent.ROUTINE) })
        UrgencyChip(MsIcons.Warning, "Warning", UrgencyWarning,
            selected == com.itantra.codec.Intent.ALERT, 1.1f,
            { onSelect(com.itantra.codec.Intent.ALERT) })
        UrgencyChip(MsIcons.E911Emergency, "Emergency", UrgencyEmergency,
            selected == com.itantra.codec.Intent.DISTRESS, 1.4f,
            { onSelect(com.itantra.codec.Intent.DISTRESS) })
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.UrgencyChip(
    icon: ImageVector,
    label: String,
    colour: Color,
    selected: Boolean,
    weight: Float,
    onClick: () -> Unit,
) {
    // Shape, colour AND size all differ per level, so the three stay distinguishable for
    // the roughly one man in twelve who cannot separate red from green — and in a
    // greyscale screenshot, which is the cheap test for that.
    Column(
        Modifier
            .weight(weight)
            .height(68.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) colour else MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(
                if (selected) 0.dp else 1.5.dp,
                colour.copy(alpha = 0.5f),
                RoundedCornerShape(14.dp),
            )
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val fg = if (selected) Color.White else colour
        Icon(icon, null, tint = fg, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
    }
}

// --- received message ------------------------------------------------------------------

@Composable
private fun MessageCard(state: SessionUiState, onReplay: () -> Unit) = Card {
    val d = state.lastDelivery

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(
            if (state.isPlaying) MsIcons.VolumeUp else MsIcons.RecordVoiceOver,
            null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (state.isPlaying) "Speaking aloud now…" else "Message received",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        // The badge states exactly what happened. "Translated" over a message that merely
        // arrived as plain text would be a claim the user cannot check.
        when (d?.kind) {
            Translator.Kind.TRANSLATED -> Pill(
                MsIcons.GTranslate, "Translated", MaterialTheme.colorScheme.primary, Color.White,
            )
            Translator.Kind.PHRASE_SAME_LANGUAGE -> Pill(
                MsIcons.OfflinePin, "Codebook",
                MaterialTheme.colorScheme.surfaceContainerHigh,
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Translator.Kind.UNRESOLVED -> Pill(
                MsIcons.Warning, "Unknown phrase",
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
            )
            else -> Pill(
                MsIcons.Hearing, "As spoken",
                MaterialTheme.colorScheme.surfaceContainerHigh,
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(
        "“${state.lastReceivedText}”",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )

    // The sender's own wording, when a translation happened. A bilingual reader checking
    // the two against each other is a real safety feature for a distress message.
    d?.original?.let { original ->
        Spacer(Modifier.height(6.dp))
        Text(
            "“$original”",
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(12.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary)
            .pointerInput(Unit) { detectTapGestures(onTap = { onReplay() }) }
            .semantics { contentDescription = "Hear it again" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(MsIcons.PlayArrow, null, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text("Play voice", style = MaterialTheme.typography.labelLarge, color = Color.White)
    }
}

// --- mesh ------------------------------------------------------------------------------

@Composable
private fun MeshCard(state: SessionUiState) = Card {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MsIcons.Hub, null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (state.transportName.contains("mesh", ignoreCase = true)) "Local mesh active"
                else "Direct link",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                // Real counters, not decoration: sent / received / dropped come from the
                // session. The design says "3 nearby relays"; this app cannot count
                // neighbours on a connectionless bearer, so it reports what it does know.
                "${state.sentCount} sent · ${state.receivedCount} received" +
                    if (state.errorCount > 0) " · ${state.errorCount} failed" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Pill(
            MsIcons.VerifiedUser, "Peer-to-peer",
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- helpers ---------------------------------------------------------------------------

private fun urgencyColour(intent: Int): Color = when (intent) {
    com.itantra.codec.Intent.DISTRESS -> UrgencyEmergency
    com.itantra.codec.Intent.ALERT -> UrgencyWarning
    else -> UrgencyRoutine
}

private fun Color.lighten(amount: Float) = Color(
    red + (1f - red) * amount,
    green + (1f - green) * amount,
    blue + (1f - blue) * amount,
    alpha,
)

// --- who is speaking, who is listening ---------------------------------------------------

/**
 * Which end of the conversation this phone is, right now.
 *
 * Push-to-talk has no fixed speaker and no fixed receiver — every phone is both, and which
 * one it is changes the moment somebody presses the button. That is obvious to whoever is
 * holding the phone and invisible to everyone else, which is why this band exists: on a
 * desk with two handsets it is otherwise impossible to tell which one is transmitting.
 *
 * Three states, each with its own colour *and* its own symbol, on the rule used throughout
 * this app that colour alone never carries meaning:
 *
 *  - **Speaking** — this phone has the button held and is capturing;
 *  - **Playing** — a message arrived and is being spoken aloud here;
 *  - **Listening** — the resting state, which is most of the time.
 */
@Composable
private fun RoleBanner(state: SessionUiState) {
    val (icon, title, detail, colour) = when {
        state.isRecording -> Quad(
            MsIcons.Mic,
            "You are SPEAKING",
            "Everyone listening will hear this in their own language",
            UrgencyEmergency,
        )
        state.isPlaying -> Quad(
            MsIcons.VolumeUp,
            "PLAYING a message",
            "Spoken in the language you chose on this phone",
            StatusSpeaking,
        )
        else -> Quad(
            MsIcons.Hearing,
            "You are LISTENING",
            "Hold the button to speak",
            MaterialTheme.colorScheme.primary,
        )
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colour,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Color.White)
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.9f),
                )
            }
        }
    }
}

/** Four values from a `when`, without a data class per use site. */
private data class Quad(
    val icon: ImageVector,
    val title: String,
    val detail: String,
    val colour: Color,
)
