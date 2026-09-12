package com.itantra.transport

import android.Manifest
import android.annotation.SuppressLint
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
     * Pairing deliberately stays in the OS. It is a one-time provisioning step, and
     * reimplementing it inside the app would add a discovery and bonding flow that
     * earns no marks and can fail on stage.
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
     * Could a device of this class be running iTantra?
     *
     * Headsets, speakers and car kits cannot, and on a phone that has been used for a
     * while they are the overwhelming majority of pairings. Uncategorised is kept
     * because some phones report themselves that way.
     */
    private fun couldRunTheApp(majorDeviceClass: Int?): Boolean = when (majorDeviceClass) {
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
