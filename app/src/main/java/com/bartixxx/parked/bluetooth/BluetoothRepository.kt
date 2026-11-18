package com.bartixxx.parked.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

data class BondedDevice(
    val name: String,
    val address: String,
)

/**
 * Read-only access to bonded/connected Bluetooth devices.
 *
 * Modern Android requires BLUETOOTH_CONNECT at runtime (API 31+); every operation fails
 * closed and returns no data if the permission was revoked.
 *
 * Connection detection uses long-lived profile proxies obtained through
 * [BluetoothAdapter.getProfileProxy]. [BluetoothManager.getConnectedDevices] cannot be
 * used for HFP/A2DP: Android only implements it for GATT/GATT_SERVER and throws
 * IllegalArgumentException for every other profile.
 */
class BluetoothRepository(private val context: Context) {

    private val appContext: Context = context.applicationContext

    private val manager: BluetoothManager?
        get() = appContext.getSystemService(BluetoothManager::class.java)

    val adapter: BluetoothAdapter?
        get() = manager?.adapter

    private val proxies = ConcurrentHashMap<Int, BluetoothProfile>()
    private val requestedProfiles = ConcurrentHashMap.newKeySet<Int>()

    @Volatile
    private var tracking = false

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (!tracking) {
                runCatching { adapter?.closeProfileProxy(profile, proxy) }
                return
            }
            proxies[profile] = proxy
        }

        override fun onServiceDisconnected(profile: Int) {
            proxies.remove(profile)
            requestedProfiles.remove(profile)
        }
    }

    fun hasBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            appContext,
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
     * Opens (or re-opens) profile proxies used for connection detection. Safe to call
     * repeatedly; only missing profiles are requested. Call from the monitoring service so
     * proxies exist before the first connection check.
     */
    fun startConnectionTracking() {
        if (!hasBluetoothPermission()) return
        val adapter = adapter ?: return
        tracking = true

        profilesToTrack().forEach { profile ->
            if (!requestedProfiles.add(profile)) return@forEach
            val accepted = runCatching {
                adapter.getProfileProxy(appContext, profileListener, profile)
            }.getOrDefault(false)
            if (!accepted) requestedProfiles.remove(profile)
        }
    }

    /** Releases profile proxies when monitoring stops. */
    fun stopConnectionTracking() {
        tracking = false
        val currentAdapter = adapter
        proxies.entries.forEach { (profile, proxy) ->
            runCatching { currentAdapter?.closeProfileProxy(profile, proxy) }
        }
        proxies.clear()
        requestedProfiles.clear()
    }

    fun isConnectionTrackingReady(): Boolean = proxies.isNotEmpty()

    /**
     * True when any tracked audio profile (HFP, A2DP, hearing aid, LE Audio) reports an
     * active connection to [address].
     */
    fun isConnected(address: String?): Boolean {
        if (address.isNullOrBlank()) return false
        if (!hasBluetoothPermission()) return false
        if (!isBluetoothEnabled()) return false

        startConnectionTracking()
        return connectedAddresses().any { it.equals(address, ignoreCase = true) }
    }

    private fun connectedAddresses(): Set<String> {
        val addresses = mutableSetOf<String>()
        proxies.forEach { (_, proxy) ->
            runCatching {
                proxy.connectedDevices.orEmpty().forEach { device ->
                    addresses += device.address
                }
            }
        }
        return addresses
    }

    private fun profilesToTrack(): List<Int> = buildList {
        add(BluetoothProfile.HEADSET)
        add(BluetoothProfile.A2DP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(BluetoothProfile.HEARING_AID)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(BluetoothProfile.LE_AUDIO)
        }
    }
}
