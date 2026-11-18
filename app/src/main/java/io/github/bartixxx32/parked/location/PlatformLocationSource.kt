package io.github.bartixxx32.parked.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * LocationManager-based source. Works on every supported device, with or without
 * Google Play Services. GPS and network providers are queried together.
 */
class PlatformLocationSource(private val context: Context) : LocationSource {

    private val locationManager: LocationManager?
        get() = context.getSystemService(LocationManager::class.java)

    override suspend fun getCurrentFix(
        timeoutMs: Long,
        acceptableAccuracyMeters: Float,
    ): LocationFix? {
        if (!hasLocationPermission()) return null
        val manager = locationManager ?: return null
        val providers = buildList {
            // Android 12+ exposes the system's own fused provider. It combines GPS,
            // Wi-Fi and cell data and may be more accurate than raw GPS, especially in
            // cities. It is not available on every device; registration failures are
            // handled below like any other unavailable provider.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                isEnabled(manager, LocationManager.FUSED_PROVIDER)
            ) {
                add(LocationManager.FUSED_PROVIDER)
            }
            if (isEnabled(manager, LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (isEnabled(manager, LocationManager.NETWORK_PROVIDER)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        }
        if (providers.isEmpty()) return null

        return withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val handler = Handler(Looper.getMainLooper())
                val listeners = mutableMapOf<String, LocationListener>()
                var finished = false
                var best: LocationFix? = null

                fun stopUpdates() {
                    listeners.forEach { (_, listener) ->
                        runCatching { manager.removeUpdates(listener) }
                    }
                    listeners.clear()
                }

                fun finish(fix: LocationFix?) {
                    if (finished) return
                    finished = true
                    handler.removeCallbacksAndMessages(null)
                    stopUpdates()
                    if (continuation.isActive) {
                        runCatching { continuation.resume(fix) }
                    }
                }

                providers.forEach { provider ->
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (finished) return
                            val fix = location.toLocationFix(isLastKnown = false)
                            if (!LocationValidator.coordinatesValid(fix.latitude, fix.longitude)) return
                            if (best == null || isBetter(fix, best!!)) best = fix
                            if (shouldFinishEarly(fix, acceptableAccuracyMeters)) {
                                finish(fix)
                            }
                        }

                        override fun onProviderEnabled(provider: String) = Unit

                        override fun onProviderDisabled(provider: String) = Unit

                        @Deprecated("Deprecated in the platform API")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                    }
                    listeners[provider] = listener
                    try {
                        manager.requestLocationUpdates(
                            provider,
                            0L,
                            0f,
                            listener,
                            Looper.getMainLooper(),
                        )
                    } catch (_: SecurityException) {
                        listeners.remove(provider)
                    } catch (_: IllegalArgumentException) {
                        listeners.remove(provider)
                    }
                }

                handler.postDelayed({ finish(best) }, timeoutMs.coerceAtLeast(500L))

                continuation.invokeOnCancellation {
                    handler.post {
                        finished = true
                        stopUpdates()
                    }
                }
            }
        }
    }

    override fun getLastKnownFix(): LocationFix? {
        if (!hasLocationPermission()) return null
        val manager = locationManager ?: return null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
            "fused",
        ).distinct()

        val now = System.currentTimeMillis()
        return providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }
            .map { location -> location.toLocationFix(isLastKnown = true) }
            .filter { fix -> LocationValidator.coordinatesValid(fix.latitude, fix.longitude) }
            .filter { fix -> fix.accuracyMeters.isFinite() && fix.accuracyMeters >= 0f }
            .minByOrNull { fix -> score(fix, now) }
    }

    private fun isEnabled(manager: LocationManager, provider: String): Boolean =
        runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    private fun score(fix: LocationFix, now: Long): Double {
        val ageSeconds = ((now - fix.timeEpochMs).coerceAtLeast(0L) / 1000.0)
        return ageSeconds + fix.accuracyMeters * 3.0
    }

    private fun isBetter(candidate: LocationFix, current: LocationFix): Boolean {
        if (candidate.accuracyMeters != current.accuracyMeters) {
            return candidate.accuracyMeters < current.accuracyMeters
        }
        return candidate.timeEpochMs > current.timeEpochMs
    }

    /**
     * Stops waiting as soon as the fix is genuinely good instead of accepting the very
     * first callback, which is often a coarse network/cached location. A GPS fix has to be
     * clearly better than a network fix to end the wait early; otherwise listeners keep
     * running until the timeout and the best fix seen so far is returned.
     */
    private fun shouldFinishEarly(fix: LocationFix, acceptableAccuracyMeters: Float): Boolean {
        val accuracy = fix.accuracyMeters
        if (!accuracy.isFinite() || accuracy < 0f) return false
        val target = when (fix.provider) {
            LocationManager.GPS_PROVIDER -> GPS_GOOD_ACCURACY_METERS
            else -> ANY_PROVIDER_GOOD_ACCURACY_METERS
        }
        return accuracy <= minOf(target, acceptableAccuracyMeters)
    }

    private companion object {
        const val GPS_GOOD_ACCURACY_METERS = 15f
        const val ANY_PROVIDER_GOOD_ACCURACY_METERS = 10f
    }
}
