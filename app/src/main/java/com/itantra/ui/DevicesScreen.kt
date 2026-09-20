package com.itantra.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.itantra.lang.LanguagePack
import com.itantra.session.Peer
import com.itantra.session.SessionUiState
import com.itantra.transport.PairedDevice
import com.itantra.transport.TransportState

/**
 * Devices — the Stitch "Nearby Devices" screen.
 *
 * The design shows a live radar scan, named phones with signal percentages, and distances
 * in metres. Most of that this app cannot honestly produce, and the differences are worth
 * being explicit about rather than faking:
 *
 *  - **Distance is not implemented and is not shown.** Deriving metres from BLE signal
 *    strength is unreliable to the point of being misleading — a phone in a pocket reads
 *    like one twice as far away — and this app has no location permission at all.
 *  - **Names come from Bluetooth pairing**, which is where Android actually keeps them.
 *    A device nobody has paired with has no name to show, so it is not invented.
 *  - **"Found within range" counts what the OS reports**, and reads zero when that is the
 *    truth rather than showing a plausible three.
 *
 * What the design gets exactly right, and this keeps, is the **link-type chooser**: the
 * Wi-Fi Direct / Bluetooth trade-off with its range and battery consequences stated in
 * plain words. That is a real decision a user in the field has to make.
 */
