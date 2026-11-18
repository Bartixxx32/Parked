package io.github.bartixxx32.parked.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.bartixxx32.parked.AppContainer
import io.github.bartixxx32.parked.bluetooth.BondedDevice
import io.github.bartixxx32.parked.capture.CaptureOutcome
import io.github.bartixxx32.parked.capture.CaptureRequest
import io.github.bartixxx32.parked.capture.CaptureTrigger
import io.github.bartixxx32.parked.capture.userMessage
import io.github.bartixxx32.parked.data.db.DiagnosticsEvent
import io.github.bartixxx32.parked.data.db.ParkingSpot
import io.github.bartixxx32.parked.data.settings.AppSettings
import io.github.bartixxx32.parked.export.ExportFormat
import io.github.bartixxx32.parked.export.ParkingExporter
import io.github.bartixxx32.parked.location.LocationFix
import io.github.bartixxx32.parked.location.LocationValidator
import io.github.bartixxx32.parked.monitor.BluetoothMonitorService
import io.github.bartixxx32.parked.monitor.MonitorRuntimeState
import io.github.bartixxx32.parked.util.AppIntents
import io.github.bartixxx32.parked.util.PermissionUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MainUiState(
    val saving: Boolean = false,
    val testingLocation: Boolean = false,
    val locationTestResult: String? = null,
)

class MainViewModel(private val container: AppContainer) : ViewModel() {

    private val appContext = container.appContext

    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val spots: StateFlow<List<ParkingSpot>> = container.parkingRepository.spots
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val latestSpot: StateFlow<ParkingSpot?> = container.parkingRepository.latest
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val monitorState: StateFlow<MonitorRuntimeState> = container.monitorState.state

