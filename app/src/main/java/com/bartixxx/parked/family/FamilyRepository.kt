package com.bartixxx.parked.family

import com.bartixxx.parked.data.ParkingRepository
import com.bartixxx.parked.data.db.ParkingSpot
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates family mode between the settings store, the API and the local
 * parking database. Only the latest local spot is uploaded; notes and photos
 * never leave the phone.
 */
class FamilyRepository(
    private val store: FamilyStore,
    private val parkingRepository: ParkingRepository,
    private val scope: CoroutineScope,
) {

    private data class Runtime(
        val members: List<FamilyMember> = emptyList(),
        val loading: Boolean = false,
        val syncing: Boolean = false,
        val pendingUpload: Boolean = false,
        val error: FamilyError? = null,
        val lastSyncEpochMs: Long? = null,
    )

    private val runtime = MutableStateFlow(Runtime())
    private val mutex = Mutex()

    val state: StateFlow<FamilyState> = combine(store.credentials, runtime) { credentials, value ->
        FamilyState(
            credentials = credentials,
            members = value.members,
            loading = value.loading,
            syncing = value.syncing,
            pendingUpload = value.pendingUpload,
            error = value.error,
            lastSyncEpochMs = value.lastSyncEpochMs,
        )
    }.stateIn(scope, SharingStarted.Eagerly, FamilyState())

    suspend fun createGroup(carLabel: String): FamilyError? = mutex.withLock {
        val current = store.current()
        val label = cleanCarLabel(carLabel) ?: return@withLock FamilyError.INVALID_NAME
        runtime.update { it.copy(loading = true, error = null) }
        try {
            val api = apiFor(current)
            val session = api.createGroup(label)
            store.saveSession(
                serverUrl = current.serverUrl,
                groupId = session.groupId,
                deviceId = session.deviceId,
                deviceToken = session.deviceToken,
                adminToken = session.adminToken,
                inviteCode = session.inviteCode,
                carLabel = label,
            )
            val updated = store.current()
            refreshLocked(api, updated)
            syncLatestLocked(api, updated)
            null
        } catch (error: Exception) {
            fail(error)
        } finally {
            runtime.update { it.copy(loading = false) }
        }
    }

    suspend fun joinGroup(inviteCode: String, carLabel: String): FamilyError? = mutex.withLock {
        val current = store.current()
        val label = cleanCarLabel(carLabel) ?: return@withLock FamilyError.INVALID_NAME
        val code = InviteCode.normalize(inviteCode) ?: return@withLock FamilyError.INVALID_CODE
        runtime.update { it.copy(loading = true, error = null) }
        try {
            val api = apiFor(current)
            val session = api.joinGroup(code, label)
            store.saveSession(
                serverUrl = current.serverUrl,
                groupId = session.groupId,
                deviceId = session.deviceId,
                deviceToken = session.deviceToken,
                adminToken = null,
                inviteCode = session.inviteCode ?: code,
                carLabel = label,
            )
            val updated = store.current()
            refreshLocked(api, updated)
            syncLatestLocked(api, updated)
            null
        } catch (error: Exception) {
            fail(error)
        } finally {
            runtime.update { it.copy(loading = false) }
        }
    }

    suspend fun refresh(): FamilyError? = mutex.withLock {
        val current = store.current()
        if (!current.joined) return@withLock null
        try {
            refreshLocked(apiFor(current), current)
        } catch (error: Exception) {
            fail(error)
        }
    }

    suspend fun syncSpot(spot: ParkingSpot): FamilyError? = mutex.withLock {
        val current = store.current()
        if (!current.enabled || !current.joined) return@withLock null
        try {
            syncSpotLocked(apiFor(current), current, spot)
        } catch (error: Exception) {
            fail(error)
        }
    }

    suspend fun syncLatest(): FamilyError? = mutex.withLock {
        val current = store.current()
        if (!current.enabled || !current.joined) return@withLock null
        try {
            syncLatestLocked(apiFor(current), current)
        } catch (error: Exception) {
            fail(error)
        }
    }

    suspend fun setEnabled(enabled: Boolean): FamilyError? {
        val current = store.current()
        if (enabled && !current.joined) {
            runtime.update { it.copy(error = FamilyError.NOT_CONNECTED) }
            return FamilyError.NOT_CONNECTED
        }
        store.setEnabled(enabled)
        if (!enabled) {
            runtime.update { it.copy(error = null) }
            return null
        }
        return refresh().let { error ->
            if (error != null) error else syncLatest()
        }
    }

    suspend fun updateCarLabel(carLabel: String): FamilyError? {
        val current = store.current()
        val label = cleanCarLabel(carLabel) ?: return FamilyError.INVALID_NAME
        if (!current.joined) return FamilyError.NOT_CONNECTED
        return try {
            apiFor(current).updateCarLabel(current.deviceToken!!, label)
            store.setCarLabel(label)
            runtime.update { value ->
                value.copy(
                    members = value.members.map { member ->
                        if (member.deviceId == current.deviceId) {
                            member.copy(carLabel = label)
                        } else {
                            member
                        }
                    },
                    error = null,
                )
            }
            null
        } catch (error: Exception) {
            fail(error)
        }
    }

    suspend fun setServerUrl(url: String): FamilyError? {
        val current = store.current()
        if (current.joined) return FamilyError.INVALID_URL
        val normalized = normalizeServerUrl(url) ?: return FamilyError.INVALID_URL
        store.setServerUrl(normalized)
        runtime.update { it.copy(error = null) }
        return null
    }

    suspend fun rotateInviteCode(): FamilyError? {
        val current = store.current()
        if (!current.joined) return FamilyError.NOT_CONNECTED
        val adminToken = current.adminToken ?: return FamilyError.UNAUTHORIZED
        return try {
            val code = apiFor(current)
                .rotateInviteCode(current.deviceToken!!, adminToken)
            store.setInviteCode(code)
            runtime.update { it.copy(error = null) }
            null
        } catch (error: Exception) {
            fail(error)
        }
    }

    suspend fun leaveGroup(): FamilyError? {
        val current = store.current()
        if (!current.joined) {
            clearLocalSession()
            return null
        }
        return try {
            apiFor(current).leave(current.deviceToken!!)
            clearLocalSession()
            null
        } catch (error: Exception) {
            fail(error)
        }
    }

    fun clearError() {
        runtime.update { it.copy(error = null) }
    }

    private suspend fun refreshLocked(api: FamilyApi, current: FamilyCredentials): FamilyError? {
        val token = current.deviceToken ?: return FamilyError.NOT_CONNECTED
        return try {
            val snapshot = api.fetchSpots(token)
            runtime.update {
                it.copy(
                    members = snapshot.members,
                    lastSyncEpochMs = System.currentTimeMillis(),
                    error = null,
                )
            }
            if (runtime.value.pendingUpload) {
                syncLatestLocked(api, current)
            }
            null
        } catch (error: Exception) {
            fail(error)
        }
    }

    private suspend fun syncLatestLocked(api: FamilyApi, current: FamilyCredentials): FamilyError? {
        val spot = parkingRepository.latestSpot() ?: return null
        return syncSpotLocked(api, current, spot)
    }

    private suspend fun syncSpotLocked(
        api: FamilyApi,
        current: FamilyCredentials,
        spot: ParkingSpot,
    ): FamilyError? {
        val token = current.deviceToken ?: return FamilyError.NOT_CONNECTED
        runtime.update { it.copy(syncing = true) }
        return try {
            api.uploadSpot(token, spot)
            runtime.update {
                it.copy(
                    syncing = false,
                    pendingUpload = false,
                    lastSyncEpochMs = System.currentTimeMillis(),
                    error = null,
                )
            }
            null
        } catch (error: Exception) {
            val mapped = mapError(error)
            runtime.update {
                it.copy(
                    syncing = false,
                    pendingUpload = true,
                    error = mapped,
                )
            }
            mapped
        }
    }

    private fun fail(error: Exception): FamilyError {
        if (error is CancellationException) throw error
        val mapped = mapError(error)
        runtime.update { it.copy(error = mapped) }
        return mapped
    }

    private fun mapError(error: Exception): FamilyError =
        (error as? FamilyApiException)?.error ?: FamilyError.UNKNOWN

    private fun apiFor(current: FamilyCredentials): FamilyApi {
        val normalized = normalizeServerUrl(current.serverUrl)
            ?: throw FamilyApiException(FamilyError.INVALID_URL)
        return FamilyApi(normalized)
    }

    private suspend fun clearLocalSession() {
        store.clear()
        runtime.value = Runtime()
    }

    private fun cleanCarLabel(raw: String): String? {
        val label = raw.filterNot { it.isISOControl() }.trim()
        return label.takeIf { it.length in 1..MAX_CAR_LABEL_LENGTH }
    }

    private fun normalizeServerUrl(raw: String): String? {
        val value = raw.trim().trimEnd('/')
        if (!value.startsWith("https://")) return null
        return runCatching {
            val url = URL(value)
            if (url.host.isNullOrBlank()) null else value
        }.getOrNull()
    }

    private companion object {
        const val MAX_CAR_LABEL_LENGTH = 60
    }
}
