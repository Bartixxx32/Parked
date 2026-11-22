package com.bartixxx.parked.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bartixxx.parked.R
import com.bartixxx.parked.data.db.ParkingSpot
import com.bartixxx.parked.data.settings.AppSettings
import com.bartixxx.parked.data.trigger.TriggerPhase
import com.bartixxx.parked.monitor.MonitorRuntimeState
import com.bartixxx.parked.ui.Formatters
import com.bartixxx.parked.ui.PermissionSnapshot
import com.bartixxx.parked.ui.components.EmptyState
import com.bartixxx.parked.ui.components.InfoRow
import com.bartixxx.parked.ui.components.PermissionStatusRow
import com.bartixxx.parked.ui.components.SectionCard
import com.bartixxx.parked.ui.components.StatusDot
import kotlinx.coroutines.delay

@Composable
fun DashboardScreen(
    settings: AppSettings,
    monitor: MonitorRuntimeState,
    latestSpot: ParkingSpot?,
    permissions: PermissionSnapshot,
    saving: Boolean,
    onToggleMonitoring: (Boolean) -> Unit,
    onSaveNow: () -> Unit,
    onAddManual: (String, String, String) -> Unit,
    onCancelPending: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenMap: (ParkingSpot) -> Unit,
    onNavigate: (ParkingSpot) -> Unit,
    onWalk: (ParkingSpot) -> Unit,
    onOpenSettings: () -> Unit,
    onRequestPermissions: () -> Unit,
    onRequestBackgroundLocation: () -> Unit,
    onBatterySettings: () -> Unit,
    onAutostartSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
) {
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var showAddManual by remember { mutableStateOf(false) }
    LaunchedEffect(monitor.pendingAtEpochMs) {
        if (monitor.pendingAtEpochMs > 0L) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(1_000L)
            }
        }
    }
    val remainingSeconds = ((monitor.pendingAtEpochMs - nowMs) / 1000L).coerceAtLeast(0L)
    val pending = monitor.phase == TriggerPhase.PENDING
    val missingForeground =
        !permissions.bluetoothPermission || !permissions.locationOk
    val missingBackground = permissions.locationOk && !permissions.backgroundLocation
    val reliabilityProblems = missingForeground || missingBackground ||
        !permissions.batteryOptimizationIgnored || !permissions.bluetoothEnabled

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Parked",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.dashboard_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard(title = stringResource(R.string.section_monitoring)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(
                    color = when {
                        !settings.monitoringEnabled -> MaterialTheme.colorScheme.outline
                        !monitor.running -> MaterialTheme.colorScheme.error
                        monitor.connected -> MaterialTheme.colorScheme.primary
                        pending -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.secondary
                    },
                    size = 12,
                )
                Spacer(Modifier.size(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = statusTitle(settings, monitor),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = statusDetail(settings, monitor, pending, remainingSeconds),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.monitoringEnabled,
                    onCheckedChange = onToggleMonitoring,
                )
            }

            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Bluetooth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.car_radio),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = settings.deviceName ?: settings.deviceAddress
                            ?: stringResource(R.string.not_selected),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                TextButton(onClick = onOpenSettings) {
                    Text(
                        if (settings.deviceAddress == null) {
                            stringResource(R.string.action_choose)
                        } else {
                            stringResource(R.string.action_change)
                        },
                    )
                }
            }

            if (settings.monitoringEnabled && monitor.running) {
                PhaseBadge(monitor.phase)
            }

            if (pending) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.pending_save_in, remainingSeconds),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    TextButton(onClick = onCancelPending) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }

            if (settings.monitoringEnabled && !monitor.running) {
                Text(
                    text = stringResource(R.string.service_not_running_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                FilledTonalButton(
                    onClick = { onToggleMonitoring(true) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.restart_monitoring))
                }
            }

            if (settings.deviceAddress == null) {
                Text(
                    text = stringResource(R.string.setup_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (monitor.lastError != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = monitor.lastError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (reliabilityProblems) {
            SectionCard(title = stringResource(R.string.section_permissions_reliability)) {
                PermissionStatusRow(
                    title = stringResource(R.string.bluetooth_access),
                    granted = permissions.bluetoothPermission,
                    detail = stringResource(R.string.bluetooth_access_detail),
                )
                PermissionStatusRow(
                    title = stringResource(R.string.location),
                    granted = permissions.locationOk,
                    detail = if (permissions.locationOk) {
                        stringResource(R.string.location_granted_detail)
                    } else {
                        stringResource(R.string.location_denied_detail)
                    },
                )
                PermissionStatusRow(
                    title = stringResource(R.string.background_location),
                    granted = permissions.backgroundLocation,
                    detail = stringResource(R.string.background_location_detail),
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
                    title = stringResource(R.string.bluetooth_on),
                    granted = permissions.bluetoothEnabled,
                    detail = stringResource(R.string.bluetooth_on_detail),
                    actionLabel = if (!permissions.bluetoothEnabled) stringResource(R.string.action_settings) else null,
                    onAction = if (!permissions.bluetoothEnabled) onOpenBluetoothSettings else null,
                )
                PermissionStatusRow(
                    title = stringResource(R.string.battery_optimization_off),
                    granted = permissions.batteryOptimizationIgnored,
                    detail = stringResource(R.string.battery_optimization_detail),
                    actionLabel = if (!permissions.batteryOptimizationIgnored) stringResource(R.string.action_change) else null,
                    onAction = if (!permissions.batteryOptimizationIgnored) onBatterySettings else null,
                )

                if (missingForeground) {
                    Button(
                        onClick = onRequestPermissions,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.grant_required_permissions))
                    }
                }
                if (missingBackground) {
                    FilledTonalButton(
                        onClick = onRequestBackgroundLocation,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.allow_background_location))
                    }
                }
            }
        }

        SectionCard(title = stringResource(R.string.section_latest_spot)) {
            val spot = latestSpot
            if (settings.deviceAddress == null) {
                Text(
                    text = stringResource(R.string.manual_save_no_bluetooth_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.manual_save_no_bluetooth_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (spot == null) {
                EmptyState(
                    title = stringResource(R.string.no_spots),
                    subtitle = stringResource(R.string.no_spots_subtitle),
                ) {
                    Icon(
                        imageVector = Icons.Filled.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = Formatters.dateTime(spot.savedAtEpochMs),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = Formatters.age(spot.savedAtEpochMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                InfoRow(
                    stringResource(R.string.label_coordinates),
                    Formatters.coordinates(spot.latitude, spot.longitude),
                )
                InfoRow(stringResource(R.string.label_accuracy), Formatters.accuracy(spot))
                InfoRow(stringResource(R.string.label_source), Formatters.source(spot))
                if (spot.note.isNotBlank()) {
                    InfoRow(stringResource(R.string.label_note), spot.note)
                }
                if (spot.isFallback) {
                    Text(
                        text = stringResource(R.string.fallback_point_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (spot.isMock) {
                    Text(
                        text = stringResource(R.string.mock_point_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            ManualSaveActions(
                saving = saving,
                hasLocationPermission = permissions.locationOk,
                onSaveNow = onSaveNow,
                onAddManual = { showAddManual = true },
            )

            if (spot != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalButton(
                        onClick = { onOpenMap(spot) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.action_map))
                    }
                    FilledTonalButton(
                        onClick = { onNavigate(spot) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Filled.Navigation,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.action_navigate))
                    }
                    FilledTonalButton(
                        onClick = { onWalk(spot) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Filled.DirectionsWalk,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.action_walk))
                    }
                }
                TextButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.full_history))
                }
            }
        }

        SectionCard(title = stringResource(R.string.section_reliability)) {
            Text(
                text = stringResource(R.string.reliability_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(stringResource(R.string.reliability_bullet_background), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.reliability_bullet_autostart), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.reliability_bullet_battery), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.reliability_bullet_recents), style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                text = stringResource(R.string.reliability_outro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onBatterySettings,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_battery))
                }
                FilledTonalButton(
                    onClick = onAutostartSettings,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_autostart))
                }
            }
        }
    }

    if (showAddManual) {
        ManualAddDialog(
            onDismiss = { showAddManual = false },
            onConfirm = onAddManual,
        )
    }
}

