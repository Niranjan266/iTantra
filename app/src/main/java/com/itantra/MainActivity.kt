package com.itantra

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
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
import com.itantra.lang.LanguagePack
import com.itantra.session.SessionController
import com.itantra.session.SessionMode
import com.itantra.transport.BluetoothDevices
import com.itantra.transport.BleMeshTransport
import com.itantra.transport.BluetoothRfcommTransport
import com.itantra.transport.FloodRelay
import com.itantra.transport.RepeatSender
import com.itantra.transport.LoopbackTransport
import com.itantra.transport.PairedDevice
import com.itantra.transport.ThrottleWrapper
import com.itantra.ui.ItantraTheme
import com.itantra.ui.PttScreen
import com.itantra.ui.SimpleScreen
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
    private val packs = mutableStateListOf<LanguagePack>()

    private var selfTestResult by mutableStateOf("")

    /**
     * Which screen is showing.
     *
     * The simple screen is the product; the technical one is scaffolding that happens
     * to be needed for the submission's measurements. Defaulting to simple means the
     * person this is built for never has to know the other one exists.
     */
    private var technicalView by mutableStateOf(false)

    /** Simulated bearer rate, or null for an unthrottled link (PRD F-52). */
    private var bearerBps by mutableStateOf<Int?>(null)

    /**
     * Relay and repetition, on by default for the broadcast bearer and off for the others.
     *
     * Relaying a point-to-point link achieves nothing — there is only one peer and it
     * already heard the message — so it is not forced on every transport.
     */
    private var meshEnabled by mutableStateOf(false)
    private var repeatCopies by mutableStateOf(3)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPermissions()

        setContent {
            ItantraTheme {
                Surface {
                    val state by session.ui.collectAsState()
                    if (!technicalView) {
                        SimpleScreen(
                            state = state,
                            packs = packs,
                            micGranted = micGranted,
                            onSelectLanguage = { pack ->
                                app.appScope.launch { session.selectLanguage(pack) }
                            },
                            onRequestMic = {
                                permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                            },
                            onTalkStart = { session.startTalking() },
                            onTalkEnd = { langId, intent ->
                                app.appScope.launch {
                                    session.stopTalkingAndTransmit(langId, intent, "")
                                }
                            },
                            onReplay = { session.replayLast() },
                            onOpenTechnical = { technicalView = true },
                        )
                        return@Surface
                    }
                    PttScreen(
                        state = state,
                        transportChoice = transportChoice,
                        pairedDevices = pairedDevices,
                        bluetoothReady = bluetoothReady,
                        micGranted = micGranted,
                        onChooseTransport = ::chooseTransport,
                        onRequestPermission = {
                            permissionLauncher.launch(BluetoothDevices.requiredPermissions())
                        },
                        onRequestMic = {
                            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                        },
                        packs = packs,
                        onSelectLanguage = { pack ->
                            // Application scope: swapping models takes seconds and must
                            // not be abandoned if the screen turns off mid-load.
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
                            // Transmission runs on the application scope: releasing the
                            // button must finish sending even if the screen goes off.
                            app.appScope.launch {
                                // The text field is only a fallback for when no model is
                                // loaded; normally the recogniser supplies the words.
                                session.stopTalkingAndTransmit(langId, intent, text)
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have paired a device, or switched Bluetooth on, while away.
        refreshPermissions()
    }

    private fun chooseTransport(choice: TransportChoice) {
        transportChoice = choice
        // The broadcast bearer is the only one where relaying adds reach, so selecting it
        // turns the mesh on rather than leaving the user to find a second switch.
        meshEnabled = choice is TransportChoice.BleMesh
        if (choice is TransportChoice.BleMesh && !BleMeshTransport.hasPermission(this)) {
            permissionLauncher.launch(BleMeshTransport.requiredPermissions())
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
            RepeatSender(
                FloodRelay(throttled, app.appScope),
                app.appScope,
                copies = repeatCopies,
            )
        } else {
            throttled
        }
        // Application scope: a connection attempt must survive the screen turning off.
        app.appScope.launch { session.switchTransport(next) }
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
    }

    /**
     * Write the measurements to a file and offer it to any app that can take it.
     *
     * A file, not a screenshot: the submission claims measured numbers, so the raw
     * rows have to be able to leave the device and be checked.
     */
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
        // Never hold the microphone while off screen. Note this releases the mic only:
        // playback deliberately continues, because a DISTRESS message must finish even
        // if the user puts the phone in their pocket (PRD F-32).
        session.releaseMicrophone()
    }
}
