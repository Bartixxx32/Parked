package com.bartixxx.parked.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bartixxx.parked.AppContainer
import com.bartixxx.parked.R
import com.bartixxx.parked.backup.BackupException
import com.bartixxx.parked.backup.BackupFailure
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
import com.bartixxx.parked.family.FamilyError
import com.bartixxx.parked.family.FamilyState
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
    val backupBusy: Boolean = false,
)

class MainViewModel(private val container: AppContainer) : ViewModel() {

    private val appContext = container.appContext

    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val familyState: StateFlow<FamilyState> = container.familyRepository.state

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

    fun updateParkingTimerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setParkingTimerEnabled(enabled)
            syncParkingTimer()
        }
    }

    fun updateParkingTimerMinutes(minutes: Int) {
        viewModelScope.launch {
            container.settingsRepository.setParkingTimerMinutes(minutes)
            syncParkingTimer()
        }
    }

    fun syncParkingTimer() {
        viewModelScope.launch {
            container.parkingTimerScheduler.sync(
                spot = container.parkingRepository.latestSpot(),
                settings = container.settingsRepository.current(),
            )
        }
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
                    is CaptureOutcome.Saved -> {
                        message(
                            appContext.getString(
                                R.string.msg_spot_saved_accuracy,
                                outcome.spot.accuracyMeters?.toInt()?.let { "$it m" }
                                    ?: appContext.getString(R.string.no_data),
                            ),
                        )
                        container.familyRepository.syncSpot(outcome.spot)
                        container.parkingTimerScheduler.sync(outcome.spot, currentSettings)
                    }
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
            val result = runCatching {
                container.parkingRepository.addManualSpot(latitude, longitude, note)
            }
            result.onSuccess {
                message(appContext.getString(R.string.msg_manual_spot_added))
                container.parkingRepository.latestSpot()?.let { spot ->
                    container.familyRepository.syncSpot(spot)
                    container.parkingTimerScheduler.sync(
                        spot,
                        container.settingsRepository.current(),
                    )
                }
            }.onFailure {
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
            val isLatest = runCatching {
                container.parkingRepository.latestSpot()?.id == id
            }.getOrDefault(false)
            runCatching { container.parkingRepository.updateNote(id, note) }
                .onSuccess {
                    message(appContext.getString(R.string.msg_note_saved))
                    if (isLatest) {
                        container.familyRepository.onLocalNoteChanged()?.let { error ->
                            message(familyErrorMessage(error))
                        }
                    }
                }
                .onFailure { message(appContext.getString(R.string.msg_note_save_failed)) }
        }
    }

    fun attachPhoto(spotId: Long, uri: Uri) {
        viewModelScope.launch {
            container.parkingRepository.attachPhoto(spotId, uri)
                .onSuccess {
                    message(appContext.getString(R.string.msg_photo_added))
                    // Replacing a photo makes the previous family copy stale.
                    // Retract it so the family sees only the new image.
                    container.familyRepository.revokePhotosForSpot(spotId)?.let { error ->
                        message(familyErrorMessage(error))
                    }
                }
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

    fun notifyPhotoCaptureFailed() {
        message(appContext.getString(R.string.msg_photo_capture_failed))
    }

    fun removePhoto(spotId: Long) {
        viewModelScope.launch {
            runCatching { container.parkingRepository.removePhoto(spotId) }
                .onSuccess {
                    message(appContext.getString(R.string.msg_photo_removed))
                    // If this photo was sent to the family, retract it from the
                    // server and from family phones that already downloaded it.
                    container.familyRepository.revokePhotosForSpot(spotId)?.let { error ->
                        message(familyErrorMessage(error))
                    }
                }
                .onFailure { message(appContext.getString(R.string.msg_photo_remove_failed)) }
        }
    }

    fun deleteSpot(id: Long) {
        viewModelScope.launch {
            val wasLatest = runCatching {
                container.parkingRepository.latestSpot()?.id == id
            }.getOrDefault(false)
            runCatching { container.parkingRepository.deleteSpot(id) }
                .onSuccess {
                    message(appContext.getString(R.string.msg_spot_deleted))
                    if (wasLatest) {
                        container.parkingTimerScheduler.cancel()
                        container.familyRepository.onLocalSpotDeleted()?.let { error ->
                            message(familyErrorMessage(error))
                        }
                    }
                    container.familyRepository.revokePhotosForSpot(id)?.let { error ->
                        message(familyErrorMessage(error))
                    }
                }
                .onFailure { message(appContext.getString(R.string.msg_spot_delete_failed)) }
        }
    }

    fun deleteAllSpots() {
        viewModelScope.launch {
            runCatching { container.parkingRepository.deleteAll() }
                .onSuccess {
                    message(appContext.getString(R.string.msg_history_deleted))
                    container.parkingTimerScheduler.cancel()
                    container.familyRepository.onLocalSpotDeleted()?.let { error ->
                        message(familyErrorMessage(error))
                    }
                    container.familyRepository.revokeAllSentPhotos()?.let { error ->
                        message(familyErrorMessage(error))
                    }
                }
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

    fun backupSuggestedName(): String = container.backupManager.suggestedName()

    fun createBackup(password: String, uri: Uri) {
        if (_uiState.value.backupBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(backupBusy = true) }
            try {
                val summary = container.backupManager.export(uri, password.toCharArray())
                message(
                    appContext.getString(
                        R.string.backup_created,
                        summary.spots,
                        summary.photos,
                    ),
                )
            } catch (error: Throwable) {
                message(backupErrorMessage(error))
            } finally {
                _uiState.update { it.copy(backupBusy = false) }
            }
        }
    }

    fun restoreBackup(password: String, uri: Uri) {
        if (_uiState.value.backupBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(backupBusy = true) }
            try {
                val summary = container.backupManager.restore(uri, password.toCharArray())
                // Old family photo ids no longer match the restored spots.
                runCatching { container.familyRepository.revokeAllSentPhotos() }
                runCatching { container.familyRepository.onLocalSpotDeleted() }
                // Restored settings force monitoring off; make sure a running service stops too.
                container.triggerStateRepository.clear()
                BluetoothMonitorService.stop(appContext)
                message(
                    appContext.getString(
                        R.string.backup_restored,
                        summary.spots,
                        summary.photos,
                    ),
                )
            } catch (error: Throwable) {
                message(backupErrorMessage(error))
            } finally {
                _uiState.update { it.copy(backupBusy = false) }
            }
        }
    }

    private fun backupErrorMessage(error: Throwable): String = when (
        (error as? BackupException)?.failure
    ) {
        BackupFailure.WRONG_PASSWORD_OR_CORRUPT ->
            appContext.getString(R.string.backup_error_password)
        BackupFailure.INVALID_FORMAT -> appContext.getString(R.string.backup_error_invalid)
        BackupFailure.UNSUPPORTED_VERSION ->
            appContext.getString(R.string.backup_error_unsupported)
        BackupFailure.TOO_LARGE -> appContext.getString(R.string.backup_error_too_large)
        BackupFailure.IO -> appContext.getString(R.string.backup_error_io)
        null -> appContext.getString(
            R.string.backup_error_unknown,
            error.message.orEmpty(),
        )
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

    fun createFamilyGroup(carLabel: String) {
        viewModelScope.launch {
            val error = container.familyRepository.createGroup(carLabel)
            message(
                if (error == null) {
                    appContext.getString(R.string.msg_family_created)
                } else {
                    familyErrorMessage(error)
                },
            )
        }
    }

    fun joinFamilyGroup(inviteCode: String, carLabel: String) {
        viewModelScope.launch {
            val error = container.familyRepository.joinGroup(inviteCode, carLabel)
            message(
                if (error == null) {
                    appContext.getString(R.string.msg_family_joined)
                } else {
                    familyErrorMessage(error)
                },
            )
        }
    }

    fun leaveFamilyGroup() {
        viewModelScope.launch {
            val error = container.familyRepository.leaveGroup()
            message(
                if (error == null) {
                    appContext.getString(R.string.msg_family_left)
                } else {
                    familyErrorMessage(error)
                },
            )
        }
    }

    fun refreshFamily(showMessage: Boolean = true) {
        viewModelScope.launch {
            val error = container.familyRepository.refresh()
            if (showMessage) {
                message(
                    if (error == null) {
                        appContext.getString(R.string.msg_family_refreshed)
                    } else {
                        familyErrorMessage(error)
                    },
                )
            }
        }
    }

    fun sendFamilyPhoto(spot: ParkingSpot) {
        viewModelScope.launch {
            val error = container.familyRepository.sendPhoto(spot)
            message(
                if (error == null) {
                    appContext.getString(R.string.msg_family_photo_sent)
                } else {
                    familyErrorMessage(error)
                },
            )
        }
    }

    fun rotateFamilyCode() {
        viewModelScope.launch {
            val error = container.familyRepository.rotateInviteCode()
            message(
                if (error == null) {
                    appContext.getString(R.string.msg_family_rotated)
                } else {
                    familyErrorMessage(error)
                },
            )
        }
    }

    fun setFamilyShareNote(enabled: Boolean) {
        viewModelScope.launch {
            val error = container.familyRepository.setShareNote(enabled)
            if (error != null) message(familyErrorMessage(error))
        }
    }

    fun setFamilyEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val error = container.familyRepository.setEnabled(enabled)
            message(
                if (error == null) {
                    appContext.getString(
                        if (enabled) R.string.msg_family_enabled else R.string.msg_family_disabled,
                    )
                } else {
                    familyErrorMessage(error)
                },
            )
        }
    }

    fun setFamilyCarLabel(carLabel: String) {
        viewModelScope.launch {
            val error = container.familyRepository.updateCarLabel(carLabel)
            message(
                if (error == null) {
                    appContext.getString(R.string.msg_family_name_updated)
                } else {
                    familyErrorMessage(error)
                },
            )
        }
    }

    fun setFamilyGroupName(groupName: String) {
        viewModelScope.launch {
            val error = container.familyRepository.updateGroupName(groupName)
            message(
                if (error == null) {
                    appContext.getString(R.string.msg_family_group_name_updated)
                } else {
                    familyErrorMessage(error)
                },
            )
        }
    }

    fun setFamilyServerUrl(url: String) {
        viewModelScope.launch {
            val error = container.familyRepository.setServerUrl(url)
            message(
                if (error == null) {
                    appContext.getString(R.string.msg_family_server_updated)
                } else {
                    familyErrorMessage(error)
                },
            )
        }
    }

    private fun familyErrorMessage(error: FamilyError): String = appContext.getString(
        when (error) {
            FamilyError.NETWORK -> R.string.family_error_network
            FamilyError.INVALID_CODE -> R.string.family_error_invalid_code
            FamilyError.INVALID_INVITE -> R.string.family_error_invalid_invite
            FamilyError.UNAUTHORIZED -> R.string.family_error_unauthorized
            FamilyError.INVALID_NAME -> R.string.family_error_invalid_name
            FamilyError.INVALID_URL -> R.string.family_error_invalid_url
            FamilyError.NOT_CONNECTED -> R.string.family_error_not_connected
            FamilyError.RATE_LIMITED -> R.string.family_error_rate_limited
            FamilyError.GROUP_FULL -> R.string.family_error_group_full
            FamilyError.CRYPTO -> R.string.family_error_crypto
            FamilyError.PHOTO_LIMIT -> R.string.family_error_photo_limit
            FamilyError.PHOTO_DISABLED -> R.string.family_error_photo_disabled
            FamilyError.PHOTO_TOO_LARGE -> R.string.family_error_photo_too_large
            FamilyError.PHOTO_FAILED -> R.string.family_error_photo_failed
            FamilyError.PHOTO_ALREADY_SENT -> R.string.family_error_photo_already_sent
            FamilyError.NO_PHOTO -> R.string.family_error_no_photo
            FamilyError.NO_RECIPIENTS -> R.string.family_error_no_recipients
            FamilyError.UNSUPPORTED -> R.string.family_error_unsupported
            FamilyError.SERVER -> R.string.family_error_server
            FamilyError.UNKNOWN -> R.string.family_error_unknown
        },
    )

    private fun message(text: String) {
        _messages.tryEmit(text)
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { MainViewModel(container) }
        }
    }
}
