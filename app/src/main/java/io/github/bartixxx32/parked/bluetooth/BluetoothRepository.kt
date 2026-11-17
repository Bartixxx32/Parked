package io.github.bartixxx32.parked.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

data class BondedDevice(
    val name: String,
    val address: String,
)

/**
 * Read-only access to bonded/connected Bluetooth devices.
 *
 * Modern Android requires BLUETOOTH_CONNECT at runtime (API 31+); every operation fails
 * closed and returns no data if the permission was revoked.
 */
class BluetoothRepository(private val context: Context) {

    private val manager: BluetoothManager?
        get() = context.getSystemService(BluetoothManager::class.java)

    val adapter: BluetoothAdapter?
        get() = manager?.adapter

    fun hasBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isBluetoothAvailable(): Boolean = adapter != null

    fun isBluetoothEnabled(): Boolean = runCatching { adapter?.isEnabled == true }.getOrDefault(false)

    fun bondedDevices(): List<BondedDevice> {
        if (!hasBluetoothPermission()) return emptyList()
        val adapter = adapter ?: return emptyList()
        return runCatching {
            adapter.bondedDevices.orEmpty()
                .map { device ->
                    val name = runCatching { device.name }.getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?: device.address
                    BondedDevice(name = name, address = device.address)
                }
                .filter { it.address.isNotBlank() }
                .sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
    }

    /**
     * True when the ACL link or any profile connection to [address] is active.
     * BLUETOOTH_CONNECT is required for this check on Android 12+.
     */
    fun isConnected(address: String?): Boolean {
        if (address.isNullOrBlank()) return false
        if (!hasBluetoothPermission()) return false
        val adapter = adapter ?: return false
        if (!isBluetoothEnabled()) return false

        val target = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return false
        val aclConnected = runCatching { target.isConnected }.getOrDefault(false)
        if (aclConnected) return true

        return connectedAddresses().any { it.equals(address, ignoreCase = true) }
    }

    private fun connectedAddresses(): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptySet()
        val manager = manager ?: return emptySet()
        val addresses = mutableSetOf<String>()
        val profiles = intArrayOf(
            BluetoothProfile.GATT,
            BluetoothProfile.GATT_SERVER,
            BluetoothProfile.A2DP,
            BluetoothProfile.A2DP_SINK,
            BluetoothProfile.HEADSET,
            BluetoothProfile.HEADSET_CLIENT,
        )
        profiles.forEach { profile ->
            runCatching {
                manager.getConnectedDevices(profile).forEach { device ->
                    addresses += device.address
                }
            }
        }
        return addresses
    }
}
