package com.itantra

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
// Aliased: this project has its own Intent (message urgency), which collides with
// android.content.Intent. The alias keeps both readable in this file.
import com.itantra.codec.Intent as MessageIntent
import com.itantra.codec.LanguageId
import com.itantra.codec.Symbols
import com.itantra.lang.CatalogueEntry
import com.itantra.lang.LanguageCatalogue
import com.itantra.lang.LanguagePack
import com.itantra.lang.PackDownloader
import com.itantra.session.SessionController
import com.itantra.session.SessionMode
import com.itantra.transport.BluetoothDevices
import com.itantra.transport.BluetoothScanner
import com.itantra.transport.BleMeshTransport
import com.itantra.transport.BluetoothRfcommTransport
import com.itantra.transport.WifiDirectTransport
import com.itantra.transport.WifiMulticastTransport
import com.itantra.transport.FloodRelay
import com.itantra.transport.RepeatSender
import com.itantra.transport.LoopbackTransport
import com.itantra.transport.PairedDevice
import com.itantra.transport.ThrottleWrapper
import com.itantra.ui.ItantraTheme
import com.itantra.ui.PttScreen
import com.itantra.ui.BottomNav
import com.itantra.ui.Destination
import com.itantra.ui.DevicesScreen
import com.itantra.ui.HomeScreen
import com.itantra.ui.MeshDiagnosticsScreen
import com.itantra.ui.MessagesScreen
import com.itantra.ui.SettingsScreen
import com.itantra.ui.TransportChoice
import kotlinx.coroutines.launch

/**
 * The screen.
 *
 * Deliberately thin. It renders state and forwards taps; it owns nothing. The session,
 * its transport and its speech models live in [ITantraApp] and outlive this Activity,
 * so locking the screen or rotating the phone cannot interrupt a transmission or
 * abandon a model load — see the note in that class for the bug that motivated it.
 *
 * This is also the only place that knows which concrete transport is in use. Everything
 * else works through the interface (TRD section 1.1).
 */
class MainActivity : ComponentActivity() {

    private val app: ITantraApp get() = application as ITantraApp
    private val session: SessionController get() = app.session

    private var transportChoice by mutableStateOf<TransportChoice>(TransportChoice.Loopback)
    private var bluetoothReady by mutableStateOf(false)
    private var micGranted by mutableStateOf(false)
    private val pairedDevices = mutableStateListOf<PairedDevice>()

    /** A live Bluetooth scan, started only when the user asks for one. */
    private val scanner by lazy { BluetoothScanner(applicationContext) }
    private val packs = mutableStateListOf<LanguagePack>()

    /** Languages that can be added, and the state of any download in progress. */
    private val available = mutableStateListOf<CatalogueEntry>()

    /** Installed languages whose voice is missing or outdated, by code. */
    private var voiceUpdates by mutableStateOf<Map<String, CatalogueEntry>>(emptyMap())
    private var downloading by mutableStateOf<String?>(null)
    private var downloadProgress by mutableStateOf(0f)
    private var downloadNote by mutableStateOf("")

    private var selfTestResult by mutableStateOf("")

    /**
     * Which screen is showing.
     *
     * The simple screen is the product; the technical one is scaffolding that happens
     * to be needed for the submission's measurements. Defaulting to simple means the
     * person this is built for never has to know the other one exists.
     */
    private var technicalView by mutableStateOf(false)

    /** The design's Mesh Diagnostics screen. The older PttScreen stays behind it. */
    private var meshDiagnostics by mutableStateOf(false)

    /** Which of the design's four destinations is showing. */
    private var destination by mutableStateOf(Destination.HOME)

    /** Urgency for the next transmission. Lifted here so Home and the technical view agree. */
    private var urgency by mutableStateOf(MessageIntent.ROUTINE)

    /** Simulated bearer rate, or null for an unthrottled link (PRD F-52). */
    private var bearerBps by mutableStateOf<Int?>(null)

