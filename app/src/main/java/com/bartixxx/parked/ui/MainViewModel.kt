package com.bartixxx.parked.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bartixxx.parked.AppContainer
import com.bartixxx.parked.R
import com.bartixxx.parked.bluetooth.BondedDevice
import com.bartixxx.parked.capture.CaptureOutcome
import com.bartixxx.parked.capture.CaptureRequest
import com.bartixxx.parked.capture.CaptureTrigger
import com.bartixxx.parked.capture.userMessageRes
import com.bartixxx.parked.data.db.DiagnosticsEvent
import com.bartixxx.parked.data.db.ParkingSpot
import com.bartixxx.parked.data.settings.AppSettings
import com.bartixxx.parked.export.ExportFormat
import com.bartixxx.parked.export.ParkingExporter
import com.bartixxx.parked.location.LocationFix
import com.bartixxx.parked.location.LocationValidator
import com.bartixxx.parked.monitor.BluetoothMonitorService
import com.bartixxx.parked.monitor.MonitorRuntimeState
import com.bartixxx.parked.util.AppIntents
import com.bartixxx.parked.util.PermissionUtils
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
                message(appContext.getString(R.string.msg_monitoring_off))
                return@launch
            }
            if (current.deviceAddress.isNullOrBlank()) {
                message(appContext.getString(R.string.msg_select_radio_first))
                return@launch
            }
            container.settingsRepository.setMonitoringEnabled(true)
            runCatching { BluetoothMonitorService.start(appContext) }
                .onSuccess { message(appContext.getString(R.string.msg_monitoring_on)) }
                .onFailure {
                    message(
                        appContext.getString(
                            R.string.msg_monitoring_start_failed,
                            it.message.orEmpty(),
                        ),
                    )
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
        message(appContext.getString(R.string.msg_pending_cancelled))
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
                message(appContext.getString(R.string.msg_device_selected, device.name))
                if (wasMonitoring) {
                    runCatching { BluetoothMonitorService.start(appContext) }
                        .onFailure {
                            message(
                                appContext.getString(
                                    R.string.msg_service_refresh_failed,
                                    it.message.orEmpty(),
                                ),
                            )
                        }
                }
            } else {
                if (wasMonitoring) {
                    container.settingsRepository.setMonitoringEnabled(false)
                    BluetoothMonitorService.stop(appContext)
                }
                message(appContext.getString(R.string.msg_device_selection_cleared))
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
                        appContext.getString(
                            R.string.msg_spot_saved_accuracy,
                            outcome.spot.accuracyMeters?.toInt()?.let { "$it m" }
                                ?: appContext.getString(R.string.no_data),
                        ),
                    )
                    CaptureOutcome.Duplicate -> message(appContext.getString(R.string.msg_duplicate_spot))
                    is CaptureOutcome.Failed -> message(
                        appContext.getString(outcome.reason.userMessageRes()),
                    )
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
            message(appContext.getString(R.string.msg_invalid_coordinates))
            return
        }
        if (!LocationValidator.coordinatesValid(latitude, longitude)) {
            message(appContext.getString(R.string.msg_coordinates_out_of_range))
            return
        }
        viewModelScope.launch {
            runCatching { container.parkingRepository.addManualSpot(latitude, longitude, note) }
                .onSuccess { message(appContext.getString(R.string.msg_manual_spot_added)) }
                .onFailure {
                    message(
                        appContext.getString(
                            R.string.msg_add_spot_failed,
                            it.message.orEmpty(),
                        ),
                    )
                }
        }
    }

    fun updateNote(id: Long, note: String) {
        viewModelScope.launch {
            runCatching { container.parkingRepository.updateNote(id, note) }
                .onSuccess { message(appContext.getString(R.string.msg_note_saved)) }
                .onFailure { message(appContext.getString(R.string.msg_note_save_failed)) }
        }
    }

    fun attachPhoto(spotId: Long, uri: Uri) {
        viewModelScope.launch {
            container.parkingRepository.attachPhoto(spotId, uri)
                .onSuccess { message(appContext.getString(R.string.msg_photo_added)) }
                .onFailure {
                    message(
                        appContext.getString(
                            R.string.msg_photo_add_failed,
                            it.message.orEmpty(),
                        ),
                    )
                }
        }
    }

    fun removePhoto(spotId: Long) {
        viewModelScope.launch {
            runCatching { container.parkingRepository.removePhoto(spotId) }
                .onSuccess { message(appContext.getString(R.string.msg_photo_removed)) }
                .onFailure { message(appContext.getString(R.string.msg_photo_remove_failed)) }
        }
    }

    fun deleteSpot(id: Long) {
        viewModelScope.launch {
            runCatching { container.parkingRepository.deleteSpot(id) }
                .onSuccess { message(appContext.getString(R.string.msg_spot_deleted)) }
                .onFailure { message(appContext.getString(R.string.msg_spot_delete_failed)) }
        }
    }

    fun deleteAllSpots() {
        viewModelScope.launch {
            runCatching { container.parkingRepository.deleteAll() }
                .onSuccess { message(appContext.getString(R.string.msg_history_deleted)) }
                .onFailure { message(appContext.getString(R.string.msg_history_delete_failed)) }
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
                        ?: error(appContext.getString(R.string.msg_open_output_failed))
                    stream.use { output ->
                        output.write(payload.content.toByteArray(Charsets.UTF_8))
                    }
                }
            }.onSuccess {
                message(appContext.getString(R.string.msg_exported, payload.fileName))
            }.onFailure {
                message(
                    appContext.getString(
                        R.string.msg_export_failed,
                        it.message.orEmpty(),
                    ),
                )
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
                it.copy(
                    testingLocation = true,
                    locationTestResult = appContext.getString(R.string.test_getting_location),
                )
            }
            val currentSettings = container.settingsRepository.current()
            val fix = container.locationSource.getCurrentFix(
                timeoutMs = 20_000L,
                acceptableAccuracyMeters = currentSettings.maxAccuracyMeters.toFloat(),
            )
            val lastKnown = container.locationSource.getLastKnownFix()
            val result = buildString {
                append(
                    fixText(appContext.getString(R.string.test_fresh_fix), fix),
                )
                if (fix == null && lastKnown != null) {
                    append("\n")
                    append(
                        fixText(appContext.getString(R.string.test_last_known), lastKnown),
                    )
                }
            }.ifBlank { appContext.getString(R.string.test_no_location) }
            container.diagnosticsRepository.info(
                "Test GPS",
                fixTextEnglish("Fresh fix", fix) +
                    if (fix == null && lastKnown != null) {
                        " " + fixTextEnglish("Last known", lastKnown)
                    } else {
                        ""
                    },
            )
            _uiState.update {
                it.copy(testingLocation = false, locationTestResult = result)
            }
        }
    }

    fun clearDiagnostics() {
        container.diagnosticsRepository.clear()
        message(appContext.getString(R.string.msg_logs_cleared))
    }

    fun shareDiagnostics() {
        viewModelScope.launch {
            val text = container.diagnosticsRepository.recentAsText()
                .ifBlank { appContext.getString(R.string.msg_no_events) }
            if (!AppIntents.shareText(
                    appContext,
                    text,
                    appContext.getString(R.string.msg_share_subject),
                )
            ) {
                message(appContext.getString(R.string.msg_no_share_app))
            }
        }
    }

    private fun fixText(label: String, fix: LocationFix?): String {
        if (fix == null) return appContext.getString(R.string.location_fix_none, label)
        val ageSeconds = ((System.currentTimeMillis() - fix.timeEpochMs) / 1000L).coerceAtLeast(0L)
        return appContext.getString(
            R.string.location_fix_line,
            label,
            fix.latitude,
            fix.longitude,
            fix.accuracyMeters.toInt(),
            ageSeconds,
            fix.provider ?: "?",
        ) + if (fix.isMock) " • MOCK" else ""
    }

    /** English-only text written to the diagnostics log. */
    private fun fixTextEnglish(label: String, fix: LocationFix?): String {
        if (fix == null) return "$label: none"
        val ageSeconds = ((System.currentTimeMillis() - fix.timeEpochMs) / 1000L).coerceAtLeast(0L)
        return buildString {
            append(label).append(": ")
            append(String.format(Locale.US, "%.5f, %.5f", fix.latitude, fix.longitude))
            append(" • accuracy ").append(fix.accuracyMeters.toInt()).append(" m")
            append(" • ").append(ageSeconds).append(" s ago")
            append(" • ").append(fix.provider ?: "?")
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
