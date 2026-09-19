package com.itantra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The design's four destinations.
 *
 * Kept to exactly the four the Stitch screens show. Adding a fifth would be easy and
 * wrong: this is the navigation a non-reading user has to learn, and every extra
 * destination is another thing that can be the wrong one in an emergency.
 */
enum class Destination(val label: String, val icon: ImageVector) {
    HOME("Home", MsIcons.Home),
    MESSAGES("Messages", MsIcons.Chat),
    DEVICES("Devices", MsIcons.CellTower),

    /**
     * Settings, and the way to the technical view.
     *
     * That view is scaffolding for the submission's measurements rather than product, but
     * it has to be **reachable** — when Home was rebuilt to the design the gear went away
     * with it and the measurements screen became unreachable entirely.
     */
    SETTINGS("Settings", MsIcons.Settings),
}

@Composable
fun BottomNav(
    current: Destination,
    onSelect: (Destination) -> Unit,
    /** Unread count for the Messages badge, or 0 for none. */
    messageCount: Int = 0,
) {
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .navigationBarsPadding()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Destination.entries.forEach { d ->
                NavItem(
                    destination = d,
                    selected = d == current,
                    badge = if (d == Destination.MESSAGES) messageCount else 0,
                    onClick = { onSelect(d) },
                )
            }
        }
    }
}

@Composable
private fun NavItem(
    destination: Destination,
    selected: Boolean,
    badge: Int,
    onClick: () -> Unit,
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .semantics { contentDescription = destination.label },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Icon(destination.icon, null, tint = tint, modifier = Modifier.size(24.dp))
            if (badge > 0) {
                // A count, not a dot: "how many messages am I behind" is the question
                // someone returning to the phone actually has.
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.error),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (badge > 9) "9+" else badge.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onError,
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(destination.label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}
