package com.bartixxx.parked.capture

import androidx.annotation.StringRes
import com.bartixxx.parked.R

/** Localized message shown to the user in the UI. */
@StringRes
fun CaptureFailure.userMessageRes(): Int = when (this) {
    CaptureFailure.NO_LOCATION -> R.string.failure_no_location
    CaptureFailure.INVALID_COORDINATES -> R.string.failure_invalid_coordinates
    CaptureFailure.LOW_ACCURACY -> R.string.failure_low_accuracy
    CaptureFailure.STALE_LOCATION -> R.string.failure_stale_location
    CaptureFailure.STORAGE_ERROR -> R.string.failure_storage
    CaptureFailure.INTERNAL_ERROR -> R.string.failure_internal
}

/** Technical description stored in the diagnostics log, always in English. */
fun CaptureFailure.diagnosticMessage(): String = when (this) {
    CaptureFailure.NO_LOCATION -> "No location fix"
    CaptureFailure.INVALID_COORDINATES -> "Invalid coordinates"
    CaptureFailure.LOW_ACCURACY -> "GPS accuracy too low"
    CaptureFailure.STALE_LOCATION -> "Location fix too old"
    CaptureFailure.STORAGE_ERROR -> "Storage error"
    CaptureFailure.INTERNAL_ERROR -> "Internal error"
}
