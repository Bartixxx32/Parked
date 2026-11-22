package com.bartixxx.parked.backup

import android.content.Context
import android.net.Uri
import com.bartixxx.parked.data.ParkingRepository
import com.bartixxx.parked.data.RestoredParkingSpot
import com.bartixxx.parked.data.db.ParkingSpot
import com.bartixxx.parked.data.db.SpotSource
import com.bartixxx.parked.data.settings.AppSettings
import com.bartixxx.parked.data.settings.SettingsRepository
import com.bartixxx.parked.timer.ParkingTimerScheduler
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full local backup: spots, their photos and app settings. The archive is
 * encrypted with a user password before it is written to the selected document.
 * Family credentials, group keys, invite secrets and family photos are never
 * exported. Nothing is uploaded by this class.
 */
class BackupManager(
    private val context: Context,
    private val parkingRepository: ParkingRepository,
    private val settingsRepository: SettingsRepository,
    private val timerScheduler: ParkingTimerScheduler,
) {

    suspend fun export(uri: Uri, password: CharArray): BackupSummary =
        withContext(Dispatchers.IO) {
            val work = createWorkDir()
            try {
                val spots = parkingRepository.allSpots()
                val settings = settingsRepository.current()
                val zip = File(work, "backup.zip")
                val photoNames = writeZip(zip, spots, settings)
                val encrypted = File(work, "backup.parked")
                BackupCrypto.encrypt(zip, encrypted, password)
                val output = try {
                    context.contentResolver.openOutputStream(uri)
                } catch (error: Exception) {
                    throw BackupException(BackupFailure.IO, error)
                } ?: throw BackupException(BackupFailure.IO)
                try {
                    output.use { target ->
                        FileInputStream(encrypted).use { input -> input.copyTo(target) }
                    }
                } catch (error: IOException) {
                    throw BackupException(BackupFailure.IO, error)
                }
                BackupSummary(
                    spots = spots.size,
                    photos = photoNames,
                    bytes = encrypted.length(),
                )
            } finally {
                password.fill('\u0000')
                work.deleteRecursively()
            }
        }

    suspend fun restore(uri: Uri, password: CharArray): BackupSummary =
        withContext(Dispatchers.IO) {
            val work = createWorkDir()
            try {
                val encrypted = File(work, "backup.parked")
                val input = try {
                    context.contentResolver.openInputStream(uri)
                } catch (error: Exception) {
                    throw BackupException(BackupFailure.IO, error)
                } ?: throw BackupException(BackupFailure.IO)
                try {
                    input.use { source ->
                        FileOutputStream(encrypted).use { target ->
                            copyLimited(source, target, MAX_ARCHIVE_BYTES)
                        }
                    }
                } catch (error: IOException) {
                    throw BackupException(BackupFailure.IO, error)
                }
                if (encrypted.length() == 0L) throw BackupException(BackupFailure.INVALID_FORMAT)

                val zip = File(work, "backup.zip")
                BackupCrypto.decrypt(encrypted, zip, password)

                val extracted = File(work, "extracted").apply { mkdirs() }
                val (restoredSettings, restoredSpots) = try {
                    val entries = unzip(zip, extracted)
                    val manifest = entries[MANIFEST]
                        ?: throw BackupException(BackupFailure.INVALID_FORMAT)
                    val spotsFile = entries[SPOTS]
                        ?: throw BackupException(BackupFailure.INVALID_FORMAT)
                    val properties = Properties().apply {
                        FileInputStream(manifest).use { load(it) }
                    }
                    readSettings(properties) to readSpots(spotsFile, entries)
                } catch (error: BackupException) {
                    throw error
                } catch (error: IOException) {
                    throw BackupException(BackupFailure.INVALID_FORMAT, error)
                } catch (error: IllegalArgumentException) {
                    throw BackupException(BackupFailure.INVALID_FORMAT, error)
                }

                val inserted = parkingRepository.replaceAll(restoredSpots)
                settingsRepository.restoreFromBackup(restoredSettings)
                timerScheduler.sync(
                    parkingRepository.latestSpot(),
                    settingsRepository.current(),
                )
                BackupSummary(
                    spots = inserted,
                    photos = restoredSpots.count { it.photoSource != null },
                    bytes = encrypted.length(),
                )
            } finally {
                password.fill('\u0000')
                work.deleteRecursively()
            }
        }

    fun suggestedName(): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        return "parked-backup-$stamp.parked"
    }

    private fun writeZip(zip: File, spots: List<ParkingSpot>, settings: AppSettings): Int {
        var photoCount = 0
        val photoEntries = spots.mapIndexed { index, spot ->
            val path = spot.photoPath
            if (path == null) {
                null
            } else {
                val file = File(path)
                if (file.isFile && file.length() in 1..MAX_PHOTO_BYTES) {
                    "photos/p$index.jpg"
                } else {
                    null
                }
            }
        }

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { output ->
            val properties = Properties().apply {
                setProperty(KEY_FORMAT, FORMAT_VERSION.toString())
                setProperty(KEY_CREATED_AT, System.currentTimeMillis().toString())
                setProperty(KEY_COUNT, spots.size.toString())
                setProperty(KEY_MONITORING, settings.monitoringEnabled.toString())
                setProperty(KEY_DEVICE_ADDRESS, settings.deviceAddress.orEmpty())
                setProperty(KEY_DEVICE_NAME, settings.deviceName.orEmpty())
                setProperty(KEY_SAVE_DELAY, settings.saveDelaySeconds.toString())
                setProperty(KEY_GPS_TIMEOUT, settings.gpsTimeoutSeconds.toString())
                setProperty(KEY_MAX_ACCURACY, settings.maxAccuracyMeters.toString())
                setProperty(KEY_ALLOW_FALLBACK, settings.allowFallbackLastKnown.toString())
                setProperty(KEY_FALLBACK_AGE, settings.fallbackMaxAgeHours.toString())
                setProperty(KEY_TIMER_ENABLED, settings.parkingTimerEnabled.toString())
                setProperty(KEY_TIMER_MINUTES, settings.parkingTimerMinutes.toString())
            }
            output.putNextEntry(ZipEntry(MANIFEST))
            properties.store(output, null)
            output.closeEntry()

            output.putNextEntry(ZipEntry(SPOTS))
            val data = DataOutputStream(output)
            data.writeInt(SPOTS_VERSION)
            data.writeInt(spots.size)
            spots.forEachIndexed { index, spot ->
                data.writeDouble(spot.latitude)
                data.writeDouble(spot.longitude)
                data.writeBoolean(spot.accuracyMeters != null)
                spot.accuracyMeters?.let(data::writeFloat)
                data.writeLong(spot.savedAtEpochMs)
                data.writeLong(spot.fixTimeEpochMs)
                data.writeUTF(spot.note.take(500))
                data.writeUTF(spot.source)
                data.writeUTF(photoEntries[index].orEmpty())
                data.writeBoolean(spot.isFallback)
                data.writeBoolean(spot.isMock)
                data.writeUTF(spot.provider.orEmpty())
                data.writeUTF(spot.deviceAddress.orEmpty())
                data.writeUTF(spot.deviceName.orEmpty())
            }
            data.flush()
            output.closeEntry()

            spots.forEachIndexed { index, spot ->
                val entryName = photoEntries[index] ?: return@forEachIndexed
                val source = File(spot.photoPath!!)
                output.putNextEntry(ZipEntry(entryName))
                source.inputStream().use { input -> input.copyTo(output) }
                output.closeEntry()
                photoCount += 1
            }
        }
        return photoCount
    }

    private fun readSettings(properties: Properties): AppSettings {
        val format = properties.getProperty(KEY_FORMAT)?.toIntOrNull()
            ?: throw BackupException(BackupFailure.INVALID_FORMAT)
        if (format > FORMAT_VERSION) throw BackupException(BackupFailure.UNSUPPORTED_VERSION)
        if (format != FORMAT_VERSION) throw BackupException(BackupFailure.INVALID_FORMAT)

        fun int(key: String, default: Int, range: IntRange): Int =
            properties.getProperty(key)?.toIntOrNull()?.coerceIn(range) ?: default

        val address = properties.getProperty(KEY_DEVICE_ADDRESS)?.takeIf { it.isNotBlank() }
        return AppSettings(
            monitoringEnabled = properties.getProperty(KEY_MONITORING)?.toBooleanStrictOrNull()
                ?: false,
            deviceAddress = address,
            deviceName = properties.getProperty(KEY_DEVICE_NAME)
                ?.takeIf { it.isNotBlank() } ?: address,
            saveDelaySeconds = int(
                KEY_SAVE_DELAY,
                AppSettings.DEFAULT_SAVE_DELAY_SECONDS,
                AppSettings.SAVE_DELAY_RANGE,
            ),
            gpsTimeoutSeconds = int(
                KEY_GPS_TIMEOUT,
                AppSettings.DEFAULT_GPS_TIMEOUT_SECONDS,
                AppSettings.GPS_TIMEOUT_RANGE,
            ),
            maxAccuracyMeters = int(
                KEY_MAX_ACCURACY,
                AppSettings.DEFAULT_MAX_ACCURACY_METERS,
                AppSettings.MAX_ACCURACY_RANGE,
            ),
            allowFallbackLastKnown = properties.getProperty(KEY_ALLOW_FALLBACK)
                ?.toBooleanStrictOrNull() ?: true,
            fallbackMaxAgeHours = int(
                KEY_FALLBACK_AGE,
                AppSettings.DEFAULT_FALLBACK_MAX_AGE_HOURS,
                AppSettings.FALLBACK_MAX_AGE_RANGE,
            ),
            parkingTimerEnabled = properties.getProperty(KEY_TIMER_ENABLED)
                ?.toBooleanStrictOrNull() ?: false,
            parkingTimerMinutes = int(
                KEY_TIMER_MINUTES,
                AppSettings.DEFAULT_PARKING_TIMER_MINUTES,
                AppSettings.PARKING_TIMER_RANGE,
            ),
        )
    }

    private fun readSpots(file: File, entries: Map<String, File>): List<RestoredParkingSpot> {
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { data ->
            if (data.readInt() != SPOTS_VERSION) {
                throw BackupException(BackupFailure.INVALID_FORMAT)
            }
            val count = data.readInt()
            if (count !in 0..MAX_SPOTS) throw BackupException(BackupFailure.INVALID_FORMAT)
            val spots = ArrayList<RestoredParkingSpot>(count)
            repeat(count) {
                val latitude = data.readDouble()
                val longitude = data.readDouble()
                val accuracy = if (data.readBoolean()) data.readFloat() else null
                val savedAt = data.readLong()
                val fixTime = data.readLong()
                val note = data.readUTF().take(500)
                val source = data.readUTF().takeIf { it in VALID_SOURCES } ?: SpotSource.MANUAL
                val photoName = data.readUTF()
                val isFallback = data.readBoolean()
                val isMock = data.readBoolean()
                val provider = data.readUTF().takeIf { it.isNotBlank() }
                val deviceAddress = data.readUTF().takeIf { it.isNotBlank() }
                val deviceName = data.readUTF().takeIf { it.isNotBlank() }

                if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
                    !longitude.isFinite() || longitude !in -180.0..180.0 ||
                    savedAt <= 0L || (accuracy != null && (!accuracy.isFinite() || accuracy < 0f))
                ) {
                    throw BackupException(BackupFailure.INVALID_FORMAT)
                }
                val photo = if (photoName.isBlank()) {
                    null
                } else {
                    entries[photoName] ?: throw BackupException(BackupFailure.INVALID_FORMAT)
                }
                spots += RestoredParkingSpot(
                    spot = ParkingSpot(
                        latitude = latitude,
                        longitude = longitude,
                        accuracyMeters = accuracy,
                        savedAtEpochMs = savedAt,
                        fixTimeEpochMs = if (fixTime > 0L) fixTime else savedAt,
                        note = note,
                        photoPath = null,
                        source = source,
                        isFallback = isFallback,
                        isMock = isMock,
                        provider = provider,
                        deviceAddress = deviceAddress,
                        deviceName = deviceName,
                    ),
                    photoSource = photo,
                )
            }
            if (data.available() > 0) {
                // Trailing bytes are harmless but usually indicate a damaged file.
                throw BackupException(BackupFailure.INVALID_FORMAT)
            }
            return spots
        }
    }

    private fun unzip(zip: File, directory: File): Map<String, File> {
        val result = mutableMapOf<String, File>()
        var total = 0L
        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { input ->
            var count = 0
            while (true) {
                val entry = input.nextEntry ?: break
                count += 1
                if (count > MAX_ENTRIES) throw BackupException(BackupFailure.INVALID_FORMAT)
                val name = entry.name
                if (entry.isDirectory) {
                    input.closeEntry()
                    continue
                }
                val allowed = name == MANIFEST || name == SPOTS ||
                    (name.startsWith("photos/") && name.substringAfter("photos/").let {
                        it.isNotBlank() && '/' !in it && '\\' !in it && it != "." && it != ".."
                    })
                if (!allowed) throw BackupException(BackupFailure.INVALID_FORMAT)

                val target = File(directory, name)
                if (!target.canonicalPath.startsWith(directory.canonicalPath + File.separator)) {
                    throw BackupException(BackupFailure.INVALID_FORMAT)
                }
                target.parentFile?.mkdirs()
                val limit = if (name.startsWith("photos/")) MAX_PHOTO_BYTES else MAX_METADATA_BYTES
                FileOutputStream(target).use { output ->
                    total += copyLimited(input, output, limit)
                }
                if (total > MAX_UNCOMPRESSED_BYTES) {
                    throw BackupException(BackupFailure.TOO_LARGE)
                }
                result[name] = target
                input.closeEntry()
            }
        }
        return result
    }

    private fun copyLimited(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        limit: Long,
    ): Long {
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw BackupException(BackupFailure.TOO_LARGE)
            output.write(buffer, 0, read)
        }
        return total
    }

    private fun createWorkDir(): File =
        File(context.cacheDir, "parked-backup").apply {
            deleteRecursively()
            if (!mkdirs()) throw BackupException(BackupFailure.IO)
        }

    private companion object {
        const val FORMAT_VERSION = 1
        const val SPOTS_VERSION = 1
        const val MANIFEST = "manifest.properties"
        const val SPOTS = "spots.bin"
        const val MAX_SPOTS = 100_000
        const val MAX_ENTRIES = 100_010
        const val MAX_PHOTO_BYTES = 25L * 1024L * 1024L
        const val MAX_METADATA_BYTES = 20L * 1024L * 1024L
        const val MAX_ARCHIVE_BYTES = 1024L * 1024L * 1024L
        const val MAX_UNCOMPRESSED_BYTES = 2L * 1024L * 1024L * 1024L
        val VALID_SOURCES = setOf(SpotSource.AUTO, SpotSource.MANUAL, SpotSource.FALLBACK)

        const val KEY_FORMAT = "format"
        const val KEY_CREATED_AT = "createdAt"
        const val KEY_COUNT = "count"
        const val KEY_MONITORING = "monitoringEnabled"
        const val KEY_DEVICE_ADDRESS = "deviceAddress"
        const val KEY_DEVICE_NAME = "deviceName"
        const val KEY_SAVE_DELAY = "saveDelaySeconds"
        const val KEY_GPS_TIMEOUT = "gpsTimeoutSeconds"
        const val KEY_MAX_ACCURACY = "maxAccuracyMeters"
        const val KEY_ALLOW_FALLBACK = "allowFallbackLastKnown"
        const val KEY_FALLBACK_AGE = "fallbackMaxAgeHours"
        const val KEY_TIMER_ENABLED = "parkingTimerEnabled"
        const val KEY_TIMER_MINUTES = "parkingTimerMinutes"
    }
}
