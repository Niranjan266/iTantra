package com.itantra.transport

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Connectionless one-to-many bearer: the message travels inside a BLE advertisement.
 *
 * ## Why broadcast rather than a connection
 *
 * Every other transport here is a pipe between two phones. Bluetooth RFCOMM must be
 * paired — someone taps "confirm" on both handsets — and carries one peer at a time. That
 * is the wrong shape for a disaster call, where the destination is "anybody within
 * earshot" and nobody is going to pair with eight strangers while water is rising.
 *
 * A BLE advertisement needs **no pairing, no connection and no agreement of any kind**.
 * Every phone running this app that is scanning will hear it, and with [FloodRelay] on
 * top each of them repeats it. That combination — broadcast plus relay — is what turns a
 * crowd of phones into a network.
 *
 * ## The constraint that shapes everything
 *
 * A legacy advertisement carries **31 bytes** in total, of which about 24 are usable once
 * the flags and service-data structures are counted (see [MeshFrame.BLE_LEGACY_CAPACITY]).
 * That is the entire budget:
 *
 * | Message | Wire size | Fits a legacy advertisement |
 * |---|---|---|
 * | Phrase reference + TTL | 17 B | **yes** |
 * | Spelled-out sentence + TTL | 63 B | no |
 *
 * So the phrase codebook is not an optimisation here — it is the difference between a
 * message that can travel connectionlessly and one that cannot. Anything too long is
 * **refused by [send] rather than silently truncated**, because a truncated packet fails
 * its CRC at every receiver and looks exactly like a radio problem.
 *
 * Extended advertising (Bluetooth 5) would raise the limit well past any packet we send,
 * and [supportsExtendedAdvertising] reports whether this handset has it — but it is not
 * used as the default path, because the phones most likely to be in a flood are the oldest
 * ones present and legacy advertising is what works everywhere.
 *
 * ## Honest limitations
 *
 * - **Unverified on hardware.** Written against the Android API; no handset was available
 *   when it was built. The logic above it ([FloodRelay], [MeshFrame]) is unit-tested, but
 *   that a real phone advertises and a second real phone hears it has not been observed.
 * - **No delivery guarantee.** There is no acknowledgement and there cannot be: a
 *   broadcast has no single recipient to acknowledge it. Reliability comes from
 *   [RepeatSender] sending several copies.
 * - **Android's scan throttling.** An app scanning from the background is rate-limited by
 *   the OS, and some vendors are stricter than the documentation. Foreground use is the
 *   supported case.
 */
class BleMeshTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    /**
     * How long each message is advertised before the next one is allowed out.
     *
     * BLE advertising is a repeating beacon, not a packet send: the radio keeps
     * retransmitting whatever is loaded until told otherwise. So "sending" means
     * advertising for a window. 300 ms at the fastest advertising interval is tens of
     * actual transmissions, which is itself a useful amount of redundancy.
     */
    private val advertiseWindowMs: Long = 300,
) : Transport {

    companion object {
        private const val TAG = "iTantra.Ble"

        /**
         * Our service UUID — the only thing that distinguishes our beacons from the shop
         * tags, fitness bands and headphones that fill any urban BLE scan.
         *
         * A 16-bit short UUID, not a random 128-bit one, and that choice costs 12 bytes of
         * the 24-byte budget if got wrong: a 128-bit UUID in the advertisement leaves too
         * little room for the packet. The value sits in the range reserved for
         * non-registered use.
         */
        val SERVICE_UUID: UUID = UUID.fromString("0000fd6f-0000-1000-8000-00805f9b34fb")

        private val SERVICE_PARCEL = ParcelUuid(SERVICE_UUID)

        /** Permissions this transport needs, by OS version. */
        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                )
            } else {
                // Before Android 12 a BLE scan required a location permission, because
                // nearby beacons reveal where you are. Nothing here uses location.
                arrayOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                )
            }

        fun hasPermission(context: Context): Boolean =
            requiredPermissions().all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
    }

    override val name: String get() = "BLE broadcast"

    /**
     * Left null deliberately.
     *
     * A connectionless beacon has no meaningful steady-state bitrate: it is a small
     * payload repeated for a window, not a stream. Reporting a number here would put a
     * fabricated figure into the measurements CSV.
     */
    override val nominalBitrate: Int? get() = null

    private val _state = MutableStateFlow<TransportState>(TransportState.Idle)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incoming: Flow<ByteArray> = _incoming

    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + job)

    /** Serialises advertising: the radio holds one payload at a time. */
    private val sendLock = Mutex()

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null

    /** True when this handset can advertise more than the legacy 31 bytes. */
    val supportsExtendedAdvertising: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            adapter?.isLeExtendedAdvertisingSupported == true

    @Volatile var scanHits: Int = 0
        private set

    @Volatile var advertiseFailures: Int = 0
        private set

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val data = result?.scanRecord?.getServiceData(SERVICE_PARCEL) ?: return
            scanHits++
            // Emitted without validation: FloodRelay checks MAGIC and the header CRC, and
            // duplicating that here would put the wire format in two places.
            ownScope.launch { _incoming.emit(data) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "scan failed: $errorCode")
            _state.value = TransportState.Failed("Scan failed ($errorCode)")
        }
    }

    override suspend fun connect() {
        if (adapter == null) {
            _state.value = TransportState.Failed("This device has no Bluetooth")
            return
        }
        if (adapter?.isEnabled != true) {
            _state.value = TransportState.Failed("Bluetooth is off")
            return
        }
        if (!hasPermission(context)) {
            _state.value = TransportState.Failed("Nearby-devices permission not granted")
            return
        }

        advertiser = adapter?.bluetoothLeAdvertiser
        scanner = adapter?.bluetoothLeScanner
        if (advertiser == null || scanner == null) {
            _state.value = TransportState.Failed("This device cannot broadcast over BLE")
            return
        }

        _state.value = TransportState.Connecting
        val started = runCatching {
            scanner?.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(SERVICE_PARCEL).build()),
                ScanSettings.Builder()
                    // Low latency: a distress message should be heard as soon as it is
                    // sent, and this transport is only used while the app is in front of
                    // the user. The battery cost is real and accepted for that reason.
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    // Report every advertisement, not just the first from each device. A
                    // repeated beacon carrying a NEW message must not be filtered out as a
                    // duplicate of the old one — the OS cannot tell them apart, but
                    // FloodRelay can.
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .build(),
                scanCallback,
            )
            true
        }.getOrElse {
            Log.e(TAG, "could not start scanning", it)
            _state.value = TransportState.Failed("Could not start scanning: ${it.message}")
            false
        }

        if (started) {
            // "Connected" is a stretch for a connectionless bearer and the peer name says
            // so rather than inventing one. There is no peer; there is a channel.
            _state.value = TransportState.Connected("anyone in range")
            Log.i(TAG, "scanning; extended advertising supported = $supportsExtendedAdvertising")
        }
    }

    /**
     * Advertise one frame for [advertiseWindowMs], then stop.
     *
     * Suspends for the window, so callers are serialised and two messages cannot fight
     * over the one radio. That is also why [RepeatSender] spaces its copies: back-to-back
     * calls would simply queue here.
     */
    override suspend fun send(frame: ByteArray): Boolean {
        val adv = advertiser ?: return false

        if (!MeshFrame.fits(frame)) {
            // Refused, not truncated. A truncated packet fails its CRC at every receiver
            // and is indistinguishable from interference, which would send someone
            // debugging the radio instead of the message size.
            Log.w(TAG, "frame of ${frame.size} B exceeds the " +
                "${MeshFrame.BLE_LEGACY_CAPACITY} B advertisement budget — not sent")
            _state.value = TransportState.Failed(
                "Message too long for BLE broadcast (${frame.size} B of " +
                    "${MeshFrame.BLE_LEGACY_CAPACITY} B). Use a codebook phrase."
            )
            return false
        }

        return sendLock.withLock {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                // Nothing may connect to us. We are a beacon; a connection attempt from a
                // stranger's phone would be a distraction and an attack surface.
                .setConnectable(false)
                .setTimeout(0)
                .build()

            val data = AdvertiseData.Builder()
                // The device name is whatever the owner called their phone and would
                // eat the budget for no benefit.
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceData(SERVICE_PARCEL, frame)
                .build()

            var ok = false
            val callback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { ok = true }
                override fun onStartFailure(errorCode: Int) {
                    advertiseFailures++
                    Log.e(TAG, "advertise failed: $errorCode")
                }
            }

            runCatching { adv.startAdvertising(settings, data, callback) }
                .onFailure { Log.e(TAG, "startAdvertising threw", it); return@withLock false }

            delay(advertiseWindowMs)
            runCatching { adv.stopAdvertising(callback) }
            ok
        }
    }

    override suspend fun close() {
        runCatching { scanner?.stopScan(scanCallback) }
        job.cancel()
        _state.value = TransportState.Closed
    }
}
