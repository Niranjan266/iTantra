package com.itantra.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * One paired device, as shown in the connection picker.
 *
 * @param couldBePeer whether this device could plausibly be running iTantra. A phone
 *   pairs with a lot of headsets and speakers over its life, and none of them can run
 *   an Android app — listing them all buries the one device that matters.
 */
data class PairedDevice(
    val name: String,
    val address: String,
    val couldBePeer: Boolean,
)

/**
 * Reading the paired-device list, without the permission checks leaking into the UI.
 *
 * Android moved Bluetooth permissions in API 31: BLUETOOTH_CONNECT became a runtime
 * permission, while older versions used install-time BLUETOOTH. The rest of the app
 * should not have to care, so the version split is handled once, here.
 */
object BluetoothDevices {

    /** The runtime permissions this build needs on the current OS version. */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            emptyArray() // granted at install time on API 30 and below
        }

    fun hasPermission(context: Context): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    fun isAvailable(context: Context): Boolean = adapterOf(context) != null

    fun isEnabled(context: Context): Boolean = adapterOf(context)?.isEnabled == true

    /**
     * Devices already paired in Android's Bluetooth settings.
     *
     * Pairing is started from the Devices screen with [pair] and confirmed in the
     * system's own dialog; this list is what that produced.
     */
    @SuppressLint("MissingPermission")
    fun paired(context: Context): List<PairedDevice> {
        if (!hasPermission(context)) return emptyList()
        val adapter = adapterOf(context) ?: return emptyList()
        return runCatching {
            adapter.bondedDevices.orEmpty().map { device ->
                PairedDevice(
                    name = runCatching { device.name }.getOrNull() ?: device.address,
                    address = device.address,
                    couldBePeer = couldRunTheApp(device.bluetoothClass?.majorDeviceClass),
                )
            }.sortedWith(
                // Plausible peers first, then alphabetically.
                compareByDescending<PairedDevice> { it.couldBePeer }
                    .thenBy { it.name.lowercase() }
            )
        }.getOrDefault(emptyList())
    }

    /**
     * Start pairing with a device found by a scan.
     *
     * An app can START bonding; Android then shows its own confirm-the-code dialog on both
     * phones, and the user finishes it there. So the user never has to leave the app to
     * find Android's Bluetooth screen, and the security of the exchange stays the OS's.
     * The earlier version of this file said pairing had to stay in Settings — it only has
     * to be CONFIRMED by the OS, which is a different thing.
     *
     * @return false if it could not even be started (no permission, Bluetooth off, or the
     *   address is invalid). True means the system dialog is on its way, not that pairing
     *   has succeeded — [paired] is re-read when the dialog closes and the app resumes.
     */
    @SuppressLint("MissingPermission")
    fun pair(context: Context, address: String): Boolean {
        if (!hasPermission(context)) return false
        val adapter = adapterOf(context) ?: return false
        return runCatching {
            // Discovery competes with bonding for the radio and makes it slow or fail.
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            adapter.getRemoteDevice(address).createBond()
        }.getOrDefault(false)
    }

    /**
     * Could a device of this class be running iTantra?
     *
     * Headsets, speakers and car kits cannot, and on a phone that has been used for a
     * while they are the overwhelming majority of pairings. Uncategorised is kept
     * because some phones report themselves that way.
     */
    internal fun couldRunTheApp(majorDeviceClass: Int?): Boolean = when (majorDeviceClass) {
        BluetoothClass.Device.Major.PHONE,
        BluetoothClass.Device.Major.COMPUTER,
        BluetoothClass.Device.Major.UNCATEGORIZED,
        -> true
        null -> true // class unknown: do not hide it, the user may still need it
        else -> false
    }

    private fun adapterOf(context: Context) =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
}

/**
 * A live scan for Bluetooth devices in range, paired or not.
 *
 * [BluetoothDevices.paired] only lists what this phone has already bonded with, which is
 * the wrong answer to "who is near me": a phone that has never been paired is exactly the
 * one a stranger is carrying, and a bonded list is mostly the owner's own headphones.
 *
 * Discovery is expensive — it saturates the radio for about twelve seconds and slows any
 * active connection — so it is started explicitly and stopped as soon as the screen that
 * asked for it goes away.
 */
class BluetoothScanner(private val context: Context) {

    private val _found = MutableStateFlow<List<PairedDevice>>(emptyList())
    val found: StateFlow<List<PairedDevice>> = _found.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private var receiver: BroadcastReceiver? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (receiver != null) return
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
            as? BluetoothManager)?.adapter ?: return
        if (!BluetoothDevices.hasPermission(context)) return

        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        } ?: return
                        add(device)
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_STARTED -> _scanning.value = true
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> _scanning.value = false
                }
            }
        }
        receiver = r
        context.registerReceiver(r, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        })

        // A scan already running returns no results to us, so restart it.
        runCatching {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            adapter.startDiscovery()
        }
    }

    @SuppressLint("MissingPermission")
    private fun add(device: BluetoothDevice) {
        val name = runCatching { device.name }.getOrNull()
        val entry = PairedDevice(
            // An unnamed device is normal — many only reveal a name once connected — and
            // showing the address is more use than hiding the row entirely.
            name = name?.takeIf { it.isNotBlank() } ?: device.address,
            address = device.address,
            couldBePeer = BluetoothDevices.couldRunTheApp(device.bluetoothClass?.majorDeviceClass),
        )
        _found.update { list ->
            if (list.any { it.address == entry.address }) list
            else (list + entry).sortedByDescending { it.couldBePeer }
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        runCatching { receiver?.let { context.unregisterReceiver(it) } }
        receiver = null
        runCatching {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
                as? BluetoothManager)?.adapter
            if (adapter?.isDiscovering == true) adapter.cancelDiscovery()
        }
        _scanning.value = false
    }
}