@Composable
fun DevicesScreen(
    state: SessionUiState,
    pairedDevices: List<PairedDevice>,
    peers: List<Peer>,
    packs: List<LanguagePack>,
    scanned: List<PairedDevice>,
    scanning: Boolean,
    onScan: () -> Unit,
    bluetoothReady: Boolean,
    selected: TransportChoice,
    onChoose: (TransportChoice) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onPair: (PairedDevice) -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Text("Nearby Devices", style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground)
        Text(
            "Choose a phone nearby to talk without the internet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(14.dp))

        if (!bluetoothReady) {
            OutlinedCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(MsIcons.Bluetooth, null, tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Switch Bluetooth on and allow nearby devices.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(Modifier.height(10.dp))
                PrimaryButton("Allow nearby devices", MsIcons.VerifiedUser, onRequestPermission)
            }
            Spacer(Modifier.height(14.dp))
        }

        // --- how to connect --------------------------------------------------------
        Text("How do you want to connect?", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))

        LinkOption(
            icon = MsIcons.NearMe,
            title = "Automatic · recommended",
            detail = "Finds iTantra phones over Wi-Fi and Bluetooth at once · no pairing",
            range = "Wi-Fi + BLE",
            chosen = selected is TransportChoice.Automatic,
            // Works with only one of the two: no Wi-Fi network still leaves BLE, and
            // Bluetooth off still leaves Wi-Fi.
            enabled = true,
            onClick = { onChoose(TransportChoice.Automatic) },
        )
        Spacer(Modifier.height(8.dp))
        LinkOption(
            icon = MsIcons.GraphicEq,
            title = "Sound",
            detail = "Tones from the speaker to the other phone's mic · no radio, " +
                "works in airplane mode or through a walkie-talkie",
            range = "a room · 47 bit/s",
            chosen = selected is TransportChoice.Sound,
            enabled = true,
            onClick = { onChoose(TransportChoice.Sound) },
        )
        Spacer(Modifier.height(8.dp))
        LinkOption(
            icon = MsIcons.Hub,
            title = "Wi-Fi Direct",
            detail = "The phones make their own network · no router, no internet",
            range = "any length",
            chosen = selected is TransportChoice.WifiDirect,
            enabled = true,
            onClick = { onChoose(TransportChoice.WifiDirect) },
        )
        Spacer(Modifier.height(8.dp))
        LinkOption(
            icon = MsIcons.WifiTethering,
            title = "Wi-Fi broadcast",
            detail = "Everyone on this Wi-Fi hears you · carries full sentences",
            range = "~1400 B",
            chosen = selected is TransportChoice.WifiBroadcast,
            // No Bluetooth needed. A phone's own hotspot counts as a network, so this
            // works with no router and no internet — but it does need Wi-Fi of some kind,
            // which the BLE bearer below does not.
            enabled = true,
            onClick = { onChoose(TransportChoice.WifiBroadcast) },
        )
        Spacer(Modifier.height(8.dp))
        LinkOption(
            icon = MsIcons.Podcasts,
            title = "Broadcast mesh",
            detail = "No pairing · every phone in range relays",
            range = "BLE broadcast",
            chosen = selected is TransportChoice.BleMesh,
            enabled = bluetoothReady,
            onClick = { onChoose(TransportChoice.BleMesh) },
        )
        Spacer(Modifier.height(8.dp))
        LinkOption(
            icon = MsIcons.Bluetooth,
            title = "Bluetooth direct",
            detail = "Uses less battery · one paired phone",
            range = "Up to ~15 m",
            chosen = selected is TransportChoice.BluetoothHost ||
                selected is TransportChoice.BluetoothJoin,
            enabled = bluetoothReady,
            onClick = { onChoose(TransportChoice.BluetoothHost) },
        )
        Spacer(Modifier.height(8.dp))
        LinkOption(
            icon = MsIcons.Sync,
            title = "This phone only",
            detail = "Loopback, for trying it out alone",
            range = "No radio",
            chosen = selected is TransportChoice.Loopback,
            enabled = true,
            onClick = { onChoose(TransportChoice.Loopback) },
        )

        Spacer(Modifier.height(18.dp))

        // --- who is actually out there ---------------------------------------------
        //
        // The list that answers "can anyone hear me". Every entry is a phone that sent a
        // beacon in the last few seconds on the link currently selected — not a paired
        // device, not a device that exists, one that is running this app right now.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (peers.isEmpty()) MsIcons.Radar else MsIcons.Group,
                null,
                tint = if (peers.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                else StatusReady,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (peers.isEmpty()) "Looking for other iTantra phones…"
                else "${peers.size} iTantra ${if (peers.size == 1) "phone" else "phones"} nearby",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        if (peers.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Everyone listed hears you the moment you talk. Each phone shows and " +
                    "speaks the message in the language its own owner chose.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))

        if (peers.isEmpty()) {
            OutlinedCard {
                Text(
                    if (state.transportState.isConnected)
                        "Nobody has announced themselves yet. Open iTantra on the other " +
                            "phone and choose the same connection above — each one calls " +
                            "out every few seconds."
                    else
                        "Choose a connection above first. Phones can only find each " +
                            "other once a link is up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            peers.forEach { peer ->
                PeerRow(peer, packs)
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(18.dp))

        // --- pairing ---------------------------------------------------------------
        //
        // Pairing CANNOT happen inside this app. Android reserves the bonding flow for its
        // own settings screen — an app may ask to connect to a bonded device, and may
        // discover unbonded ones, but the confirm-this-code exchange belongs to the OS.
        // Without a way through to it, a user told to "pair the phones" has nowhere to go,
        // which is exactly the dead end this screen used to be.
        OutlinedCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(MsIcons.Bluetooth, null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Pairing happens in Android settings",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        "Only needed for Bluetooth direct. The other three connections " +
                            "above need no pairing at all.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            PrimaryButton("Open Bluetooth settings", MsIcons.Settings, onOpenBluetoothSettings)
        }

        Spacer(Modifier.height(16.dp))

        // --- everything Bluetooth can see, paired or not ----------------------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Bluetooth devices in range",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (scanning) MaterialTheme.colorScheme.surfaceContainerHigh
                        else MaterialTheme.colorScheme.primary
                    )
                    .clickable { if (!scanning) onScan() }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(
                    if (scanning) "Searching…" else "Search",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (scanning) MaterialTheme.colorScheme.onSurfaceVariant
                    else Color.White,
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        if (scanned.isEmpty()) {
            OutlinedCard {
                Text(
                    // Says what a scan will and will not tell you. A Bluetooth scan finds
                    // devices, not apps: a phone showing up here may not be running
                    // iTantra at all, which is what the list above is for.
                    if (scanning) "Listening for anything with Bluetooth switched on. " +
                        "This takes about twelve seconds."
                    else "Tap Search to find phones nearby, including ones this phone has " +
                        "never paired with. A device appearing here is not necessarily " +
                        "running iTantra.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
        } else {
            val pairedAddresses = pairedDevices.map { it.address }.toSet()
            scanned.forEach { d ->
                val alreadyPaired = d.address in pairedAddresses
                OutlinedCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (d.couldBePeer) MsIcons.PhoneAndroid else MsIcons.Bluetooth,
                            null,
                            tint = if (d.couldBePeer) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                d.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                            )
                            Text(
                                when {
                                    alreadyPaired -> "Paired · join it below"
                                    d.couldBePeer -> "a phone or computer · not paired"
                                    else -> "an accessory"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // Only phones and computers get the button: pairing with earbuds
                        // cannot make them run iTantra, and would only clutter the list.
                        if (d.couldBePeer && !alreadyPaired) {
                            SmallAction(label = "Pair", enabled = true) { onPair(d) }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(10.dp))

        // --- paired phones ---------------------------------------------------------
        // Filter to plausible peers. Android's paired list is everything the phone has
        // ever bonded with — on the test handset that was 20 entries including four sets
        // of earbuds, a soundbar and a set-top box. Offering "Join" on headphones is an
        // invitation to a confusing failure, and this filter already existed on the older
        // screen; rebuilding to the design dropped it.
        val peers = pairedDevices.filter { it.couldBePeer }
        val hidden = pairedDevices.size - peers.size

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Paired phones", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.width(8.dp))
            Text(
                // The real count of plausible peers, and how many were filtered out, so
                // the number is never silently smaller than what Android shows.
                if (hidden > 0) "${peers.size}  ($hidden other devices hidden)"
                else "${peers.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))

        if (peers.isEmpty()) {
            OutlinedCard {
                Text(
                    "No paired phones yet. Tap Search above, then Pair on the other phone " +
                        "and confirm the code on both screens. Or use Automatic, Wi-Fi " +
                        "broadcast or BLE broadcast — those need no pairing at all.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            peers.forEach { device ->
                val connectedToThis = (state.transportState as? TransportState.Connected)
                    ?.peer?.contains(device.name, ignoreCase = true) == true
                Spacer(Modifier.height(8.dp))
                OutlinedCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(MsIcons.PhoneAndroid, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(device.name, style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onBackground, maxLines = 1)
                            Text(
                                if (connectedToThis) "Connected" else "Paired · tap to join",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (connectedToThis) StatusReady
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        SmallAction(
                            label = if (connectedToThis) "Joined" else "Join",
                            enabled = !connectedToThis,
                        ) { onChoose(TransportChoice.BluetoothJoin(device)) }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(MsIcons.VerifiedUser, null, tint = StatusReady,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "No mobile data, password or router needed. Nothing leaves these phones.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LinkOption(
    icon: ImageVector,
    title: String,
    detail: String,
    range: String,
    chosen: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (chosen) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(
            if (chosen) 0.dp else 1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (enabled) onClick() },
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            val fg = when {
                chosen -> Color.White
                !enabled -> MaterialTheme.colorScheme.outline
                else -> MaterialTheme.colorScheme.primary
            }
            Icon(icon, null, tint = fg, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (chosen) Color.White else MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (chosen) Color.White.copy(alpha = 0.85f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    MsIcons.Straighten, null,
                    tint = if (chosen) Color.White.copy(alpha = 0.85f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    range,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (chosen) Color.White.copy(alpha = 0.85f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (chosen) {
                Spacer(Modifier.width(8.dp))
                Icon(MsIcons.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
internal fun OutlinedCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) =
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) { Column(Modifier.padding(14.dp), content = content) }

@Composable
internal fun PrimaryButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary)
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White)
    }
}

@Composable
private fun SmallAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHigh
            )
            .clickable { if (enabled) onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One other phone running iTantra, heard live.
 *
 * Shows what it will actually take to talk to them — their language, so the user can see
 * whether a message will cross languages or arrive as-is — and how long since they were
 * last heard, because "nearby" is a claim with a shelf life.
 */
@Composable
private fun PeerRow(peer: Peer, packs: List<LanguagePack>) = OutlinedCard {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(StatusReady.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MsIcons.PhoneAndroid, null, tint = StatusReady, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                peer.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
            )
            Text(
                // The pack's own name where we have that language, so it reads in its own
                // script; the wire id otherwise, rather than an English guess at it.
                buildString {
                    append("speaks ")
                    append(packs.firstOrNull { it.id == peer.langId }?.displayName
                        ?: "language ${peer.langId}")
                    val ago = peer.secondsAgo()
                    append(if (ago <= 5) " · here now" else " · heard ${ago}s ago")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // There is no connect step to get wrong, and saying so is the point: this
            // phone is already reachable, and the listener picks the language they hear
            // on their own phone. A row that looked tappable and did nothing read as a
            // connection that had failed.
            Text(
                "Ready · nothing to connect, just hold the talk button",
                style = MaterialTheme.typography.labelSmall,
                color = StatusReady,
            )
        }
        Icon(MsIcons.Sensors, null, tint = StatusReady, modifier = Modifier.size(18.dp))
    }
}
