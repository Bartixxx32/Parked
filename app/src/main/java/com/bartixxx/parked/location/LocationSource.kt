package com.bartixxx.parked.location

/**
 * Abstraction over Android's LocationManager and the optional fused provider.
 * Implementations must never throw for a missing permission; they return null instead.
 */
interface LocationSource {
    /**
     * Requests a single fresh fix. Stops early when a fix at least as accurate as
     * [acceptableAccuracyMeters] arrives, otherwise returns the best fix before [timeoutMs].
     */
    suspend fun getCurrentFix(timeoutMs: Long, acceptableAccuracyMeters: Float): LocationFix?

    /** Best available cached fix from any provider, if the location permission is granted. */
    fun getLastKnownFix(): LocationFix?
}
