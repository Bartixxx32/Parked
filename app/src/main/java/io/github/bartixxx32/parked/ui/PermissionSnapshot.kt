package io.github.bartixxx32.parked.ui

import android.bluetooth.BluetoothManager
import android.content.Context
import io.github.bartixxx32.parked.util.PermissionUtils

data class PermissionSnapshot(
    val bluetoothAvailable: Boolean,
    val bluetoothEnabled: Boolean,
    val bluetoothPermission: Boolean,
    val fineLocation: Boolean,
    val coarseLocation: Boolean,
    val backgroundLocation: Boolean,
    val notifications: Boolean,
    val batteryOptimizationIgnored: Boolean,
) {
    val locationOk: Boolean
        get() = fineLocation || coarseLocation

    val hasCriticalIssues: Boolean
        get() = !bluetoothPermission || !locationOk || !notifications || !batteryOptimizationIgnored

    companion object {
        fun read(context: Context): PermissionSnapshot {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            return PermissionSnapshot(
                bluetoothAvailable = adapter != null,
                bluetoothEnabled = runCatching { adapter?.isEnabled == true }.getOrDefault(false),
                bluetoothPermission = PermissionUtils.hasBluetoothConnect(context),
                fineLocation = PermissionUtils.hasFineLocation(context),
                coarseLocation = PermissionUtils.hasCoarseLocation(context),
                backgroundLocation = PermissionUtils.hasBackgroundLocation(context),
                notifications = PermissionUtils.hasNotificationPermission(context),
                batteryOptimizationIgnored = PermissionUtils.isIgnoringBatteryOptimizations(context),
            )
        }
    }
}
