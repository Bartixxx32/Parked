package com.bartixxx.parked.family

import com.bartixxx.parked.BuildConfig
import com.bartixxx.parked.data.db.ParkingSpot
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Values sent to the server when creating or rotating an encrypted group. */
data class FamilyCryptoRequest(
    val keySalt: String,
    val wrappedKey: String,
    val keyIv: String,
    val joinAuthHash: String,
)

/** Public key material returned by the server after create or join. */
data class FamilyCryptoInfo(
    val mode: FamilyCryptoMode,
    val keySalt: String? = null,
    val wrappedKey: String? = null,
    val keyIv: String? = null,
    val keyVersion: Int = FamilyCrypto.KEY_VERSION,
)

data class FamilySession(
    val groupId: String,
    val deviceId: String,
    val deviceToken: String,
    val adminToken: String?,
    val inviteCode: String?,
    val crypto: FamilyCryptoInfo,
)

data class RemoteFamilySpot(
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Double?,
    val savedAtEpochMs: Long?,
    val payload: String?,
    val keyVersion: Int?,
)

data class RemoteFamilyMember(
    val deviceId: String,
    val carLabel: String,
    val lastSeenAtEpochMs: Long?,
    val spot: RemoteFamilySpot?,
)

data class RemoteFamilySnapshot(
    val groupId: String?,
    val members: List<RemoteFamilyMember>,
)

data class RemoteFamilyPhoto(
    val id: String,
    val senderDeviceId: String,
    val iv: String,
    val keyVersion: Int,
    val sizeBytes: Int,
    val savedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
)

class FamilyApiException(
    val error: FamilyError,
    val status: Int? = null,
    cause: Throwable? = null,
) : IOException(cause)

/**
 * Minimal HTTPS client. The app intentionally does not pull in a networking
 * library: HttpURLConnection and org.json are part of the platform, which keeps
 * the R8-shrunk APK small.
 */
class FamilyApi(serverUrl: String) {

    private val baseUrl = serverUrl.trim().trimEnd('/')

    suspend fun createGroup(carLabel: String, crypto: FamilyCryptoRequest?): FamilySession {
        // For encrypted groups the real car label never leaves the phone, not
        // even during creation. The server stores only this placeholder.
        val body = JSONObject().put(
            "displayName",
            if (crypto == null) carLabel else ENCRYPTED_PLACEHOLDER,
        )
        if (crypto != null) body.put("crypto", crypto.toJson())
        val response = request(
            method = "POST",
            path = "/v1/groups",
            body = body,
        )
        return sessionFrom(response, requireAdminToken = true)
    }

    suspend fun joinGroup(
        inviteCode: String,
        carLabel: String,
        joinAuthKey: String?,
    ): FamilySession {
        val code = InviteCode.normalize(inviteCode)
            ?: throw FamilyApiException(FamilyError.INVALID_CODE)
        // A secret in the invite means the group is encrypted. Send only the
        // placeholder so the server never receives the real car label.
        val body = JSONObject()
            .put("inviteCode", code)
            .put(
                "displayName",
                if (joinAuthKey == null) carLabel else ENCRYPTED_PLACEHOLDER,
            )
        if (joinAuthKey != null) body.put("joinAuthKey", joinAuthKey)
        val response = request(
            method = "POST",
            path = "/v1/groups/join",
            body = body,
        )
        return sessionFrom(response, requireAdminToken = false)
    }

