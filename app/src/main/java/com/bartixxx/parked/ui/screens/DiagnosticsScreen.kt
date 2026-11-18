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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
                    text = "Diagnostyka",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Stan usługi, uprawnienia i lokalne logi zdarzeń.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(key = "status") {
            SectionCard(title = "Stan monitorowania") {
                InfoRow("Usługa", if (monitor.running) "działa" else "nie działa")
                InfoRow("Faza", phaseLabel(monitor.phase))
                InfoRow("Radio", monitor.targetName ?: monitor.targetAddress ?: "brak")
                InfoRow("Połączone", if (monitor.connected) "tak" else "nie")
                InfoRow("Cykl", monitor.cycleId.toString())
                if (monitor.pendingAtEpochMs > 0L) {
                    InfoRow("Zapis zaplanowany", Formatters.dateTime(monitor.pendingAtEpochMs))
                }
                if (monitor.lastError != null) {
                    Text(
                        text = "Ostatni błąd: ${monitor.lastError}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        item(key = "permissions") {
            SectionCard(title = "Uprawnienia") {
                PermissionStatusRow("Bluetooth", permissions.bluetoothPermission)
                PermissionStatusRow("Lokalizacja", permissions.locationOk)
                PermissionStatusRow("Lokalizacja w tle", permissions.backgroundLocation)
                PermissionStatusRow("Bateria bez ograniczeń", permissions.batteryOptimizationIgnored)
                PermissionStatusRow("Bluetooth włączony", permissions.bluetoothEnabled)
            }
        }

        item(key = "test") {
            SectionCard(title = "Test lokalizacji") {
                Text(
                    text = "Pobiera pojedynczy pomiar GPS i pokazuje dokładność oraz wiek " +
                        "współrzędnych. Nie zapisuje miejsca w historii.",
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
                    Text(if (uiState.testingLocation) "Pobieranie…" else "Sprawdź lokalizację")
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
                        text = "Najpierw przyznaj uprawnienie do lokalizacji.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        item(key = "logs_header") {
            SectionCard(title = "Logi (${events.size})") {
                Text(
                    text = "Zdarzenia są przechowywane lokalnie i ograniczone do 300 wpisów. " +
                        "Nie zawierają pełnych współrzędnych poza testem GPS.",
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
                        Text("Udostępnij")
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
                        Text("Wyczyść")
                    }
                }
            }
        }

        if (events.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    title = "Brak zdarzeń",
                    subtitle = "Logi pojawią się po uruchomieniu monitorowania.",
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

private fun phaseLabel(phase: TriggerPhase): String = when (phase) {
    TriggerPhase.UNARMED -> "oczekiwanie"
    TriggerPhase.CONNECTED -> "połączono"
    TriggerPhase.PENDING -> "zaplanowany zapis"
    TriggerPhase.CAPTURING -> "pobieranie lokalizacji"
    TriggerPhase.SAVED -> "zapisano"
}
