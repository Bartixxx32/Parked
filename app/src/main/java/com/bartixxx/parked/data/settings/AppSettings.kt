package com.bartixxx.parked.data.settings

/**
 * User-visible configuration. Values are clamped when written to DataStore.
 */
data class AppSettings(
    val monitoringEnabled: Boolean = false,
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    val saveDelaySeconds: Int = DEFAULT_SAVE_DELAY_SECONDS,
    val gpsTimeoutSeconds: Int = DEFAULT_GPS_TIMEOUT_SECONDS,
    val maxAccuracyMeters: Int = DEFAULT_MAX_ACCURACY_METERS,
    val allowFallbackLastKnown: Boolean = true,
    val fallbackMaxAgeHours: Int = DEFAULT_FALLBACK_MAX_AGE_HOURS,
) {
    companion object {
        const val DEFAULT_SAVE_DELAY_SECONDS = 15
        const val DEFAULT_GPS_TIMEOUT_SECONDS = 45
        const val DEFAULT_MAX_ACCURACY_METERS = 100
        const val DEFAULT_FALLBACK_MAX_AGE_HOURS = 12

        val SAVE_DELAY_RANGE = 5..180
        val GPS_TIMEOUT_RANGE = 10..180
        val MAX_ACCURACY_RANGE = 10..1000
        val FALLBACK_MAX_AGE_RANGE = 1..72
    }
}
