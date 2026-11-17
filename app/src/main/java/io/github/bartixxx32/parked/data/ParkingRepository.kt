package io.github.bartixxx32.parked.data

import android.content.Context
import android.net.Uri
import io.github.bartixxx32.parked.data.db.ParkingSpot
import io.github.bartixxx32.parked.data.db.ParkingSpotDao
import io.github.bartixxx32.parked.data.db.SpotSource
import io.github.bartixxx32.parked.location.LocationValidator
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class ParkingRepository(
    private val context: Context,
    private val dao: ParkingSpotDao,
) : io.github.bartixxx32.parked.capture.ParkingSink {

    val spots: Flow<List<ParkingSpot>> = dao.observeAll()
    val latest: Flow<ParkingSpot?> = dao.observeLatest()

    override suspend fun insertIfNew(spot: ParkingSpot): Long = dao.insert(spot)

    suspend fun allSpots(): List<ParkingSpot> = dao.all()

    suspend fun latestSpot(): ParkingSpot? = dao.latest()

    suspend fun maxCycleId(): Long = dao.maxCycleId() ?: 0L

    suspend fun spotById(id: Long): ParkingSpot? = dao.byId(id)

    suspend fun addManualSpot(
        latitude: Double,
        longitude: Double,
        note: String,
    ): Long {
        require(LocationValidator.coordinatesValid(latitude, longitude)) {
            "Nieprawidłowe współrzędne"
        }
        val now = System.currentTimeMillis()
        return dao.insert(
            ParkingSpot(
                latitude = latitude,
                longitude = longitude,
                accuracyMeters = null,
                savedAtEpochMs = now,
                fixTimeEpochMs = now,
                note = note.trim().take(MAX_NOTE_LENGTH),
                source = SpotSource.MANUAL,
                provider = "manual",
            ),
        )
    }

    suspend fun updateNote(id: Long, note: String) {
        dao.updateNote(id, note.trim().take(MAX_NOTE_LENGTH))
    }

    suspend fun deleteSpot(id: Long) {
        val spot = dao.byId(id)
        spot?.photoPath?.let { deletePhotoFile(it) }
        dao.deleteById(id)
    }

    suspend fun deleteAll() {
        dao.all().forEach { spot -> spot.photoPath?.let { deletePhotoFile(it) } }
        dao.deleteAll()
    }

    suspend fun attachPhoto(spotId: Long, uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val spot = dao.byId(spotId) ?: error("Nie znaleziono wpisu")
            val newFile = copyPhotoToInternalStorage(spotId, uri)
            try {
                spot.photoPath?.let { old -> deletePhotoFile(old) }
                dao.updatePhotoPath(spotId, newFile.absolutePath)
                Unit
            } catch (error: Throwable) {
                newFile.delete()
                throw error
            }
        }
    }

    suspend fun removePhoto(spotId: Long) {
        val spot = dao.byId(spotId) ?: return
        spot.photoPath?.let { deletePhotoFile(it) }
        dao.updatePhotoPath(spotId, null)
    }

    private fun photosDir(): File = File(context.filesDir, "photos").apply { mkdirs() }

    private fun copyPhotoToInternalStorage(spotId: Long, uri: Uri): File {
        val target = File(photosDir(), "spot_${spotId}_${System.currentTimeMillis()}.jpg")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Nie można otworzyć wybranego zdjęcia")
            input.use { source ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_PHOTO_BYTES) {
                            throw IOException("Zdjęcie jest za duże (limit 25 MB)")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (target.length() <= 0L) throw IOException("Wybrany plik jest pusty")
            return target
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    private fun deletePhotoFile(path: String) {
        runCatching {
            val photos = photosDir().canonicalFile
            val file = File(path).canonicalFile
            if (file.path.startsWith(photos.path + File.separator)) {
                file.delete()
            }
        }
    }

    companion object {
        const val MAX_NOTE_LENGTH = 500
        private const val MAX_PHOTO_BYTES = 25L * 1024L * 1024L
    }
}