    val diagnostics: StateFlow<List<DiagnosticsEvent>> = container.diagnosticsRepository.events
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _bondedDevices = MutableStateFlow<List<BondedDevice>>(emptyList())
    val bondedDevices: StateFlow<List<BondedDevice>> = _bondedDevices.asStateFlow()

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun setMonitoringEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val current = container.settingsRepository.current()
            if (!enabled) {
                container.settingsRepository.setMonitoringEnabled(false)
                container.triggerStateRepository.clear()
                BluetoothMonitorService.stop(appContext)
                message("Monitorowanie wyłączone")
                return@launch
            }
            if (current.deviceAddress.isNullOrBlank()) {
                message("Najpierw wybierz radio samochodowe w ustawieniach")
                return@launch
            }
            container.settingsRepository.setMonitoringEnabled(true)
            runCatching { BluetoothMonitorService.start(appContext) }
                .onSuccess { message("Monitorowanie włączone") }
                .onFailure {
                    message("Nie udało się uruchomić monitorowania: ${it.message}")
                }
        }
    }

    fun refreshBondedDevices() {
        viewModelScope.launch {
            _bondedDevices.value = withContext(Dispatchers.IO) {
                container.bluetoothRepository.bondedDevices()
            }
        }
    }

    /**
     * Restores monitoring when the app is opened but the service was killed by the
     * system/OEM. Best effort only: Android may still refuse a background FGS start.
     */
    fun ensureMonitoringRunning() {
        viewModelScope.launch {
            val current = container.settingsRepository.current()
            if (!current.monitoringEnabled || current.deviceAddress.isNullOrBlank()) return@launch
            if (BluetoothMonitorService.isRunning) return@launch
            if (!container.bluetoothRepository.hasBluetoothPermission() ||
                !PermissionUtils.hasAnyLocation(appContext)
            ) {
                return@launch
            }
            runCatching { BluetoothMonitorService.start(appContext) }
        }
    }

    fun cancelPendingSave() {
        BluetoothMonitorService.cancelPending(appContext)
        message("Anulowano oczekujący zapis")
    }

    fun showMessage(text: String) {
        message(text)
    }

    fun selectDevice(device: BondedDevice?) {
        viewModelScope.launch {
            val wasMonitoring = container.settingsRepository.current().monitoringEnabled
            container.triggerStateRepository.clear()
            container.settingsRepository.setSelectedDevice(device?.address, device?.name)
            if (device != null) {
                message("Wybrano urządzenie: ${device.name}")
                if (wasMonitoring) {
                    runCatching { BluetoothMonitorService.start(appContext) }
                        .onFailure { message("Nie udało się odświeżyć usługi: ${it.message}") }
                }
            } else {
                if (wasMonitoring) {
                    container.settingsRepository.setMonitoringEnabled(false)
                    BluetoothMonitorService.stop(appContext)
                }
                message("Usunięto wybór urządzenia")
            }
        }
    }

    fun updateSaveDelay(seconds: Int) {
        viewModelScope.launch { container.settingsRepository.setSaveDelaySeconds(seconds) }
    }

    fun updateGpsTimeout(seconds: Int) {
        viewModelScope.launch { container.settingsRepository.setGpsTimeoutSeconds(seconds) }
    }

    fun updateMaxAccuracy(meters: Int) {
        viewModelScope.launch { container.settingsRepository.setMaxAccuracyMeters(meters) }
    }

    fun updateFallbackEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setAllowFallbackLastKnown(enabled) }
    }

    fun updateFallbackMaxAge(hours: Int) {
        viewModelScope.launch { container.settingsRepository.setFallbackMaxAgeHours(hours) }
    }

    fun saveCurrentLocation() {
        if (_uiState.value.saving) return
        viewModelScope.launch {
            _uiState.update { it.copy(saving = true) }
            try {
                val currentSettings = container.settingsRepository.current()
                val outcome = container.captureEngine.capture(
                    request = CaptureRequest(trigger = CaptureTrigger.MANUAL),
                    settings = currentSettings,
                )
                when (outcome) {
                    is CaptureOutcome.Saved -> message(
                        "Zapisano miejsce. Dokładność: " +
                            (outcome.spot.accuracyMeters?.toInt()?.let { "$it m" } ?: "brak danych"),
                    )
                    CaptureOutcome.Duplicate -> message("To miejsce zostało już zapisane")
                    is CaptureOutcome.Failed -> message(outcome.reason.userMessage())
                }
            } finally {
                _uiState.update { it.copy(saving = false) }
            }
        }
    }

    fun addManualSpot(latitudeText: String, longitudeText: String, note: String) {
        val latitude = latitudeText.replace(',', '.').trim().toDoubleOrNull()
        val longitude = longitudeText.replace(',', '.').trim().toDoubleOrNull()
        if (latitude == null || longitude == null) {
            message("Podaj poprawne współrzędne")
            return
        }
        if (!LocationValidator.coordinatesValid(latitude, longitude)) {
            message("Współrzędne są poza dozwolonym zakresem")
            return
        }
        viewModelScope.launch {
            runCatching { container.parkingRepository.addManualSpot(latitude, longitude, note) }
                .onSuccess { message("Dodano miejsce ręcznie") }
                .onFailure { message("Nie udało się dodać miejsca: ${it.message}") }
        }
    }

    fun updateNote(id: Long, note: String) {
        viewModelScope.launch {
            runCatching { container.parkingRepository.updateNote(id, note) }
                .onSuccess { message("Notatka zapisana") }
                .onFailure { message("Nie udało się zapisać notatki") }
        }
    }

    fun attachPhoto(spotId: Long, uri: Uri) {
        viewModelScope.launch {
            container.parkingRepository.attachPhoto(spotId, uri)
                .onSuccess { message("Zdjęcie dodane") }
                .onFailure { message("Nie udało się dodać zdjęcia: ${it.message}") }
        }
    }

    fun removePhoto(spotId: Long) {
        viewModelScope.launch {
            runCatching { container.parkingRepository.removePhoto(spotId) }
                .onSuccess { message("Zdjęcie usunięte") }
                .onFailure { message("Nie udało się usunąć zdjęcia") }
        }
    }

    fun deleteSpot(id: Long) {
        viewModelScope.launch {
            runCatching { container.parkingRepository.deleteSpot(id) }
                .onSuccess { message("Wpis usunięty") }
                .onFailure { message("Nie udało się usunąć wpisu") }
        }
    }

    fun deleteAllSpots() {
        viewModelScope.launch {
            runCatching { container.parkingRepository.deleteAll() }
                .onSuccess { message("Usunięto całą historię") }
                .onFailure { message("Nie udało się usunąć historii") }
        }
    }

    fun exportSpots(format: ExportFormat, uri: Uri) {
        viewModelScope.launch {
            val payload = withContext(Dispatchers.IO) {
                ParkingExporter.export(container.parkingRepository.allSpots(), format)
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    val stream = appContext.contentResolver.openOutputStream(uri)
                        ?: error("Nie można otworzyć pliku docelowego")
                    stream.use { output ->
                        output.write(payload.content.toByteArray(Charsets.UTF_8))
                    }
                }
            }.onSuccess {
                message("Wyeksportowano ${payload.fileName}")
            }.onFailure {
                message("Eksport nie powiódł się: ${it.message}")
            }
        }
    }

    fun exportSuggestedName(format: ExportFormat): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "parked-$stamp.${format.extension}"
    }

    fun testLocation() {
        if (_uiState.value.testingLocation) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(testingLocation = true, locationTestResult = "Pobieranie lokalizacji…")
            }
            val currentSettings = container.settingsRepository.current()
            val fix = container.locationSource.getCurrentFix(
                timeoutMs = 20_000L,
                acceptableAccuracyMeters = currentSettings.maxAccuracyMeters.toFloat(),
            )
            val lastKnown = container.locationSource.getLastKnownFix()
            val result = buildString {
                append(fixText("Świeży pomiar", fix))
                if (fix == null && lastKnown != null) {
                    append("\n")
                    append(fixText("Ostatnia znana", lastKnown))
                }
            }.ifBlank { "Brak lokalizacji. Sprawdź GPS i uprawnienia." }
            container.diagnosticsRepository.info("Test GPS", result.replace('\n', ' '))
            _uiState.update {
                it.copy(testingLocation = false, locationTestResult = result)
            }
        }
    }

    fun clearDiagnostics() {
        container.diagnosticsRepository.clear()
        message("Logi wyczyszczone")
    }

    fun shareDiagnostics() {
        viewModelScope.launch {
            val text = container.diagnosticsRepository.recentAsText()
                .ifBlank { "Brak zdarzeń diagnostycznych." }
            if (!AppIntents.shareText(appContext, text, "Parked — diagnostyka")) {
                message("Brak aplikacji do udostępniania")
            }
        }
    }

    private fun fixText(label: String, fix: LocationFix?): String {
        if (fix == null) return "$label: brak"
        val ageSeconds = ((System.currentTimeMillis() - fix.timeEpochMs) / 1000L).coerceAtLeast(0L)
        return buildString {
            append(label).append(": ")
            append(String.format(Locale.US, "%.5f, %.5f", fix.latitude, fix.longitude))
            append(" • dokładność ").append(fix.accuracyMeters.toInt()).append(" m")
            append(" • ").append(ageSeconds).append(" s temu")
            append(" • ").append(fix.provider)
            if (fix.isMock) append(" • MOCK")
        }
    }

    private fun message(text: String) {
        _messages.tryEmit(text)
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { MainViewModel(container) }
        }
    }
}
