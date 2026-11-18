package io.github.bartixxx32.parked.ui.screens

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bartixxx32.parked.BuildConfig
import io.github.bartixxx32.parked.bluetooth.BondedDevice
import io.github.bartixxx32.parked.data.settings.AppSettings
import io.github.bartixxx32.parked.ui.PermissionSnapshot
import io.github.bartixxx32.parked.ui.components.PermissionStatusRow
import io.github.bartixxx32.parked.ui.components.SectionCard
import io.github.bartixxx32.parked.ui.components.SettingSlider

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
                    text = "Ustawienia",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Wszystko działa lokalnie — bez konta i bez chmury.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(key = "device") {
            SectionCard(title = "Radio samochodowe") {
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
                            "Nie wybrano urządzenia"
                        } else {
                            "${settings.deviceName ?: "Urządzenie"} (${settings.deviceAddress})"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onRefreshDevices) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Odśwież listę")
                    }
                }

                if (!permissions.bluetoothPermission) {
                    Text(
                        text = "Brak uprawnienia do Bluetooth — lista urządzeń jest niedostępna.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
                        Text("Przyznaj dostęp do Bluetooth")
                    }
                } else if (bondedDevices.isEmpty()) {
                    Text(
                        text = "Brak sparowanych urządzeń. Sparuj radio w systemowych " +
                            "ustawieniach Bluetooth, a potem wróć tutaj i odśwież listę.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        onClick = onOpenBluetoothSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Otwórz ustawienia Bluetooth")
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
                            Text("Wyczyść wybór urządzenia")
                        }
                    }
                }

                Text(
                    text = "Wybierz jedno radio. Inne urządzenia Bluetooth nie wpływają " +
                        "na zapis. Zmiana urządzenia wymaga ponownego włączenia monitorowania.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(key = "automation") {
            SectionCard(title = "Automatyczny zapis") {
                SettingSlider(
                    title = "Opóźnienie po rozłączeniu",
                    value = settings.saveDelaySeconds,
                    range = AppSettings.SAVE_DELAY_RANGE,
                    step = 5,
                    unit = "s",
                    description = "Lokalizacja jest pobierana od razu przy rozłączeniu. To opóźnienie " +
                        "tylko odkłada zapis, żeby dać czas na ponowne połączenie (wtedy zapis jest anulowany).",
                    onValueChange = onSaveDelayChange,
                )
                SettingSlider(
                    title = "Maksymalny czas oczekiwania na GPS",
                    value = settings.gpsTimeoutSeconds,
                    range = AppSettings.GPS_TIMEOUT_RANGE,
                    step = 5,
                    unit = "s",
                    description = "Po tym czasie aplikacja użyje ostatniej znanej lokalizacji (jeśli dozwolone).",
                    onValueChange = onGpsTimeoutChange,
                )
                SettingSlider(
                    title = "Maksymalna dopuszczalna dokładność",
                    value = settings.maxAccuracyMeters,
                    range = AppSettings.MAX_ACCURACY_RANGE,
                    step = 10,
                    unit = "m",
                    description = "Mniej dokładne pomiary są odrzucane, aby nie zapisać przypadkowego punktu.",
                    onValueChange = onMaxAccuracyChange,
                )
                SwitchRow(
                    title = "Awaryjna ostatnia znana lokalizacja",
                    subtitle = "Jeśli świeży GPS jest niedostępny, zapisz ostatni znany punkt i oznacz go ostrzeżeniem.",
                    checked = settings.allowFallbackLastKnown,
                    onCheckedChange = onFallbackEnabledChange,
                )
                SettingSlider(
                    title = "Maksymalny wiek punktu awaryjnego",
                    value = settings.fallbackMaxAgeHours,
                    range = AppSettings.FALLBACK_MAX_AGE_RANGE,
                    step = 1,
                    unit = "h",
                    enabled = settings.allowFallbackLastKnown,
                    onValueChange = onFallbackMaxAgeChange,
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Powiadomienia",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Parked nie wysyła powiadomień o zapisie ani błędach. " +
                            "Jedyne powiadomienie to wymagane przez Androida powiadomienie " +
                            "usługi monitorującej. Możesz je wyłączyć w ustawieniach " +
                            "systemowych — monitorowanie nadal będzie działać.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onOpenNotificationSettings) {
                        Text("Ustawienia powiadomień")
                    }
                }
            }
        }

        item(key = "permissions") {
            SectionCard(title = "Uprawnienia") {
                PermissionStatusRow(
                    title = "Bluetooth",
                    granted = permissions.bluetoothPermission,
                    actionLabel = if (!permissions.bluetoothPermission) "Przyznaj" else null,
                    onAction = if (!permissions.bluetoothPermission) onRequestPermissions else null,
                )
                PermissionStatusRow(
                    title = "Lokalizacja (dokładna lub przybliżona)",
                    granted = permissions.locationOk,
                    actionLabel = if (!permissions.locationOk) "Przyznaj" else null,
                    onAction = if (!permissions.locationOk) onRequestPermissions else null,
                )
                PermissionStatusRow(
                    title = "Lokalizacja w tle (\"Zawsze pozwalaj\")",
                    granted = permissions.backgroundLocation,
                    detail = "Wymagana, gdy telefon jest zablokowany lub aplikacja jest w tle.",
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
                    title = "Bluetooth włączony w systemie",
                    granted = permissions.bluetoothEnabled,
                    actionLabel = if (!permissions.bluetoothEnabled) "Włącz" else null,
                    onAction = if (!permissions.bluetoothEnabled) onOpenBluetoothSettings else null,
                )
                PermissionStatusRow(
                    title = "Optymalizacja baterii wyłączona",
                    granted = permissions.batteryOptimizationIgnored,
                    actionLabel = if (!permissions.batteryOptimizationIgnored) "Zmień" else null,
                    onAction = if (!permissions.batteryOptimizationIgnored) onBatterySettings else null,
                )
                Button(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
                    Text("Poproś o wszystkie podstawowe uprawnienia")
                }
            }
        }

        item(key = "reliability") {
            SectionCard(title = "Niezawodność (ColorOS i inne nakładki)") {
                Text(
                    text = "Android może zabić usługę działającą w tle, a OnePlus/ColorOS " +
                        "stosuje własne oszczędzanie energii. Wykonaj poniższe kroki:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "1. Ustawienia aplikacji → Bateria → Bez ograniczeń\n" +
                        "2. Ustawienia aplikacji → Autostart → włącz dla Parked\n" +
                        "3. Zablokuj aplikację w ostatnich zadaniach (kłódka)\n" +
                        "4. Nie ograniczaj aktywności w tle w panelu ColorOS",
                    style = MaterialTheme.typography.bodySmall,
                )
                FilledTonalButton(
                    onClick = onBatterySettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Otwórz ustawienia baterii")
                }
                FilledTonalButton(
                    onClick = onAutostartSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Otwórz ustawienia autostartu")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = onOpenLocationSettings,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Lokalizacja")
                    }
                    TextButton(
                        onClick = onOpenBluetoothSettings,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Bluetooth")
                    }
                    TextButton(
                        onClick = onOpenAppSettings,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Aplikacja")
                    }
                }
                Text(
                    text = "Parked nie może zagwarantować zapisu, jeśli system zatrzyma " +
                        "usługę. W krytycznej sytuacji użyj ręcznego zapisu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(key = "about") {
            SectionCard(title = "O aplikacji") {
                Text(
                    text = "Parked ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "Licencja GPL-3.0. Kod źródłowy rozwijany jest w prywatnym repozytorium.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Prywatność: brak reklam, analityki, telemetrii i konta. " +
                        "Współrzędne, notatki i zdjęcia pozostają wyłącznie na tym urządzeniu. " +
                        "Aplikacja nie wysyła lokalizacji do żadnego serwera; kafelki mapy " +
                        "pobiera bezpośrednio z serwerów OpenStreetMap.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Mapa: © OpenStreetMap contributors, dane na licencji ODbL. " +
                        "Kafelki: OpenStreetMap Standard.",
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
