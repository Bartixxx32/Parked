package io.github.bartixxx32.parked.capture

import io.github.bartixxx32.parked.data.db.ParkingSpot

fun CaptureFailure.userMessage(): String = when (this) {
    CaptureFailure.NO_LOCATION ->
        "Nie udało się ustalić lokalizacji. Sprawdź GPS i uprawnienia do lokalizacji."
    CaptureFailure.INVALID_COORDINATES ->
        "Odrzucono nieprawidłowe współrzędne. Spróbuj ponownie na zewnątrz."
    CaptureFailure.LOW_ACCURACY ->
        "Dokładność GPS była zbyt niska. Zwiększ limit dokładności lub spróbuj później."
    CaptureFailure.STALE_LOCATION ->
        "Lokalizacja była zbyt stara. Włącz dokładne GPS i spróbuj ponownie."
    CaptureFailure.STORAGE_ERROR ->
        "Nie udało się zapisać miejsca w bazie danych."
    CaptureFailure.INTERNAL_ERROR ->
        "Wystąpił błąd wewnętrzny podczas zapisu."
}

fun ParkingSpot.accuracyLabel(): String =
    accuracyMeters?.let { "${it.toInt()} m" } ?: "brak danych"
