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
import androidx.compose.material.icons.filled.Bluetooth
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    onCancelPending: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenMap: (ParkingSpot) -> Unit,
    onNavigate: (ParkingSpot) -> Unit,
    onOpenSettings: () -> Unit,
    onRequestPermissions: () -> Unit,
    onRequestBackgroundLocation: () -> Unit,
    onBatterySettings: () -> Unit,
    onAutostartSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
) {
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
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
                    text = "Miejsce parkingowe zapisze się po rozłączeniu z radiem",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard(title = "Monitorowanie") {
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
                        text = "Radio samochodowe",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = settings.deviceName ?: settings.deviceAddress ?: "nie wybrano",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                TextButton(onClick = onOpenSettings) {
                    Text(if (settings.deviceAddress == null) "Wybierz" else "Zmień")
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
                        text = "Zapis lokalizacji za ok. $remainingSeconds s",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    TextButton(onClick = onCancelPending) {
                        Text("Anuluj")
                    }
                }
            }

            if (settings.monitoringEnabled && !monitor.running) {
                Text(
                    text = "Usługa monitorowania nie działa. System mógł ją zatrzymać, " +
                        "albo nie udało się jej uruchomić.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                FilledTonalButton(
                    onClick = { onToggleMonitoring(true) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Uruchom monitorowanie ponownie")
                }
            }

            if (settings.deviceAddress == null) {
                Text(
                    text = "Aby zacząć, sparuj radio w systemowych ustawieniach Bluetooth, " +
                        "a potem wskaż je w ustawieniach aplikacji.",
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
            SectionCard(title = "Uprawnienia i niezawodność") {
                PermissionStatusRow(
                    title = "Dostęp do Bluetooth",
                    granted = permissions.bluetoothPermission,
                    detail = "Wymagany do wykrywania połączenia z radiem.",
                )
                PermissionStatusRow(
                    title = "Lokalizacja",
                    granted = permissions.locationOk,
                    detail = if (permissions.locationOk) {
                        "Dokładna lub przybliżona — do ustalenia miejsca."
                    } else {
                        "Bez niej nie da się zapisać współrzędnych."
                    },
                )
                PermissionStatusRow(
                    title = "Lokalizacja w tle",
                    granted = permissions.backgroundLocation,
                    detail = "\"Zawsze pozwalaj\" — potrzebne przy zablokowanym telefonie.",
                    actionLabel = if (permissions.locationOk && !permissions.backgroundLocation) {
                        "Włącz"
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
                    title = "Bluetooth włączony",
                    granted = permissions.bluetoothEnabled,
                    detail = "Włącz Bluetooth w systemie.",
                    actionLabel = if (!permissions.bluetoothEnabled) "Ustawienia" else null,
                    onAction = if (!permissions.bluetoothEnabled) onOpenBluetoothSettings else null,
                )
                PermissionStatusRow(
                    title = "Optymalizacja baterii wyłączona",
                    granted = permissions.batteryOptimizationIgnored,
                    detail = "Zmniejsza ryzyko ubicia usługi przez system.",
                    actionLabel = if (!permissions.batteryOptimizationIgnored) "Zmień" else null,
                    onAction = if (!permissions.batteryOptimizationIgnored) onBatterySettings else null,
                )

                if (missingForeground) {
                    Button(
                        onClick = onRequestPermissions,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Przyznaj wymagane uprawnienia")
                    }
                }
                if (missingBackground) {
                    FilledTonalButton(
                        onClick = onRequestBackgroundLocation,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Zezwól na lokalizację w tle")
                    }
                }
            }
        }

        SectionCard(title = "Ostatnie miejsce") {
            val spot = latestSpot
            if (spot == null) {
                EmptyState(
                    title = "Brak zapisanych miejsc",
                    subtitle = "Tutaj pojawi się pierwsze automatyczne lub ręczne miejsce.",
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
                InfoRow("Współrzędne", Formatters.coordinates(spot.latitude, spot.longitude))
                InfoRow("Dokładność", Formatters.accuracy(spot))
                InfoRow("Źródło", Formatters.source(spot))
                if (spot.note.isNotBlank()) {
                    InfoRow("Notatka", spot.note)
                }
                if (spot.isFallback) {
                    Text(
                        text = "Punkt awaryjny: użyto ostatniej znanej lokalizacji.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (spot.isMock) {
                    Text(
                        text = "Uwaga: lokalizacja pochodzi z providera testowego (mock).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Button(
                    onClick = onSaveNow,
                    enabled = !saving && permissions.locationOk,
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
                    Text(if (saving) "Zapisywanie…" else "Zapisz bieżącą lokalizację")
                }
                if (!permissions.locationOk) {
                    Text(
                        text = "Zapis ręczny wymaga uprawnienia do lokalizacji.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalButton(
                        onClick = { onOpenMap(spot) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Mapa")
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
                        Text("Nawiguj")
                    }
                }
                TextButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Cała historia")
                }
            }
        }

        SectionCard(title = "Niezawodność na Androidzie") {
            Text(
                text = "Android i nakładki producentów (np. ColorOS) mogą ograniczać pracę " +
                    "w tle i zabijać usługi. Aby zwiększyć szansę na automatyczny zapis:",
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
                    Text("• pozwól aplikacji działać w tle bez ograniczeń,", style = MaterialTheme.typography.bodySmall)
                    Text("• włącz autostart dla Parked,", style = MaterialTheme.typography.bodySmall)
                    Text("• wyłącz optymalizację baterii dla Parked,", style = MaterialTheme.typography.bodySmall)
                    Text("• nie usuwaj aplikacji z listy ostatnich zadań.", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                text = "Żadna aplikacja nie może zagwarantować zapisu, jeśli system ją zabije. " +
                    "W razie potrzeby użyj przycisku ręcznego zapisu.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onBatterySettings,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Bateria")
                }
                FilledTonalButton(
                    onClick = onAutostartSettings,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Autostart")
                }
            }
        }
    }
}

@Composable
private fun PhaseBadge(phase: TriggerPhase) {
    val label: String
    val color: Color
    when (phase) {
        TriggerPhase.UNARMED -> {
            label = "Oczekiwanie na połączenie"
            color = MaterialTheme.colorScheme.outline
        }

        TriggerPhase.CONNECTED -> {
            label = "Radio połączone"
            color = MaterialTheme.colorScheme.primary
        }

        TriggerPhase.PENDING -> {
            label = "Za chwilę zapis"
            color = MaterialTheme.colorScheme.tertiary
        }

        TriggerPhase.CAPTURING -> {
            label = "Ustalanie lokalizacji…"
            color = MaterialTheme.colorScheme.tertiary
        }

        TriggerPhase.SAVED -> {
            label = "Miejsce zapisane"
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

private fun statusTitle(settings: AppSettings, monitor: MonitorRuntimeState): String = when {
    !settings.monitoringEnabled -> "Monitorowanie wyłączone"
    settings.deviceAddress.isNullOrBlank() -> "Nie wybrano radia"
    !monitor.running -> "Usługa nie działa"
    monitor.connected -> "Radio połączone"
    monitor.phase == TriggerPhase.PENDING -> "Rozłączono — szykuję zapis"
    else -> "Oczekiwanie na radio"
}

private fun statusDetail(
    settings: AppSettings,
    monitor: MonitorRuntimeState,
    pending: Boolean,
    remainingSeconds: Long,
): String = when {
    !settings.monitoringEnabled ->
        "Włącz monitorowanie, aby zapis następował automatycznie po rozłączeniu."

    settings.deviceAddress.isNullOrBlank() ->
        "Wybierz sparowane radio samochodowe."

    !monitor.running ->
        "Usługa monitorowania została zatrzymana."

    pending ->
        "Zapis lokalizacji za ok. $remainingSeconds s. Ponowne połączenie anuluje zapis."

    monitor.connected ->
        "Po rozłączeniu zapis nastąpi po ${settings.saveDelaySeconds} s."

    else ->
        "Połącz się z radiem. Rozłączenie uruchomi procedurę zapisu."
}
