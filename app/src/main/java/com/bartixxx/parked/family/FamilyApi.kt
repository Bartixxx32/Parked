package com.bartixxx.parked.family

import com.bartixxx.parked.BuildConfig
import com.bartixxx.parked.data.db.ParkingSpot
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class FamilySession(
    val groupId: String,
    val deviceId: String,
    val deviceToken: String,
    val adminToken: String?,
    val inviteCode: String?,
)

data class FamilySnapshot(
    val groupId: String?,
    val members: List<FamilyMember>,
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

    suspend fun createGroup(carLabel: String): FamilySession {
        val response = request(
            method = "POST",
            path = "/v1/groups",
            body = JSONObject().put("displayName", carLabel),
        )
        return sessionFrom(response, requireAdminToken = true)
    }

    suspend fun joinGroup(inviteCode: String, carLabel: String): FamilySession {
        val code = InviteCode.normalize(inviteCode)
            ?: throw FamilyApiException(FamilyError.INVALID_CODE)
        val response = request(
            method = "POST",
            path = "/v1/groups/join",
            body = JSONObject()
                .put("inviteCode", code)
                .put("displayName", carLabel),
        )
        return sessionFrom(response, requireAdminToken = false)
    }

    suspend fun fetchSpots(deviceToken: String): FamilySnapshot {
        val response = request(
            method = "GET",
            path = "/v1/spots",
            deviceToken = deviceToken,
        )
        val members = mutableListOf<FamilyMember>()
        val array = response?.optJSONArray("members") ?: JSONArray()
        for (index in 0 until array.length()) {
            val member = array.optJSONObject(index) ?: continue
            val deviceId = member.optString("deviceId").takeIf { it.isNotBlank() } ?: continue
            val carLabel = member.optString("displayName").ifBlank { deviceId }
            val lastSeen = epoch(member.optString("lastSeenAt"))
            val spotObject = member.optJSONObject("spot")
            members += FamilyMember(
                deviceId = deviceId,
                carLabel = carLabel,
                lastSeenAtEpochMs = lastSeen,
                spot = spotObject?.let { spot ->
                    val latitude = spot.optDouble("latitude")
                    val longitude = spot.optDouble("longitude")
                    if (latitude.isNaN() || longitude.isNaN()) {
                        null
                    } else {
                        FamilySpot(
                            deviceId = deviceId,
                            carLabel = carLabel,
                            latitude = latitude,
                            longitude = longitude,
                            accuracyMeters = spot.accuracyOrNull(),
                            savedAtEpochMs = epoch(spot.optString("savedAt"))
                                ?: System.currentTimeMillis(),
                            lastSeenAtEpochMs = lastSeen,
                        )
                    }
                },
            )
        }
        return FamilySnapshot(
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

    suspend fun rotateInviteCode(deviceToken: String, adminToken: String): String {
        val response = request(
            method = "POST",
            path = "/v1/groups/rotate",
            deviceToken = deviceToken,
            adminToken = adminToken,
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
        )
    }

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

    private fun parseError(status: Int, text: String): FamilyError {
        val code = runCatching { JSONObject(text).optString("error") }.getOrNull()
        return when {
            status == 429 || code == "rate_limited" -> FamilyError.RATE_LIMITED
            code == "group_full" -> FamilyError.GROUP_FULL
            code == "invalid_code" -> FamilyError.INVALID_CODE
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

    private fun JSONObject.accuracyOrNull(): Double? {
        if (isNull("accuracyM")) return null
        val value = optDouble("accuracyM")
        return value.takeIf { !it.isNaN() }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
    }
}