    suspend fun fetchSpots(deviceToken: String): RemoteFamilySnapshot {
        val response = request(
            method = "GET",
            path = "/v1/spots",
            deviceToken = deviceToken,
        )
        val members = mutableListOf<RemoteFamilyMember>()
        val array = response?.optJSONArray("members") ?: JSONArray()
        for (index in 0 until array.length()) {
            val member = array.optJSONObject(index) ?: continue
            val deviceId = member.optString("deviceId").takeIf { it.isNotBlank() } ?: continue
            val spotObject = member.optJSONObject("spot")
            members += RemoteFamilyMember(
                deviceId = deviceId,
                carLabel = member.optString("displayName"),
                lastSeenAtEpochMs = epoch(member.optString("lastSeenAt")),
                spot = spotObject?.let(::parseRemoteSpot),
            )
        }
        return RemoteFamilySnapshot(
            groupId = response?.optString("groupId")?.takeIf { it.isNotBlank() },
            members = members,
        )
    }

    suspend fun uploadSpot(deviceToken: String, spot: ParkingSpot) {
        request(
            method = "PUT",
            path = "/v1/spot",
            deviceToken = deviceToken,
            body = JSONObject()
                .put("latitude", spot.latitude)
                .put("longitude", spot.longitude)
                .put("accuracyM", spot.accuracyMeters ?: JSONObject.NULL)
                .put("savedAt", Instant.ofEpochMilli(spot.savedAtEpochMs).toString()),
        )
    }

    suspend fun uploadEncryptedSpot(
        deviceToken: String,
        payload: String,
        keyVersion: Int,
        savedAtEpochMs: Long,
    ) {
        request(
            method = "PUT",
            path = "/v1/spot",
            deviceToken = deviceToken,
            body = JSONObject()
                .put("payload", payload)
                .put("keyVersion", keyVersion)
                .put("savedAt", Instant.ofEpochMilli(savedAtEpochMs).toString()),
        )
    }

    suspend fun deleteSpot(deviceToken: String) {
        request(method = "DELETE", path = "/v1/spot", deviceToken = deviceToken)
    }

