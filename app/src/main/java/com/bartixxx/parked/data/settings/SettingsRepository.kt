package com.bartixxx.parked.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.parkedSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "parked_settings",
)

class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.parkedSettingsDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences -> preferences.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setMonitoringEnabled(enabled: Boolean) {
        context.parkedSettingsDataStore.edit { it[Keys.MONITORING_ENABLED] = enabled }
    }

    suspend fun setSelectedDevice(address: String?, name: String?) {
        context.parkedSettingsDataStore.edit { preferences ->
            if (address.isNullOrBlank()) {
                preferences.remove(Keys.DEVICE_ADDRESS)
                preferences.remove(Keys.DEVICE_NAME)
            } else {
                preferences[Keys.DEVICE_ADDRESS] = address
                preferences[Keys.DEVICE_NAME] = name?.takeIf { it.isNotBlank() } ?: address
            }
        }
    }

    suspend fun setSaveDelaySeconds(seconds: Int) {
        val clamped = seconds.coerceIn(AppSettings.SAVE_DELAY_RANGE)
        context.parkedSettingsDataStore.edit { it[Keys.SAVE_DELAY_SECONDS] = clamped }
    }

    suspend fun setGpsTimeoutSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(AppSettings.GPS_TIMEOUT_RANGE)
        context.parkedSettingsDataStore.edit { it[Keys.GPS_TIMEOUT_SECONDS] = clamped }
    }

    suspend fun setMaxAccuracyMeters(meters: Int) {
        val clamped = meters.coerceIn(AppSettings.MAX_ACCURACY_RANGE)
        context.parkedSettingsDataStore.edit { it[Keys.MAX_ACCURACY_METERS] = clamped }
    }

    suspend fun setAllowFallbackLastKnown(allowed: Boolean) {
        context.parkedSettingsDataStore.edit { it[Keys.ALLOW_FALLBACK] = allowed }
    }

    suspend fun setFallbackMaxAgeHours(hours: Int) {
        val clamped = hours.coerceIn(AppSettings.FALLBACK_MAX_AGE_RANGE)
        context.parkedSettingsDataStore.edit { it[Keys.FALLBACK_MAX_AGE_HOURS] = clamped }
    }

    suspend fun setParkingTimerEnabled(enabled: Boolean) {
        context.parkedSettingsDataStore.edit { it[Keys.PARKING_TIMER_ENABLED] = enabled }
    }

    suspend fun setParkingTimerMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(AppSettings.PARKING_TIMER_RANGE)
        context.parkedSettingsDataStore.edit { it[Keys.PARKING_TIMER_MINUTES] = clamped }
    }

    /**
     * Replaces the user configuration after a backup restore. Monitoring is
     * deliberately left off so opening a backup file cannot silently start a
     * background service; the user can enable it again in Settings.
     */
    suspend fun restoreFromBackup(settings: AppSettings) {
        context.parkedSettingsDataStore.edit { preferences ->
            preferences[Keys.MONITORING_ENABLED] = false
            if (settings.deviceAddress.isNullOrBlank()) {
                preferences.remove(Keys.DEVICE_ADDRESS)
                preferences.remove(Keys.DEVICE_NAME)
            } else {
                preferences[Keys.DEVICE_ADDRESS] = settings.deviceAddress
                preferences[Keys.DEVICE_NAME] = settings.deviceName
                    ?.takeIf { it.isNotBlank() }
                    ?: settings.deviceAddress
            }
            preferences[Keys.SAVE_DELAY_SECONDS] =
                settings.saveDelaySeconds.coerceIn(AppSettings.SAVE_DELAY_RANGE)
            preferences[Keys.GPS_TIMEOUT_SECONDS] =
                settings.gpsTimeoutSeconds.coerceIn(AppSettings.GPS_TIMEOUT_RANGE)
            preferences[Keys.MAX_ACCURACY_METERS] =
                settings.maxAccuracyMeters.coerceIn(AppSettings.MAX_ACCURACY_RANGE)
            preferences[Keys.ALLOW_FALLBACK] = settings.allowFallbackLastKnown
            preferences[Keys.FALLBACK_MAX_AGE_HOURS] =
                settings.fallbackMaxAgeHours.coerceIn(AppSettings.FALLBACK_MAX_AGE_RANGE)
            preferences[Keys.PARKING_TIMER_ENABLED] = settings.parkingTimerEnabled
            preferences[Keys.PARKING_TIMER_MINUTES] =
                settings.parkingTimerMinutes.coerceIn(AppSettings.PARKING_TIMER_RANGE)
        }
    }

    private fun Preferences.toSettings(): AppSettings = AppSettings(
        monitoringEnabled = this[Keys.MONITORING_ENABLED] ?: false,
        deviceAddress = this[Keys.DEVICE_ADDRESS]?.takeIf { it.isNotBlank() },
        deviceName = this[Keys.DEVICE_NAME]?.takeIf { it.isNotBlank() },
        saveDelaySeconds = (this[Keys.SAVE_DELAY_SECONDS]
            ?: AppSettings.DEFAULT_SAVE_DELAY_SECONDS).coerceIn(AppSettings.SAVE_DELAY_RANGE),
        gpsTimeoutSeconds = (this[Keys.GPS_TIMEOUT_SECONDS]
            ?: AppSettings.DEFAULT_GPS_TIMEOUT_SECONDS).coerceIn(AppSettings.GPS_TIMEOUT_RANGE),
        maxAccuracyMeters = (this[Keys.MAX_ACCURACY_METERS]
            ?: AppSettings.DEFAULT_MAX_ACCURACY_METERS).coerceIn(AppSettings.MAX_ACCURACY_RANGE),
        allowFallbackLastKnown = this[Keys.ALLOW_FALLBACK] ?: true,
        fallbackMaxAgeHours = (this[Keys.FALLBACK_MAX_AGE_HOURS]
            ?: AppSettings.DEFAULT_FALLBACK_MAX_AGE_HOURS).coerceIn(AppSettings.FALLBACK_MAX_AGE_RANGE),
        parkingTimerEnabled = this[Keys.PARKING_TIMER_ENABLED] ?: false,
        parkingTimerMinutes = (this[Keys.PARKING_TIMER_MINUTES]
            ?: AppSettings.DEFAULT_PARKING_TIMER_MINUTES)
            .coerceIn(AppSettings.PARKING_TIMER_RANGE),
    )

    private object Keys {
        val MONITORING_ENABLED = booleanPreferencesKey("monitoring_enabled")
        val DEVICE_ADDRESS = stringPreferencesKey("device_address")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val SAVE_DELAY_SECONDS = intPreferencesKey("save_delay_seconds")
        val GPS_TIMEOUT_SECONDS = intPreferencesKey("gps_timeout_seconds")
        val MAX_ACCURACY_METERS = intPreferencesKey("max_accuracy_meters")
        val ALLOW_FALLBACK = booleanPreferencesKey("allow_fallback_last_known")
        val FALLBACK_MAX_AGE_HOURS = intPreferencesKey("fallback_max_age_hours")
        val PARKING_TIMER_ENABLED = booleanPreferencesKey("parking_timer_enabled")
        val PARKING_TIMER_MINUTES = intPreferencesKey("parking_timer_minutes")
    }
}