    /**
     * Relay and repetition, on by default for the broadcast bearer and off for the others.
     *
     * Relaying a point-to-point link achieves nothing — there is only one peer and it
     * already heard the message — so it is not forced on every transport.
     */
    private var meshEnabled by mutableStateOf(false)

    /**
     * The live relay, so its counters can be shown.
     *
     * Held as state rather than read through the transport chain: the diagnostics screen
     * needs the numbers from the exact relay currently in use, and a transport switch
     * replaces it.
     */
    private var relay by mutableStateOf<FloodRelay?>(null)
    private var meshRelayed by mutableStateOf(0)
    private var meshSuppressed by mutableStateOf(0)
    private var repeatCopies by mutableStateOf(3)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPermissions()

        // Come back up on the link the user last chose, rather than on the loopback that
        // only talks to itself. See [rememberChoice] for why this matters so much.
        restoreChoice()?.let(::chooseTransport)

        setContent {
            ItantraTheme {
                Surface {
                    val state by session.ui.collectAsState()
                    val messages by session.messages.collectAsState()
                    val peers by session.peers.collectAsState()
                    val scanned by scanner.found.collectAsState()
                    val scanning by scanner.scanning.collectAsState()

                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) {
                            when {
                                meshDiagnostics -> MeshDiagnosticsScreen(
                                    state = state,
                                    transportName = state.transportName,
                                    meshRelayed = relay?.relayedCount ?: 0,
                                    meshSuppressed = relay?.suppressedCount ?: 0,
                                    onLoopbackTest = { technicalView = true; meshDiagnostics = false },
                                    onBack = { meshDiagnostics = false },
                                )

                                // The older technical view is kept behind the new one: it
                                // carries the wire-format dump, CSV export and self-test
                                // that the submission's measurements come from.
                                technicalView -> PttScreen(
                                    state = state,
                                    transportChoice = transportChoice,
                                    pairedDevices = pairedDevices,
                                    bluetoothReady = bluetoothReady,
                                    micGranted = micGranted,
                                    onChooseTransport = ::chooseTransport,
                                    onRequestPermission = {
                                        permissionLauncher.launch(
                                            BluetoothDevices.requiredPermissions()
                                        )
                                    },
                                    onRequestMic = {
                                        permissionLauncher.launch(
                                            arrayOf(Manifest.permission.RECORD_AUDIO)
                                        )
                                    },
                                    packs = packs,
                                    onSelectLanguage = { pack ->
                                        app.appScope.launch { session.selectLanguage(pack) }
                                    },
                                    onChooseBearer = { bps ->
                                        bearerBps = bps
                                        rebuildTransport()
                                    },
                                    recordCount = state.sentCount,
                                    measurementSummary = session.summary(),
                                    onExportCsv = ::exportCsv,
                                    selfTestResult = selfTestResult,
                                    onSelfTest = {
                                        app.appScope.launch {
                                            selfTestResult = "Running self-test…"
                                            selfTestResult = session.runSelfTest(
                                                "flood water is rising near the school send boats"
                                            )
                                        }
                                    },
                                    onSetMode = { m ->
                                        app.appScope.launch {
                                            session.setMode(
                                                m,
                                                state.selectedLangId ?: LanguageId.UNSPECIFIED,
                                                MessageIntent.ROUTINE,
                                            )
                                        }
                                    },
                                    onTalkStart = { session.startTalking() },
                                    onTransmit = { text, langId, intent ->
                                        app.appScope.launch {
                                            session.stopTalkingAndTransmit(langId, intent, text)
                                        }
                                    },
                                )

                                destination == Destination.HOME -> HomeScreen(
                                    state = state,
                                    packs = packs,
                                    micGranted = micGranted,
                                    urgency = urgency,
                                    onUrgency = { urgency = it },
                                    onSelectLanguage = { pack ->
                                        app.appScope.launch { session.selectLanguage(pack) }
                                    },
                                    onRequestMic = {
                                        permissionLauncher.launch(
                                            arrayOf(Manifest.permission.RECORD_AUDIO)
                                        )
                                    },
                                    onTalkStart = { session.startTalking() },
                                    onTalkEnd = { langId, intent ->
                                        app.appScope.launch {
                                            session.stopTalkingAndTransmit(langId, intent, "")
                                        }
                                    },
                                    onReplay = { session.replayLast() },
                                )

                                destination == Destination.MESSAGES -> MessagesScreen(
                                    messages = messages,
                                    packs = packs,
                                    onReplay = { session.speak(it) },
                                )

                                destination == Destination.DEVICES -> DevicesScreen(
                                    state = state,
                                    pairedDevices = pairedDevices,
                                    peers = peers,
                                    packs = packs,
                                    scanned = scanned,
                                    scanning = scanning,
                                    onScan = { scanner.start() },
                                    onOpenBluetoothSettings = ::openBluetoothSettings,
                                    bluetoothReady = bluetoothReady,
                                    selected = transportChoice,
                                    onChoose = ::chooseTransport,
                                    onRequestPermission = {
                                        permissionLauncher.launch(
                                            BluetoothDevices.requiredPermissions()
                                        )
                                    },
                                )

                                else -> SettingsScreen(
                                    state = state,
                                    packs = packs,
                                    available = available,
                                    downloading = downloading,
                                    downloadProgress = downloadProgress,
                                    downloadNote = downloadNote,
                                    onDownload = ::downloadLanguage,
                                    voiceUpdates = voiceUpdates,
                                    onUpdateVoice = ::downloadVoice,
                                    bearerBps = bearerBps,
                                    onSelectLanguage = { pack ->
                                        app.appScope.launch { session.selectLanguage(pack) }
                                    },
                                    onChooseBearer = { bps ->
                                        bearerBps = bps
                                        rebuildTransport()
                                    },
                                    onOpenTechnical = { meshDiagnostics = true },
                                )
                            }
                        }

                        // Always visible, including over the technical view — otherwise
                        // that view has no way out, which is how the gear going away left
                        // it unreachable in the first place.
                        BottomNav(
                            current = destination,
                            onSelect = {
                                destination = it
                                technicalView = false
                                meshDiagnostics = false
                            },
                            messageCount = messages.size,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have paired a device, or switched Bluetooth on, while away.
        refreshPermissions()
    }

    /**
     * Remember the link the user picked, so the next launch does not silently fall back.
     *
     * This was the whole of "it does not send from one mobile to another". The default is
     * [TransportChoice.Loopback], which echoes every frame back to the phone that sent it
     * — so after restarting the app, a message showed up as **both** Sent and Received on
     * the speaker's own screen, looking exactly like success, while no radio was involved
     * and the other phone heard nothing. A transport that quietly forgets itself and fails
     * in a way indistinguishable from working is worse than one that fails loudly.
     *
     * Only the radio-free and router-free choices are restored. A Bluetooth link is not:
     * it names one specific paired device that may be off, out of range or unpaired by
     * now, and reconnecting to it unasked at launch would hang the screen on a link the
     * user has not chosen for this conversation.
     */
    private fun rememberChoice(choice: TransportChoice) {
        val key = when (choice) {
            is TransportChoice.WifiBroadcast -> "wifi-broadcast"
            is TransportChoice.WifiDirect -> "wifi-direct"
            is TransportChoice.BleMesh -> "ble-mesh"
            is TransportChoice.Loopback -> "loopback"
            // Bluetooth deliberately not remembered — see above.
            else -> return
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LINK, key).apply()
    }

    private fun restoreChoice(): TransportChoice? =
        when (getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LINK, null)) {
            "wifi-broadcast" -> TransportChoice.WifiBroadcast
            "wifi-direct" -> TransportChoice.WifiDirect
            "ble-mesh" -> TransportChoice.BleMesh
            else -> null
        }

    private fun chooseTransport(choice: TransportChoice) {
        transportChoice = choice
        rememberChoice(choice)
        // The broadcast bearer is the only one where relaying adds reach, so selecting it
        // turns the mesh on rather than leaving the user to find a second switch.
        // Relaying adds reach on any one-to-many bearer, and achieves nothing on a
        // point-to-point link where the single peer already heard the message.
        // Relay adds reach on a one-to-many bearer. Wi-Fi Direct is a two-party link, so
        // relaying it would only duplicate what the single peer already received.
        meshEnabled = choice is TransportChoice.BleMesh ||
            choice is TransportChoice.WifiBroadcast
        if (choice is TransportChoice.BleMesh && !BleMeshTransport.hasPermission(this)) {
            permissionLauncher.launch(BleMeshTransport.requiredPermissions())
        }
        if (choice is TransportChoice.WifiDirect) {
            // NEARBY_WIFI_DEVICES on Android 13+, the location permission before that —
            // Wi-Fi Direct discovery is gated on one or the other.
            val needed = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (needed.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
                permissionLauncher.launch(needed)
            }
        }
        rebuildTransport()
    }

    /**
     * Build the transport for the current link and bearer choice.
     *
     * The throttle is a decorator, so the two choices compose: any link can be run at
     * any simulated bearer rate without either knowing about the other.
     */
    private fun rebuildTransport() {
        val base = when (val choice = transportChoice) {
            is TransportChoice.Loopback ->
                LoopbackTransport()

            is TransportChoice.BluetoothHost ->
                BluetoothRfcommTransport(
                    context = applicationContext,
                    scope = app.appScope,
                    role = BluetoothRfcommTransport.Role.Host,
                )

            is TransportChoice.BluetoothJoin ->
                BluetoothRfcommTransport(
                    context = applicationContext,
                    scope = app.appScope,
                    role = BluetoothRfcommTransport.Role.Join(choice.device.address),
                )

            is TransportChoice.BleMesh ->
                BleMeshTransport(context = applicationContext, scope = app.appScope)

            is TransportChoice.WifiBroadcast ->
                WifiMulticastTransport(context = applicationContext, scope = app.appScope)

            is TransportChoice.WifiDirect ->
                WifiDirectTransport(context = applicationContext, scope = app.appScope)
        }

        // The decorators compose, and the order is deliberate:
        //
        //   SessionController → RepeatSender → FloodRelay → Throttle → bearer
        //
        // RepeatSender outermost, so each copy it sends is relayed on its own merits and
        // duplicates are suppressed by the relay rather than reaching the speaker.
        // FloodRelay above the throttle, so a simulated narrow bearer also throttles the
        // rebroadcasts — otherwise the narrow-link demonstration would quietly exempt the
        // mesh traffic it is supposed to measure.
        val throttled = bearerBps?.let { ThrottleWrapper(base, it) } ?: base
        val next = if (meshEnabled) {
            val r = FloodRelay(throttled, app.appScope)
            relay = r
            RepeatSender(r, app.appScope, copies = repeatCopies)
        } else {
            relay = null
            throttled
        }
        // Application scope: a connection attempt must survive the screen turning off.
        app.appScope.launch {
            session.switchTransport(next)
            // Announce this phone on whatever link is now up, so the other phones can
            // list it. Restarted per switch: a beacon on the old bearer reaches nobody.
            session.startAnnouncing(deviceName())
        }
    }

    /**
     * A short name for this phone, for presence beacons.
     *
     * Android's own device name where the user has set one, since that is the name they
     * already recognise, and the model otherwise. Truncated hard: the beacon has eight
     * bytes for a name so it still fits a legacy BLE advertisement.
     */
    private fun deviceName(): String {
        val settings = runCatching {
            android.provider.Settings.Global.getString(contentResolver, "device_name")
        }.getOrNull()
        // Trimming happens in encodePresence, by bytes — doing it here by characters is
        // what produced an oversized beacon.
        return settings?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
    }

    /**
     * Hand the user to Android's own Bluetooth screen.
     *
     * Bonding is not something an app can do: the confirm-this-code exchange belongs to
     * the OS. Telling someone to pair two phones without a way to get there is a dead end,
     * and this was one.
     */
    private fun openBluetoothSettings() {
        val opened = runCatching {
            startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
            true
        }.getOrDefault(false)
        if (!opened) {
            // Some vendor builds hide the Bluetooth screen behind a different action.
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
                .onFailure {
                    Toast.makeText(
                        this,
                        "Could not open settings — pair the phones from Android's " +
                            "Bluetooth screen.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    private fun refreshPermissions() {
        micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val hasBluetooth = BluetoothDevices.hasPermission(this)
        bluetoothReady = hasBluetooth &&
            BluetoothDevices.isAvailable(this) &&
            BluetoothDevices.isEnabled(this)

        pairedDevices.clear()
        if (hasBluetooth) pairedDevices.addAll(BluetoothDevices.paired(this))

        // Re-scan on every resume: a pack copied onto the device while the app was in
        // the background appears without a reinstall (PRD section 10.5).
        packs.clear()
        packs.addAll(app.refreshPacks())
        // The session needs every pack, not just the selected one, so a received message
        // can also be shown in the sender's language.
        session.installedPacks = packs.toList()

        available.clear()
        available.addAll(LanguageCatalogue.available(this, packs))
        voiceUpdates = LanguageCatalogue.voiceUpdates(this, packs)
    }

    /**
     * Write the measurements to a file and offer it to any app that can take it.
     *
     * A file, not a screenshot: the submission claims measured numbers, so the raw
     * rows have to be able to leave the device and be checked.
     */
    /**
     * Fetch a language, then make it usable without a restart.
     *
     * On the application scope: a 188 MB download must survive the screen turning off,
     * and abandoning it half way would leave the user paying for the bytes twice.
     */
    private fun downloadLanguage(entry: CatalogueEntry) {
        if (downloading != null) return
        downloading = entry.code
        downloadProgress = 0f
        downloadNote = "starting…"

        app.appScope.launch {
            val dir = PackDownloader(applicationContext).download(entry) { p ->
                downloadProgress = p.fraction
                downloadNote = "${(p.fraction * 100).toInt()}% · ${p.label}"
            }
            downloading = null
            if (dir != null) {
                // Rescan rather than trusting what we just wrote: the pack has to survive
                // the same discovery path as one copied on by hand, or "adding a language
                // is a file copy" would only be true for the ones we downloaded.
                refreshPermissions()
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "Could not download ${entry.name}. Check the connection and try again.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /**
     * Fetch just the voice for an installed language, then start using it at once.
     *
     * If that language is the one selected, the session reloads it so the next message is
     * spoken by the new voice — not the old one still held in memory until a restart.
     */
    private fun downloadVoice(pack: LanguagePack) {
        if (downloading != null) return
        val entry = voiceUpdates[pack.code] ?: return
        downloading = pack.code
        downloadProgress = 0f
        downloadNote = "starting…"

        app.appScope.launch {
            val ok = PackDownloader(applicationContext).downloadVoice(entry, pack.root) { p ->
                downloadProgress = p.fraction
                downloadNote = "${(p.fraction * 100).toInt()}% · ${p.label}"
            }
            downloading = null
            refreshPermissions()
            if (ok) {
                packs.firstOrNull { it.code == pack.code }?.let { fresh ->
                    if (fresh.id == session.ui.value.selectedLangId) session.selectLanguage(fresh)
                }
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "Could not download the ${pack.displayName} voice. Check the connection and try again.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun exportCsv() {
        runCatching {
            val dir = File(cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "itantra-measurements.csv")
            file.writeText(session.exportCsv())

            val uri = FileProvider.getUriForFile(this, "$packageName.exports", file)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "iTantra measurements")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Export measurements"))
        }.onFailure {
            Toast.makeText(this, "Export failed: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onStop() {
        super.onStop()
        // A Bluetooth scan saturates the radio for about twelve seconds and would keep
        // doing so with the screen off, for a list nobody is looking at.
        scanner.stop()
        // Never hold the microphone while off screen. Note this releases the mic only:
        // playback deliberately continues, because a DISTRESS message must finish even
        // if the user puts the phone in their pocket (PRD F-32).
        session.releaseMicrophone()
    }
    companion object {
        private const val PREFS = "itantra"
        private const val KEY_LINK = "link"
    }

}
