package com.itantra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.itantra.codec.Intent as MessageIntent
import com.itantra.codec.Translator
import com.itantra.lang.LanguagePack
import com.itantra.session.LoggedMessage

/**
 * Messages — the Stitch "Voice Messages History" screen.
 *
 * Shows what was said in the listener's language, with the sender's own wording beneath it
 * when a translation happened. The design shows saved audio clips with durations and
 * confidence percentages; this shows neither, and both omissions are deliberate:
 *
 *  - **No audio is stored, because no audio ever crossed the link.** That is the whole
 *    product. Replay re-synthesises from the packet — which is also why replaying an old
 *    message after switching language speaks it in the new one.
 *  - **No confidence score.** These recognisers do not emit a calibrated one, and a "99%"
 *    that was never measured is worse than no number at all.
 *
 * The list is in memory only and goes when the app closes; see the note on the log in
 * SessionController for why that is a choice rather than an omission.
 */
@Composable
fun MessagesScreen(
    messages: List<LoggedMessage>,
    packs: List<LanguagePack>,
    onReplay: (LoggedMessage) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Text(
            "Messages",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            if (messages.isEmpty()) "Nothing yet."
            else "${messages.size} in this session · kept on this phone only",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        if (messages.isEmpty()) {
            OutlinedCard {
                Text(
                    "Messages you send and receive appear here while the app is open. " +
                        "Nothing is written to storage.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(messages) { m -> MessageRow(m, packs, onReplay) }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun MessageRow(
    m: LoggedMessage,
    packs: List<LanguagePack>,
    onReplay: (LoggedMessage) -> Unit,
) = OutlinedCard {
    val accent = when (m.intent) {
        MessageIntent.DISTRESS -> UrgencyEmergency
        MessageIntent.ALERT -> UrgencyWarning
        else -> MaterialTheme.colorScheme.primary
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when (m.intent) {
                    MessageIntent.DISTRESS -> MsIcons.E911Emergency
                    MessageIntent.ALERT -> MsIcons.Warning
                    else -> if (m.incoming) MsIcons.Hearing else MsIcons.Mic
                },
                null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (m.incoming) "Received" else "Sent",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                // Language name from the pack, never a table of names in the app.
                (packs.firstOrNull { it.id == m.langId }?.displayName
                    ?: "language ${m.langId}") + " · ${m.wireBytes} B",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (m.kind) {
            Translator.Kind.TRANSLATED -> Badge(
                MsIcons.GTranslate, "Translated",
                MaterialTheme.colorScheme.primary, Color.White,
            )
            Translator.Kind.PHRASE_SAME_LANGUAGE -> Badge(
                MsIcons.OfflinePin, "Codebook",
                MaterialTheme.colorScheme.surfaceContainerHigh,
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Translator.Kind.UNRESOLVED -> Badge(
                MsIcons.Warning, "Unknown",
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
            )
            Translator.Kind.VERBATIM -> Unit
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(
        "“${m.text}”",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )

    m.original?.let {
        Spacer(Modifier.height(4.dp))
        Text(
            "“$it”",
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(10.dp))
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .pointerInput(m.atMs) { detectTapGestures(onTap = { onReplay(m) }) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            MsIcons.PlayArrow, null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        // "Speak", not "Play": there is no recording. It is synthesised on demand, in
        // whichever language is selected at that moment.
        Text(
            "Speak",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Badge(icon: ImageVector, label: String, container: Color, content: Color) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = content, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = content, maxLines = 1)
    }
}