    suspend fun listPhotos(deviceToken: String): List<RemoteFamilyPhoto> {
        val response = request(
            method = "GET",
            path = "/v1/photos",
            deviceToken = deviceToken,
        )
        val array = response?.optJSONArray("photos") ?: JSONArray()
        val photos = mutableListOf<RemoteFamilyPhoto>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
            val sender = item.optString("senderDeviceId").takeIf { it.isNotBlank() }
                ?: continue
            val iv = item.optString("iv").takeIf { it.isNotBlank() } ?: continue
            val keyVersion = item.optInt("keyVersion", 0).takeIf { it > 0 } ?: continue
            photos += RemoteFamilyPhoto(
                id = id,
                senderDeviceId = sender,
                iv = iv,
                keyVersion = keyVersion,
                sizeBytes = item.optInt("sizeBytes", 0),
                savedAtEpochMs = epoch(item.optString("createdAt")) ?: 0L,
                expiresAtEpochMs = epoch(item.optString("expiresAt")) ?: 0L,
            )
        }
        return photos
    }

    suspend fun downloadPhoto(deviceToken: String, photoId: String): ByteArray =
        requestBytes(
            method = "GET",
            path = "/v1/photos/$photoId",
            deviceToken = deviceToken,
        )

    suspend fun uploadPhoto(
        deviceToken: String,
        photoId: String,
        iv: String,
        keyVersion: Int,
        ciphertext: ByteArray,
    ) {
        requestBytes(
            method = "POST",
            path = "/v1/photos",
            deviceToken = deviceToken,
            body = ciphertext,
            headers = mapOf(
                "X-Photo-Id" to photoId,
                "X-Photo-Iv" to iv,
                "X-Key-Version" to keyVersion.toString(),
            ),
        )
    }

    suspend fun ackPhoto(deviceToken: String, photoId: String) {
        request(
            method = "POST",
            path = "/v1/photos/$photoId/ack",
            deviceToken = deviceToken,
        )
    }

    suspend fun updateCarLabel(deviceToken: String, carLabel: String) {
        request(
            method = "PATCH",
            path = "/v1/device",
            deviceToken = deviceToken,
            body = JSONObject().put("displayName", carLabel),
        )
    }

    suspend fun leave(deviceToken: String) {
        request(method = "DELETE", path = "/v1/device", deviceToken = deviceToken)
    }

    suspend fun rotateInviteCode(
        deviceToken: String,
        adminToken: String,
        crypto: FamilyCryptoRequest?,
    ): String {
        val response = request(
            method = "POST",
            path = "/v1/groups/rotate",
            deviceToken = deviceToken,
            adminToken = adminToken,
            body = crypto?.let { JSONObject().put("crypto", it.toJson()) },
        )
        return response?.optString("inviteCode")?.takeIf { it.isNotBlank() }
            ?: throw FamilyApiException(FamilyError.SERVER)
    }

    private fun sessionFrom(response: JSONObject?, requireAdminToken: Boolean): FamilySession {
        val groupId = response?.optString("groupId")?.takeIf { it.isNotBlank() }
        val deviceId = response?.optString("deviceId")?.takeIf { it.isNotBlank() }
        val deviceToken = response?.optString("deviceToken")?.takeIf { it.isNotBlank() }
        val adminToken = response?.optString("adminToken")?.takeIf { it.isNotBlank() }
        if (groupId == null || deviceId == null || deviceToken == null) {
            throw FamilyApiException(FamilyError.SERVER)
        }
        if (requireAdminToken && adminToken == null) {
            throw FamilyApiException(FamilyError.SERVER)
        }
        return FamilySession(
            groupId = groupId,
            deviceId = deviceId,
            deviceToken = deviceToken,
            adminToken = adminToken,
            inviteCode = response?.optString("inviteCode")?.takeIf { it.isNotBlank() },
            crypto = parseCryptoInfo(response?.optJSONObject("crypto")),
        )
    }

    private fun parseCryptoInfo(value: JSONObject?): FamilyCryptoInfo {
        if (value == null) return FamilyCryptoInfo(FamilyCryptoMode.PLAINTEXT)
        val mode = FamilyCryptoMode.fromWire(value.optString("mode"))
        if (mode == FamilyCryptoMode.PLAINTEXT) return FamilyCryptoInfo(mode)
        return FamilyCryptoInfo(
            mode = mode,
            keySalt = value.optString("keySalt").takeIf { it.isNotBlank() },
            wrappedKey = value.optString("wrappedKey").takeIf { it.isNotBlank() },
            keyIv = value.optString("keyIv").takeIf { it.isNotBlank() },
            keyVersion = value.optInt("keyVersion", FamilyCrypto.KEY_VERSION),
        )
    }

    private fun parseRemoteSpot(value: JSONObject): RemoteFamilySpot? {
        val payload = if (value.has("payload") && !value.isNull("payload")) {
            value.optString("payload").takeIf { it.isNotBlank() }
        } else {
            null
        }
        val latitude = if (value.has("latitude") && !value.isNull("latitude")) {
            value.optDouble("latitude").takeUnless { it.isNaN() }
        } else {
            null
        }
        val longitude = if (value.has("longitude") && !value.isNull("longitude")) {
            value.optDouble("longitude").takeUnless { it.isNaN() }
        } else {
            null
        }
        if (payload == null && (latitude == null || longitude == null)) return null
        return RemoteFamilySpot(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = if (value.has("accuracyM") && !value.isNull("accuracyM")) {
                value.optDouble("accuracyM").takeUnless { it.isNaN() }
            } else {
                null
            },
            savedAtEpochMs = epoch(value.optString("savedAt")),
            payload = payload,
            keyVersion = if (value.has("keyVersion") && !value.isNull("keyVersion")) {
                value.optInt("keyVersion").takeIf { it > 0 }
            } else {
                null
            },
        )
    }

    private fun FamilyCryptoRequest.toJson(): JSONObject = JSONObject()
        .put("keySalt", keySalt)
        .put("wrappedKey", wrappedKey)
        .put("keyIv", keyIv)
        .put("joinAuthHash", joinAuthHash)

    private suspend fun request(
        method: String,
        path: String,
        deviceToken: String? = null,
        adminToken: String? = null,
        body: JSONObject? = null,
    ): JSONObject? = withContext(Dispatchers.IO) {
        val connection = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Parked/${BuildConfig.VERSION_NAME} (Android)")
            if (deviceToken != null) {
                setRequestProperty("Authorization", "Bearer $deviceToken")
            }
            if (adminToken != null) {
                setRequestProperty("X-Admin-Token", adminToken)
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }

        try {
            if (body != null) {
                connection.outputStream.use { output ->
                    output.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) {
                if (status == HttpURLConnection.HTTP_NO_CONTENT) null else connection.inputStream
            } else {
                connection.errorStream
            }
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (status !in 200..299) {
                throw FamilyApiException(parseError(status, text), status)
            }
            if (text.isBlank()) null else JSONObject(text)
        } catch (error: FamilyApiException) {
            throw error
        } catch (error: IOException) {
            throw FamilyApiException(FamilyError.NETWORK, cause = error)
        } catch (error: Exception) {
            throw FamilyApiException(FamilyError.UNKNOWN, cause = error)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun requestBytes(
        method: String,
        path: String,
        deviceToken: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
    ): ByteArray = withContext(Dispatchers.IO) {
        val connection = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("User-Agent", "Parked/${BuildConfig.VERSION_NAME} (Android)")
            setRequestProperty("Authorization", "Bearer $deviceToken")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (body != null) {
                doOutput = true
                setFixedLengthStreamingMode(body.size)
                setRequestProperty("Content-Type", "application/octet-stream")
            }
        }

        try {
            if (body != null) {
                connection.outputStream.use { output -> output.write(body) }
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val text = connection.errorStream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
                throw FamilyApiException(parseError(status, text), status)
            }
            val declared = connection.contentLengthLong
            if (declared > MAX_BINARY_BYTES) {
                throw FamilyApiException(FamilyError.PHOTO_TOO_LARGE, status)
            }
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BINARY_BYTES) {
                        throw FamilyApiException(FamilyError.PHOTO_TOO_LARGE, status)
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        } catch (error: FamilyApiException) {
            throw error
        } catch (error: IOException) {
            throw FamilyApiException(FamilyError.NETWORK, cause = error)
        } catch (error: Exception) {
            throw FamilyApiException(FamilyError.UNKNOWN, cause = error)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseError(status: Int, text: String): FamilyError {
        val code = runCatching { JSONObject(text).optString("error") }.getOrNull()
        return when {
            status == 429 || code == "rate_limited" -> FamilyError.RATE_LIMITED
            code == "group_full" -> FamilyError.GROUP_FULL
            code == "invalid_code" -> FamilyError.INVALID_CODE
            code == "invite_link_required" || code == "invalid_invite" ->
                FamilyError.INVALID_INVITE
            code == "invalid_crypto" || code == "crypto_required" ||
                code == "key_version_mismatch" -> FamilyError.CRYPTO
            code == "photo_limit" -> FamilyError.PHOTO_LIMIT
            code == "photo_too_large" -> FamilyError.PHOTO_TOO_LARGE
            code == "no_recipients" -> FamilyError.NO_RECIPIENTS
            code == "invalid_name" -> FamilyError.INVALID_NAME
            code == "unauthorized" || code == "forbidden" -> FamilyError.UNAUTHORIZED
            code == "not_found" -> FamilyError.NOT_CONNECTED
            status in 500..599 -> FamilyError.SERVER
            else -> FamilyError.UNKNOWN
        }
    }

    private fun epoch(value: String?): Long? =
        value?.takeIf { it.isNotBlank() }?.let {
            runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
        }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 30_000
        const val ENCRYPTED_PLACEHOLDER = "Encrypted"
        const val MAX_BINARY_BYTES = 2_100_000
    }
}
