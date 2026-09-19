package com.itantra.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.transport.LinkBudget
import com.itantra.transport.PairedDevice

/** Which link the user has asked for. Resolved into a Transport by the activity. */
sealed class TransportChoice {
    object Loopback : TransportChoice()
    object BluetoothHost : TransportChoice()
    data class BluetoothJoin(val device: PairedDevice) : TransportChoice()

    /**
     * Connectionless BLE broadcast with relay (see [com.itantra.transport.BleMeshTransport]).
     *
     * Listed beside the paired options although it is a different shape of thing: it needs
     * no pairing and has no single peer, so it is the only choice here that can reach a
     * phone whose owner you have never met.
     */
    object BleMesh : TransportChoice()

    /**
     * Everyone on the same Wi-Fi, over multicast.
     *
     * Carries ~1400 bytes rather than BLE's 24, so free speech travels and not only
     * codebook phrases — at the cost of needing a Wi-Fi network (a phone's own hotspot
     * counts) and the INTERNET permission Android demands for any socket.
     */
    object WifiBroadcast : TransportChoice()

    /**
     * Wi-Fi Direct: the phones make their own network, no router and no internet.
     *
     * The only bearer here that needs no existing infrastructure AND carries a full
     * spoken sentence. Android negotiates which phone hosts, so unlike Bluetooth direct
     * there is no way for both users to pick the same role and end up with neither
     * listening.
     */
    object WifiDirect : TransportChoice()

    /**
     * Wi-Fi broadcast and BLE broadcast at the same time
     * (see [com.itantra.transport.CompositeTransport]).
     *
     * The default, because it removes the guess: two phones find each other whether or
     * not they share a Wi-Fi network, and a sentence too long for BLE still travels over
     * Wi-Fi. Every other choice is a way of restricting this one.
     */
    object Automatic : TransportChoice()

    /**
     * Tone bursts from the speaker to the microphone
     * (see [com.itantra.transport.AcousticTransport]).
     *
     * No radio at all: works in airplane mode, and through any voice radio held to the
     * phone. Slow — a phrase takes under three seconds — and audible by design.
     */
    object Sound : TransportChoice()

    fun label(): String = when (this) {
        Loopback -> "Loopback"
        BluetoothHost -> "Bluetooth host"
        is BluetoothJoin -> device.name
        BleMesh -> "BLE mesh"
        WifiBroadcast -> "Wi-Fi broadcast"
        WifiDirect -> "Wi-Fi Direct"
        Automatic -> "Automatic"
        Sound -> "Sound"
    }
}

/**
 * Link selection (PRD F-13).
 *
 * Collapsed by default. This is not cosmetic: a phone that has been in use for a while
 * has a dozen paired headsets, and an always-open list pushed the push-to-talk button
 * completely off the screen — found the first time this ran on a real handset rather
 * than a clean test device. The talk button is the one control that must always be
 * reachable.
 *
 * Audio accessories are hidden by default for the same reason. A speaker cannot run
 * iTantra, so listing it only makes the real peer harder to find. "Show all" stays
 * available because device classes are not always reported correctly.
 *
 * Pairing itself is left to Android's Bluetooth settings — it is a one-time
 * provisioning step, and rebuilding it here would earn no marks while adding something
 * else that can fail on stage.
 */
@Composable
fun ConnectionBar(
    selected: TransportChoice,
    pairedDevices: List<PairedDevice>,
    bluetoothReady: Boolean,
    statusText: String,
    bearerBps: Int?,
    onChoose: (TransportChoice) -> Unit,
    onChooseBearer: (Int?) -> Unit,
    onRequestPermission: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }

    val visibleDevices = if (showAll) pairedDevices else pairedDevices.filter { it.couldBePeer }
    val hiddenCount = pairedDevices.size - visibleDevices.size

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Link · ${selected.label()}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(statusText, fontSize = 12.sp, color = Color.Gray)
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Done" else "Change", fontSize = 13.sp)
                }
            }

            if (!expanded) return@Column

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selected is TransportChoice.Loopback,
                    onClick = { onChoose(TransportChoice.Loopback) },
                    label = { Text("Loopback", fontSize = 12.sp) },
                )
                FilterChip(
                    selected = selected is TransportChoice.BluetoothHost,
                    onClick = { onChoose(TransportChoice.BluetoothHost) },
                    enabled = bluetoothReady,
                    label = { Text("Host", fontSize = 12.sp) },
                )
                FilterChip(
                    selected = selected is TransportChoice.BleMesh,
                    onClick = { onChoose(TransportChoice.BleMesh) },
                    enabled = bluetoothReady,
                    label = { Text("Mesh", fontSize = 12.sp) },
                )
            }

            if (selected is TransportChoice.BleMesh) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Broadcast to every iTantra phone in range — no pairing. Each one " +
                        "repeats the message, so it travels further than one radio reaches. " +
                        "Only codebook phrases fit a broadcast; longer messages are refused " +
                        "rather than cut short.",
                    fontSize = 11.sp,
                    color = Color.Gray,
                )
            }

            Spacer(Modifier.height(8.dp))

            when {
                !bluetoothReady -> {
                    Text(
                        "Switch Bluetooth on and grant permission to connect two phones.",
                        fontSize = 12.sp,
                        color = Color.Gray,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = onRequestPermission) {
                        Text("Grant Bluetooth permission", fontSize = 13.sp)
                    }
                }

                pairedDevices.isEmpty() -> Text(
                    "No paired devices. Pair the two phones once in Android's " +
                        "Bluetooth settings, then return here.",
                    fontSize = 12.sp,
                    color = Color.Gray,
                )

                visibleDevices.isEmpty() -> Text(
                    "No paired phones. $hiddenCount audio accessories are hidden — " +
                        "pair the second phone, or tap Show all.",
                    fontSize = 12.sp,
                    color = Color.Gray,
                )

                else -> {
                    Text("Join a paired device:", fontSize = 12.sp, color = Color.Gray)
                    Spacer(Modifier.height(4.dp))
                    // Capped height: the list can never push the talk button away again.
                    Column(
                        Modifier
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        visibleDevices.forEach { device ->
                            val isSelected = (selected as? TransportChoice.BluetoothJoin)
                                ?.device?.address == device.address
                            FilterChip(
                                selected = isSelected,
                                onClick = { onChoose(TransportChoice.BluetoothJoin(device)) },
                                label = { Text(device.name, fontSize = 12.sp) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // The narrow-link demonstration (PRD F-52). Clamping the link to a real
            // bearer rate is the whole evidence for the long-distance claim now that
            // the project ships no radio hardware.
            Text("Simulated bearer", fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LinkBudget.PRESETS.forEach { preset ->
                    FilterChip(
                        selected = bearerBps == preset.bitsPerSecond,
                        onClick = { onChooseBearer(preset.bitsPerSecond) },
                        label = { Text(preset.label, fontSize = 12.sp) },
                    )
                }
            }

            if (hiddenCount > 0) {
                TextButton(onClick = { showAll = !showAll }) {
                    Text(
                        if (showAll) "Hide audio accessories"
                        else "Show all ($hiddenCount hidden)",
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}
