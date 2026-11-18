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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bartixxx.parked.R
import com.bartixxx.parked.data.db.DiagnosticLevel
import com.bartixxx.parked.data.db.DiagnosticsEvent
import com.bartixxx.parked.data.trigger.TriggerPhase
import com.bartixxx.parked.monitor.MonitorRuntimeState
import com.bartixxx.parked.ui.Formatters
import com.bartixxx.parked.ui.MainUiState
import com.bartixxx.parked.ui.PermissionSnapshot
import com.bartixxx.parked.ui.components.EmptyState
import com.bartixxx.parked.ui.components.InfoRow
import com.bartixxx.parked.ui.components.PermissionStatusRow
import com.bartixxx.parked.ui.components.SectionCard

@Composable
fun DiagnosticsScreen(
    events: List<DiagnosticsEvent>,
    permissions: PermissionSnapshot,
    monitor: MonitorRuntimeState,
    uiState: MainUiState,
    onTestLocation: () -> Unit,
    onShareLogs: () -> Unit,
    onClearLogs: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Column {
                Text(
                    text = stringResource(R.string.tab_diagnostics),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.diag_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(key = "status") {
            SectionCard(title = stringResource(R.string.section_monitor_state)) {
                InfoRow(
                    stringResource(R.string.service),
                    stringResource(if (monitor.running) R.string.running else R.string.not_running),
                )
                InfoRow(stringResource(R.string.phase), phaseLabel(monitor.phase))
                InfoRow(
                    stringResource(R.string.label_radio),
                    monitor.targetName ?: monitor.targetAddress
                        ?: stringResource(R.string.no_data),
                )
                InfoRow(
                    stringResource(R.string.connected),
                    stringResource(if (monitor.connected) R.string.yes else R.string.no),
                )
                InfoRow(stringResource(R.string.cycle), monitor.cycleId.toString())
                if (monitor.pendingAtEpochMs > 0L) {
                    InfoRow(
                        stringResource(R.string.save_scheduled),
                        Formatters.dateTime(monitor.pendingAtEpochMs),
                    )
                }
                if (monitor.lastError != null) {
                    Text(
                        text = stringResource(R.string.last_error, monitor.lastError),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        item(key = "permissions") {
            SectionCard(title = stringResource(R.string.section_permissions)) {
                PermissionStatusRow(stringResource(R.string.bluetooth_access), permissions.bluetoothPermission)
                PermissionStatusRow(stringResource(R.string.location), permissions.locationOk)
                PermissionStatusRow(
                    stringResource(R.string.background_location),
                    permissions.backgroundLocation,
                )
                PermissionStatusRow(
                    stringResource(R.string.battery_unrestricted),
                    permissions.batteryOptimizationIgnored,
                )
                PermissionStatusRow(
                    stringResource(R.string.bluetooth_on),
                    permissions.bluetoothEnabled,
                )
            }
        }

        item(key = "test") {
            SectionCard(title = stringResource(R.string.section_location_test)) {
                Text(
                    text = stringResource(R.string.location_test_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onTestLocation,
                    enabled = !uiState.testingLocation && permissions.locationOk,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (uiState.testingLocation) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.size(8.dp))
                    } else {
                        Icon(
                            Icons.Filled.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(
                        if (uiState.testingLocation) {
                            stringResource(R.string.fetching)
                        } else {
                            stringResource(R.string.check_location)
                        },
                    )
                }
                uiState.locationTestResult?.let { result ->
                    Text(
                        text = result,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
                if (!permissions.locationOk) {
                    Text(
                        text = stringResource(R.string.grant_location_first),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        item(key = "logs_header") {
            SectionCard(title = stringResource(R.string.logs_title, events.size)) {
                Text(
                    text = stringResource(R.string.logs_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = onShareLogs,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.action_share))
                    }
                    TextButton(
                        onClick = onClearLogs,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Filled.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.action_clear))
                    }
                }
            }
        }

        if (events.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    title = stringResource(R.string.no_events),
                    subtitle = stringResource(R.string.no_events_subtitle),
                ) {
                    Icon(
                        Icons.Filled.BugReport,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(events, key = { it.id }) { event ->
                EventRow(event)
            }
        }

        item(key = "bottom") {
            Spacer(Modifier.size(16.dp))
        }
    }
}

@Composable
private fun EventRow(event: DiagnosticsEvent) {
    val levelColor = when (event.level) {
        DiagnosticLevel.ERROR -> MaterialTheme.colorScheme.error
        DiagnosticLevel.WARN -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = event.level.take(1),
                style = MaterialTheme.typography.labelMedium,
                color = levelColor,
                fontWeight = FontWeight.Bold,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.tag + " • " + Formatters.dateTime(event.timestampEpochMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = event.message,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun phaseLabel(phase: TriggerPhase): String = when (phase) {
    TriggerPhase.UNARMED -> stringResource(R.string.phase_state_unarmed)
    TriggerPhase.CONNECTED -> stringResource(R.string.phase_state_connected)
    TriggerPhase.PENDING -> stringResource(R.string.phase_state_pending)
    TriggerPhase.CAPTURING -> stringResource(R.string.phase_state_capturing)
    TriggerPhase.SAVED -> stringResource(R.string.phase_state_saved)
}
