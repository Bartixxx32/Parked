package io.github.bartixxx32.parked.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Optional fused provider. Only used when Google Play Services is present; the app never
 * requires it and silently falls back to [PlatformLocationSource] otherwise.
 */
class FusedLocationSource(private val context: Context) {

    val isAvailable: Boolean by lazy {
        runCatching {
            GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        }.getOrDefault(false)
    }

    suspend fun getCurrentFix(
        timeoutMs: Long,
        acceptableAccuracyMeters: Float,
    ): LocationFix? {
        if (!isAvailable || !hasLocationPermission()) return null
        val client = runCatching {
            LocationServices.getFusedLocationProviderClient(context)
        }.getOrNull() ?: return null
        val cancellation = CancellationTokenSource()

        return try {
            withTimeoutOrNull(timeoutMs.coerceAtLeast(500L)) {
                suspendCancellableCoroutine { continuation ->
                    runCatching {
                        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token)
                    }.onSuccess { task ->
                        task.addOnSuccessListener { location ->
                            if (continuation.isActive) {
                                val fix = location?.toLocationFix(isLastKnown = false)
                                if (fix != null && fix.accuracyMeters in 0f..acceptableAccuracyMeters) {
                                    continuation.resume(fix)
                                } else {
                                    continuation.resume(fix)
                                }
                            }
                        }.addOnFailureListener {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }.onFailure {
                        if (continuation.isActive) continuation.resume(null)
                    }
                    continuation.invokeOnCancellation { cancellation.cancel() }
                }
            }
        } catch (_: SecurityException) {
            null
        } finally {
            cancellation.cancel()
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
}
