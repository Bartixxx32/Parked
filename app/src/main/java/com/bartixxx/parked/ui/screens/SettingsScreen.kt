package com.bartixxx.parked.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bartixxx.parked.BuildConfig
import com.bartixxx.parked.R
import com.bartixxx.parked.bluetooth.BondedDevice
import com.bartixxx.parked.data.settings.AppSettings
import com.bartixxx.parked.ui.PermissionSnapshot
import com.bartixxx.parked.ui.components.PermissionStatusRow
import com.bartixxx.parked.ui.components.SectionCard
import com.bartixxx.parked.ui.components.SettingSlider

@Composable
fun SettingsScreen(
    settings: AppSettings,
    permissions: PermissionSnapshot,
    bondedDevices: List<BondedDevice>,
    onRefreshDevices: () -> Unit,
    onSelectDevice: (BondedDevice?) -> Unit,
    onSaveDelayChange: (Int) -> Unit,
    onGpsTimeoutChange: (Int) -> Unit,
    onMaxAccuracyChange: (Int) -> Unit,
    onFallbackEnabledChange: (Boolean) -> Unit,
    onFallbackMaxAgeChange: (Int) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onRequestPermissions: () -> Unit,
    onRequestBackgroundLocation: () -> Unit,
    onBatterySettings: () -> Unit,
    onAutostartSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Column {
                Text(
                    text = stringResource(R.string.tab_settings),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.settings_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(key = "device") {
            SectionCard(title = stringResource(R.string.car_radio)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Bluetooth,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = if (settings.deviceAddress == null) {
                            stringResource(R.string.no_device_selected)
                        } else {
                            val name = settings.deviceName
                                ?: stringResource(R.string.device_fallback)
                            "$name (${settings.deviceAddress})"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onRefreshDevices) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.cd_refresh_list))
                    }
                }

                if (!permissions.bluetoothPermission) {
                    Text(
                        text = stringResource(R.string.bluetooth_permission_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.grant_bluetooth_access))
                    }
                } else if (bondedDevices.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_paired_devices),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        onClick = onOpenBluetoothSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.open_bluetooth_settings))
                    }
                } else {
                    bondedDevices.forEach { device ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = settings.deviceAddress.equals(
                                    device.address,
                                    ignoreCase = true,
                                ),
                                onClick = { onSelectDevice(device) },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(device.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = device.address,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (settings.deviceAddress != null) {
                        TextButton(
                            onClick = { onSelectDevice(null) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.clear_device_selection))
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.select_one_radio_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(key = "automation") {
            SectionCard(title = stringResource(R.string.section_auto_save)) {
                SettingSlider(
                    title = stringResource(R.string.save_delay_title),
                    value = settings.saveDelaySeconds,
                    range = AppSettings.SAVE_DELAY_RANGE,
                    step = 5,
                    unit = "s",
                    description = stringResource(R.string.save_delay_desc),
                    onValueChange = onSaveDelayChange,
                )
                SettingSlider(
                    title = stringResource(R.string.gps_timeout_title),
                    value = settings.gpsTimeoutSeconds,
                    range = AppSettings.GPS_TIMEOUT_RANGE,
                    step = 5,
                    unit = "s",
                    description = stringResource(R.string.gps_timeout_desc),
                    onValueChange = onGpsTimeoutChange,
                )
                SettingSlider(
                    title = stringResource(R.string.max_accuracy_title),
                    value = settings.maxAccuracyMeters,
                    range = AppSettings.MAX_ACCURACY_RANGE,
                    step = 10,
                    unit = "m",
                    description = stringResource(R.string.max_accuracy_desc),
                    onValueChange = onMaxAccuracyChange,
                )
                SwitchRow(
                    title = stringResource(R.string.fallback_enabled_title),
                    subtitle = stringResource(R.string.fallback_enabled_desc),
                    checked = settings.allowFallbackLastKnown,
                    onCheckedChange = onFallbackEnabledChange,
                )
                SettingSlider(
                    title = stringResource(R.string.fallback_age_title),
                    value = settings.fallbackMaxAgeHours,
                    range = AppSettings.FALLBACK_MAX_AGE_RANGE,
                    step = 1,
                    unit = "h",
                    enabled = settings.allowFallbackLastKnown,
                    onValueChange = onFallbackMaxAgeChange,
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.notifications_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.notifications_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onOpenNotificationSettings) {
                        Text(stringResource(R.string.notification_settings))
                    }
                }
            }
        }

        item(key = "permissions") {
            SectionCard(title = stringResource(R.string.section_permissions)) {
                PermissionStatusRow(
                    title = stringResource(R.string.bluetooth_access),
                    granted = permissions.bluetoothPermission,
                    actionLabel = if (!permissions.bluetoothPermission) stringResource(R.string.action_grant) else null,
                    onAction = if (!permissions.bluetoothPermission) onRequestPermissions else null,
                )
                PermissionStatusRow(
                    title = stringResource(R.string.permission_location),
                    granted = permissions.locationOk,
                    actionLabel = if (!permissions.locationOk) stringResource(R.string.action_grant) else null,
                    onAction = if (!permissions.locationOk) onRequestPermissions else null,
                )
                PermissionStatusRow(
                    title = stringResource(R.string.permission_background_location),
                    granted = permissions.backgroundLocation,
                    detail = stringResource(R.string.permission_background_detail),
                    actionLabel = if (permissions.locationOk && !permissions.backgroundLocation) {
                        stringResource(R.string.action_enable)
                    } else {
                        null
                    },
                    onAction = if (permissions.locationOk && !permissions.backgroundLocation) {
                        onRequestBackgroundLocation
                    } else {
                        null
                    },
                )
                PermissionStatusRow(
                    title = stringResource(R.string.permission_bluetooth_on),
                    granted = permissions.bluetoothEnabled,
                    actionLabel = if (!permissions.bluetoothEnabled) stringResource(R.string.action_enable) else null,
                    onAction = if (!permissions.bluetoothEnabled) onOpenBluetoothSettings else null,
                )
                PermissionStatusRow(
                    title = stringResource(R.string.permission_battery_off),
                    granted = permissions.batteryOptimizationIgnored,
                    actionLabel = if (!permissions.batteryOptimizationIgnored) stringResource(R.string.action_change) else null,
                    onAction = if (!permissions.batteryOptimizationIgnored) onBatterySettings else null,
                )
                Button(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.request_all_permissions))
                }
            }
        }

        item(key = "reliability") {
            SectionCard(title = stringResource(R.string.section_reliability_oem)) {
                Text(
                    text = stringResource(R.string.reliability_oem_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.reliability_steps),
                    style = MaterialTheme.typography.bodySmall,
                )
                FilledTonalButton(
                    onClick = onBatterySettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.open_battery_settings))
                }
                FilledTonalButton(
                    onClick = onAutostartSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.open_autostart_settings))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = onOpenLocationSettings,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.action_location))
                    }
                    TextButton(
                        onClick = onOpenBluetoothSettings,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.bluetooth_access))
                    }
                    TextButton(
                        onClick = onOpenAppSettings,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.action_app))
                    }
                }
                Text(
                    text = stringResource(R.string.reliability_outro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(key = "about") {
            SectionCard(title = stringResource(R.string.about_title)) {
                Text(
                    text = stringResource(
                        R.string.app_version,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.license_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.privacy_text),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(R.string.map_legal),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(key = "bottom") {
            Spacer(Modifier.size(16.dp))
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