@Composable
private fun ManualSaveActions(
    saving: Boolean,
    hasLocationPermission: Boolean,
    onSaveNow: () -> Unit,
    onAddManual: () -> Unit,
) {
    Button(
        onClick = onSaveNow,
        enabled = !saving && hasLocationPermission,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (saving) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.size(8.dp))
        Text(
            if (saving) {
                stringResource(R.string.saving)
            } else {
                stringResource(R.string.save_current_location)
            },
        )
    }
    if (!hasLocationPermission) {
        Text(
            text = stringResource(R.string.manual_save_requires_location),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    FilledTonalButton(
        onClick = onAddManual,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.add_manual_spot))
    }
}

@Composable
private fun PhaseBadge(phase: TriggerPhase) {
    val label: String
    val color: Color
    when (phase) {
        TriggerPhase.UNARMED -> {
            label = stringResource(R.string.phase_unarmed)
            color = MaterialTheme.colorScheme.outline
        }

        TriggerPhase.CONNECTED -> {
            label = stringResource(R.string.phase_connected)
            color = MaterialTheme.colorScheme.primary
        }

        TriggerPhase.PENDING -> {
            label = stringResource(R.string.phase_pending)
            color = MaterialTheme.colorScheme.tertiary
        }

        TriggerPhase.CAPTURING -> {
            label = stringResource(R.string.phase_capturing)
            color = MaterialTheme.colorScheme.tertiary
        }

        TriggerPhase.SAVED -> {
            label = stringResource(R.string.phase_saved)
            color = MaterialTheme.colorScheme.primary
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color = color, size = 8)
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
}

@Composable
private fun statusTitle(settings: AppSettings, monitor: MonitorRuntimeState): String = when {
    !settings.monitoringEnabled -> stringResource(R.string.status_monitoring_off)
    settings.deviceAddress.isNullOrBlank() -> stringResource(R.string.status_no_radio)
    !monitor.running -> stringResource(R.string.status_service_stopped)
    monitor.connected -> stringResource(R.string.status_radio_connected)
    monitor.phase == TriggerPhase.PENDING -> stringResource(R.string.status_disconnected_saving)
    else -> stringResource(R.string.status_waiting_radio)
}

@Composable
private fun statusDetail(
    settings: AppSettings,
    monitor: MonitorRuntimeState,
    pending: Boolean,
    remainingSeconds: Long,
): String = when {
    !settings.monitoringEnabled ->
        stringResource(R.string.status_detail_monitoring_off)

    settings.deviceAddress.isNullOrBlank() ->
        stringResource(R.string.status_detail_no_radio)

    !monitor.running ->
        stringResource(R.string.status_detail_service_stopped)

    pending ->
        stringResource(R.string.status_detail_pending, remainingSeconds)

    monitor.connected ->
        stringResource(R.string.status_detail_connected, settings.saveDelaySeconds)

    else ->
        stringResource(R.string.status_detail_waiting)
}
