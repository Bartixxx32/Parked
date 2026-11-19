package com.bartixxx.parked.family

import android.content.Context
import com.bartixxx.parked.data.ParkingRepository
import com.bartixxx.parked.data.db.FamilyPhotoDao
import com.bartixxx.parked.data.db.FamilyPhotoEntity
import com.bartixxx.parked.data.db.FamilySentPhotoDao
import com.bartixxx.parked.data.db.FamilySentPhotoEntity
import com.bartixxx.parked.data.db.ParkingSpot
import java.io.File
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Coordinates family mode between the settings store, the API and the local
 * parking database. Only the latest local spot is uploaded.
 *
 * Notes stay on the phone unless the user opts in to sharing the latest one;
 * then the note travels inside the encrypted spot payload. Photos leave only
 * when the user explicitly sends one, only as ciphertext encrypted with the
 * group key, and the server deletes the ciphertext as soon as every family
 * phone confirms the download. A photo the sender deletes locally is revoked
 * on the server and removed from family phones on their next refresh, even if
 * the server copy was already deleted after delivery.
 *
 * New groups are end-to-end encrypted: [FamilyCrypto] encrypts the car label,
 * coordinates and photos on this phone, and the server only stores ciphertext.
 */
class FamilyRepository(
    private val context: Context,
    private val store: FamilyStore,
    private val parkingRepository: ParkingRepository,
    private val photoDao: FamilyPhotoDao,
    private val sentPhotoDao: FamilySentPhotoDao,
    private val scope: CoroutineScope,
) {

    private data class Runtime(
        val members: List<FamilyMember> = emptyList(),
        val loading: Boolean = false,
        val syncing: Boolean = false,
        val sendingPhoto: Boolean = false,
        val pendingUpload: Boolean = false,
        val pendingDelete: Boolean = false,
        val photoUploadEnabled: Boolean = true,
        val error: FamilyError? = null,
        val lastSyncEpochMs: Long? = null,
    )

    private val runtime = MutableStateFlow(Runtime())
    private val mutex = Mutex()
    private val keyVault = FamilyKeyVault()

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<FamilyState> = combine(
        store.credentials,
        runtime,
    ) { credentials, value -> credentials to value }
        .flatMapLatest { (credentials, value) ->
            val photos = credentials.groupId?.let(photoDao::observeForGroup)
                ?: flowOf(emptyList())
            val sentPhotos = credentials.groupId
                ?.let(sentPhotoDao::observeActiveForGroup)
                ?: flowOf(emptyList())
            combine(photos, sentPhotos) { storedPhotos, activeSentPhotos ->
                FamilyState(
                    credentials = credentials,
                    members = value.members,
                    loading = value.loading,
                    syncing = value.syncing,
                    sendingPhoto = value.sendingPhoto,
                    pendingUpload = value.pendingUpload,
                    photoUploadEnabled = value.photoUploadEnabled,
                    error = value.error,
                    lastSyncEpochMs = value.lastSyncEpochMs,
                    photos = storedPhotos,
                    sentPhotoSpotIds = activeSentPhotos.map { it.spotId }.toSet(),
                )
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, FamilyState())

    suspend fun createGroup(carLabel: String): FamilyError? = mutex.withLock {
        val current = store.current()
        val label = cleanCarLabel(carLabel) ?: return@withLock FamilyError.INVALID_NAME
        runtime.update { it.copy(loading = true, error = null) }
        try {
            val api = apiFor(current)
            val material = crypto { FamilyCrypto.newGroupKeyMaterial() }
            val wrapped = keyVault.wrap(material.groupKey)
            val request = FamilyCryptoRequest(
                keySalt = material.keySalt,
                wrappedKey = material.wrappedKey,
                keyIv = material.keyIv,
                joinAuthHash = cryptoHash(material.inviteSecret),
            )
            val session = api.createGroup(label, request)
            saveSession(
                current = current,
                session = session,
                label = label,
                inviteSecret = material.inviteSecret.takeIf {
                    session.crypto.mode == FamilyCryptoMode.E2EE
                },
                wrappedGroupKey = wrapped.takeIf {
                    session.crypto.mode == FamilyCryptoMode.E2EE
                },
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

    suspend fun joinGroup(inviteInput: String, carLabel: String): FamilyError? = mutex.withLock {
        val current = store.current()
        val label = cleanCarLabel(carLabel) ?: return@withLock FamilyError.INVALID_NAME
        val invite = FamilyCrypto.parseInvite(inviteInput)
            ?: return@withLock FamilyError.INVALID_CODE
        runtime.update { it.copy(loading = true, error = null) }
        var api: FamilyApi? = null
        var joinedToken: String? = null
        try {
            api = apiFor(current)
            val authKey = invite.secret?.let { crypto { FamilyCrypto.joinAuthKey(it) } }
            val session = api.joinGroup(invite.code, label, authKey)
            if (session.crypto.mode == FamilyCryptoMode.E2EE) {
                joinedToken = session.deviceToken
                val secret = invite.secret
                    ?: throw FamilyApiException(FamilyError.INVALID_INVITE)
                val groupKey = unwrapGroupKey(secret, session.crypto)
                    ?: throw FamilyApiException(FamilyError.INVALID_INVITE)
                val wrapped = keyVault.wrap(groupKey)
                saveSession(
                    current = current,
                    session = session,
                    label = label,
                    inviteSecret = secret,
                    wrappedGroupKey = wrapped,
                )
            } else {
                saveSession(
                    current = current,
                    session = session,
                    label = label,
                    inviteSecret = null,
                    wrappedGroupKey = null,
                )
            }
            joinedToken = null
            val updated = store.current()
            refreshLocked(api, updated)
            syncLatestLocked(api, updated)
            null
        } catch (error: Exception) {
            // The server may have created a device before local key unwrapping
            // failed. Remove it so a failed join does not take a group slot.
            if (joinedToken != null && api != null) {
                runCatching { api.leave(joinedToken) }
            }
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

    /**
     * Encrypts one spot photo and sends it to the family. This is always an
     * explicit user action: uploading every new photo automatically would burn
     * the free Storage egress quota and surprise people who expect photos to
     * stay local.
     */
    suspend fun sendPhoto(spot: ParkingSpot): FamilyError? = mutex.withLock {
        val current = store.current()
        if (!current.joined) return@withLock FamilyError.NOT_CONNECTED
        if (!current.encrypted) return@withLock FamilyError.CRYPTO
        val token = current.deviceToken ?: return@withLock FamilyError.NOT_CONNECTED
        val path = spot.photoPath ?: return@withLock FamilyError.NO_PHOTO
        if (!runtime.value.photoUploadEnabled) {
            return@withLock FamilyError.PHOTO_DISABLED
        }
        // One family copy per spot photo. The History screen also disables the
        // button, but a quick double tap can still queue two calls.
        if (sentPhotoDao.activeCountForSpot(spot.id) > 0) {
            return@withLock FamilyError.PHOTO_ALREADY_SENT
        }

        runtime.update { it.copy(sendingPhoto = true, error = null) }
        try {
            val compressed = withContext(Dispatchers.Default) {
                FamilyPhotoCompressor.compress(File(path))
            } ?: throw FamilyApiException(FamilyError.PHOTO_FAILED)
            val photoId = UUID.randomUUID().toString()
            val groupKey = groupKeyFor(current)
            val encrypted = crypto {
                FamilyCrypto.encryptPhoto(
                    groupKey = groupKey,
                    groupId = current.groupId!!,
                    photoId = photoId,
                    senderDeviceId = current.deviceId!!,
                    keyVersion = current.keyVersion,
                    plaintext = compressed,
                )
            }
            apiFor(current).uploadPhoto(
                deviceToken = token,
                photoId = photoId,
                iv = encrypted.iv,
                keyVersion = current.keyVersion,
                ciphertext = encrypted.ciphertext,
            )
            try {
                val now = System.currentTimeMillis()
                sentPhotoDao.insert(
                    FamilySentPhotoEntity(
                        photoId = photoId,
                        spotId = spot.id,
                        groupId = current.groupId!!,
                        sentAtEpochMs = now,
                    ),
                )
                sentPhotoDao.deleteOlderThan(
                    current.groupId,
                    now - SENT_PHOTO_RETENTION_MS,
                )
            } catch (error: Exception) {
                // Without the local id the sender could never retract this
                // photo. Remove the server copy instead of leaving an
                // untracked ciphertext behind.
                runCatching { apiFor(current).deletePhoto(token, photoId) }
                throw error
            }
            runtime.update { it.copy(error = null) }
            null
        } catch (error: Exception) {
            fail(error)
        } finally {
            runtime.update { it.copy(sendingPhoto = false) }
        }
    }

    /**
     * Opts the latest note in or out of family sharing. Enabling uploads the
     * latest spot again; disabling re-uploads it without the note so the old
     * note does not stay on the server.
     */
    suspend fun setShareNote(enabled: Boolean): FamilyError? = mutex.withLock {
        val before = store.current()
        if (enabled && before.joined && !before.encrypted) {
            // Notes are only ever shared inside the encrypted spot payload.
            runtime.update { it.copy(error = null) }
            return@withLock null
        }
        store.setShareNote(enabled)
        val current = store.current()
        if (!current.joined || !current.enabled) {
            runtime.update { it.copy(error = null) }
            return@withLock null
        }
        val spot = parkingRepository.latestSpot() ?: return@withLock null
        try {
            syncSpotLocked(apiFor(current), current, spot)
        } catch (error: Exception) {
            fail(error)
        }
    }

    /** Re-uploads the latest spot after its note changed, if sharing is on. */
    suspend fun onLocalNoteChanged(): FamilyError? = mutex.withLock {
        val current = store.current()
        if (!current.joined || !current.enabled || !current.shareNote) {
            return@withLock null
        }
        val spot = parkingRepository.latestSpot() ?: return@withLock null
        try {
            syncSpotLocked(apiFor(current), current, spot)
        } catch (error: Exception) {
            fail(error)
        }
    }

    /**
     * Marks every photo sent for a spot for revocation and tries to remove it
     * from the server now. Already downloaded family copies disappear on the
     * recipients' next refresh through the revocation list.
     */
    suspend fun revokePhotosForSpot(spotId: Long): FamilyError? = mutex.withLock {
        sentPhotoDao.markRevocationRequested(spotId)
        val current = store.current()
        if (!current.joined) {
            sentPhotoDao.deleteForSpot(spotId)
            return@withLock null
        }
        try {
            revokePendingPhotosLocked(apiFor(current), current)
        } catch (error: Exception) {
            fail(error)
        }
    }

    /** Marks all sent photos for revocation, used when the whole history goes. */
    suspend fun revokeAllSentPhotos(): FamilyError? = mutex.withLock {
        sentPhotoDao.markAllRevocationRequested()
        val current = store.current()
        if (!current.joined) {
            current.groupId?.let { sentPhotoDao.deleteForGroup(it) }
            return@withLock null
        }
        try {
            revokePendingPhotosLocked(apiFor(current), current)
        } catch (error: Exception) {
            fail(error)
        }
    }

    /**
     * Reconciles the family server after a local spot was deleted. The family
     * only ever sees the latest spot, so the previous one is uploaded; if the
     * history is now empty the remote spot is removed instead of surviving
     * until the server-side expiry.
     */
    suspend fun onLocalSpotDeleted(): FamilyError? = mutex.withLock {
        val current = store.current()
        if (!current.joined) return@withLock null
        try {
            val api = apiFor(current)
            val latest = if (current.enabled) parkingRepository.latestSpot() else null
            if (latest != null) {
                syncSpotLocked(api, current, latest)
            } else {
                clearSpotLocked(api, current)
            }
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
            clearRemoteSpot(current)
            return null
        }
        return refresh().let { error ->
            if (error != null) error else syncLatest()
        }
    }

    suspend fun updateCarLabel(carLabel: String): FamilyError? = mutex.withLock {
        val current = store.current()
        val label = cleanCarLabel(carLabel) ?: return@withLock FamilyError.INVALID_NAME
        if (!current.joined) return@withLock FamilyError.NOT_CONNECTED
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
        try {
            if (current.encrypted) {
                // The server cannot store a label for an encrypted group, so a
                // changed label reaches the family on the next spot upload.
                val updated = store.current()
                val spot = parkingRepository.latestSpot()
                if (spot != null) syncSpotLocked(apiFor(updated), updated, spot)
                null
            } else {
                apiFor(current).updateCarLabel(current.deviceToken!!, label)
                null
            }
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

    suspend fun rotateInviteCode(): FamilyError? = mutex.withLock {
        val current = store.current()
        if (!current.joined) return@withLock FamilyError.NOT_CONNECTED
        val adminToken = current.adminToken ?: return@withLock FamilyError.UNAUTHORIZED
        return@withLock try {
            val api = apiFor(current)
            if (current.encrypted) {
                val groupKey = groupKeyFor(current)
                val secret = FamilyCrypto.newInviteSecret()
                val wrapped = crypto { FamilyCrypto.wrapGroupKey(groupKey, secret) }
                val request = FamilyCryptoRequest(
                    keySalt = wrapped.keySalt,
                    wrappedKey = wrapped.wrappedKey,
                    keyIv = wrapped.keyIv,
                    joinAuthHash = cryptoHash(secret),
                )
                val code = api.rotateInviteCode(current.deviceToken!!, adminToken, request)
                store.setInvite(code, secret)
            } else {
                val code = api.rotateInviteCode(current.deviceToken!!, adminToken, null)
                store.setInvite(code, null)
            }
            runtime.update { it.copy(error = null) }
            null
        } catch (error: Exception) {
            fail(error)
        }
    }

    suspend fun leaveGroup(): FamilyError? = mutex.withLock {
        val current = store.current()
        if (!current.joined) {
            clearLocalSession()
            return@withLock null
        }
        return@withLock try {
            val api = apiFor(current)
            // Best effort: retract sent photos before the device disappears.
            // Leaving still proceeds if the network cannot deliver the request.
            sentPhotoDao.markAllRevocationRequested()
            runCatching { revokePendingPhotosLocked(api, current) }
            api.leave(current.deviceToken!!)
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
            val groupKey = if (current.encrypted) groupKeyFor(current) else null
            val snapshot = api.fetchSpots(token)
            val members = snapshot.members.map { remote ->
                val decrypted = remote.spot?.let { spot ->
                    decryptSpot(current, remote.deviceId, remote.carLabel, spot, groupKey)
                }
                if (
                    current.encrypted &&
                    remote.deviceId == current.deviceId &&
                    remote.spot?.payload != null &&
                    decrypted == null
                ) {
                    throw FamilyApiException(FamilyError.CRYPTO)
                }
                val label = decrypted?.carLabel
                    ?: remote.carLabel.takeIf { !current.encrypted }
                    ?: current.carLabel.takeIf { remote.deviceId == current.deviceId }
                    .orEmpty()
                FamilyMember(
                    deviceId = remote.deviceId,
                    carLabel = label,
                    lastSeenAtEpochMs = remote.lastSeenAtEpochMs,
                    spot = decrypted?.let { payload ->
                        FamilySpot(
                            deviceId = remote.deviceId,
                            carLabel = payload.carLabel,
                            latitude = payload.latitude,
                            longitude = payload.longitude,
                            accuracyMeters = payload.accuracyMeters,
                            savedAtEpochMs = payload.savedAtEpochMs,
                            lastSeenAtEpochMs = remote.lastSeenAtEpochMs,
                            note = payload.note,
                        )
                    },
                )
            }
            runtime.update {
                it.copy(
                    members = members,
                    lastSyncEpochMs = System.currentTimeMillis(),
                    error = null,
                )
            }
            if (runtime.value.pendingDelete) {
                val latest = parkingRepository.latestSpot()
                if (current.enabled && latest != null) {
                    syncSpotLocked(api, current, latest)
                } else {
                    clearSpotLocked(api, current)
                }
            } else if (runtime.value.pendingUpload) {
                syncLatestLocked(api, current)
            }

            // Photo errors must not hide the fresh spot list, so handle them
            // separately from the spots request above.
            var photoError: FamilyError? = null
            if (current.encrypted) {
                try {
                    // Retry a local photo deletion that happened while offline.
                    revokePendingPhotosLocked(api, current)?.let { photoError = it }
                    // Even with sharing switched off, fetch the revocation list
                    // so a photo the sender withdrew disappears here too. New
                    // photos are only downloaded while sharing is enabled.
                    syncPhotosLocked(api, current, downloadPhotos = current.enabled)
                        ?.let { error ->
                            if (photoError == null) photoError = error
                        }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    val mapped = fail(error)
                    if (photoError == null) photoError = mapped
                }
            }
            photoError
        } catch (error: Exception) {
            fail(error)
        }
    }

    private suspend fun syncLatestLocked(api: FamilyApi, current: FamilyCredentials): FamilyError? {
        if (!current.enabled || !current.joined) return null
        val spot = parkingRepository.latestSpot()
        if (spot == null) {
            return if (runtime.value.pendingDelete) clearSpotLocked(api, current) else null
        }
        return syncSpotLocked(api, current, spot)
    }

    private suspend fun clearSpotLocked(
        api: FamilyApi,
        current: FamilyCredentials,
    ): FamilyError? {
        val token = current.deviceToken ?: return FamilyError.NOT_CONNECTED
        runtime.update { it.copy(syncing = true) }
        return try {
            api.deleteSpot(token)
            runtime.update {
                it.copy(
                    syncing = false,
                    pendingUpload = false,
                    pendingDelete = false,
                    members = it.members.map { member ->
                        if (member.deviceId == current.deviceId) {
                            member.copy(spot = null)
                        } else {
                            member
                        }
                    },
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
                    pendingDelete = true,
                    error = mapped,
                )
            }
            mapped
        }
    }

    private suspend fun syncSpotLocked(
        api: FamilyApi,
        current: FamilyCredentials,
        spot: ParkingSpot,
    ): FamilyError? {
        val token = current.deviceToken ?: return FamilyError.NOT_CONNECTED
        runtime.update { it.copy(syncing = true) }
        return try {
            if (current.encrypted) {
                val groupKey = groupKeyFor(current)
                val payload = crypto {
                    FamilyCrypto.encryptSpot(
                        groupKey = groupKey,
                        groupId = current.groupId!!,
                        deviceId = current.deviceId!!,
                        keyVersion = current.keyVersion,
                        payload = FamilySpotPayload(
                            carLabel = current.carLabel,
                            latitude = spot.latitude,
                            longitude = spot.longitude,
                            accuracyMeters = spot.accuracyMeters?.toDouble(),
                            savedAtEpochMs = spot.savedAtEpochMs,
                            note = if (current.shareNote && current.encrypted) spot.note else "",
                        ),
                    )
                }
                api.uploadEncryptedSpot(
                    deviceToken = token,
                    payload = payload,
                    keyVersion = current.keyVersion,
                    savedAtEpochMs = spot.savedAtEpochMs,
                )
            } else {
                api.uploadSpot(token, spot)
            }
            runtime.update {
                it.copy(
                    syncing = false,
                    pendingUpload = false,
                    pendingDelete = false,
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

    /**
     * Downloads pending photos, decrypts them on this phone, saves them under
     * the app's private directory and acknowledges receipt. The Worker deletes
     * the server copy when the last family phone acknowledges.
     *
     * A photo that is authentic but cannot be opened (for example because it
     * was produced by a broken client) is acknowledged too: keeping it on the
     * server would waste the quota until the TTL and could never be displayed.
     */
    private suspend fun syncPhotosLocked(
        api: FamilyApi,
        current: FamilyCredentials,
        downloadPhotos: Boolean,
    ): FamilyError? {
        val token = current.deviceToken ?: return FamilyError.NOT_CONNECTED
        val groupId = current.groupId ?: return FamilyError.NOT_CONNECTED
        val listing = api.listPhotos(token)
        runtime.update { it.copy(photoUploadEnabled = listing.uploadEnabled) }

        // A sender may retract a wrong photo after this phone downloaded it.
        // Remove the local copy even though the server no longer has the image.
        // This runs even when sharing is off, so retractions are always applied.
        for (photoId in listing.revokedPhotoIds) {
            if (!isSafePhotoId(photoId)) continue
            photoDao.byId(photoId)?.let { deleteReceivedPhoto(it) }
        }

        val remotePhotos = listing.photos
        if (!downloadPhotos || remotePhotos.isEmpty()) return null

        val groupKey = groupKeyFor(current)
        val labels = runtime.value.members.associate { it.deviceId to it.carLabel }
        var firstError: FamilyError? = null
        for (remote in remotePhotos) {
            if (!isSafePhotoId(remote.id)) continue
            try {
                if (photoDao.byId(remote.id) != null) {
                    // Downloaded earlier, but the ACK did not reach the server.
                    api.ackPhoto(token, remote.id)
                    continue
                }
                val ciphertext = api.downloadPhoto(token, remote.id)
                if (ciphertext.size > MAX_RECEIVED_PHOTO_BYTES) {
                    api.ackPhoto(token, remote.id)
                    continue
                }
                val plaintext = crypto {
                    FamilyCrypto.decryptPhoto(
                        groupKey = groupKey,
                        groupId = groupId,
                        photoId = remote.id,
                        senderDeviceId = remote.senderDeviceId,
                        keyVersion = remote.keyVersion,
                        ivEncoded = remote.iv,
                        ciphertext = ciphertext,
                    )
                }
                if (plaintext == null) {
                    api.ackPhoto(token, remote.id)
                    continue
                }

                val file = saveReceivedPhoto(groupId, remote.id, plaintext)
                try {
                    photoDao.insert(
                        FamilyPhotoEntity(
                            id = remote.id,
                            groupId = groupId,
                            senderDeviceId = remote.senderDeviceId,
                            senderLabel = labels[remote.senderDeviceId].orEmpty(),
                            savedAtEpochMs = remote.savedAtEpochMs,
                            receivedAtEpochMs = System.currentTimeMillis(),
                            filePath = file.absolutePath,
                        ),
                    )
                } catch (error: Exception) {
                    file.delete()
                    throw error
                }
                api.ackPhoto(token, remote.id)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val mapped = mapError(error)
                if (firstError == null) firstError = mapped
                if (mapped == FamilyError.NETWORK || mapped == FamilyError.UNAUTHORIZED) {
                    break
                }
            }
        }
        return firstError
    }

    private suspend fun revokePendingPhotosLocked(
        api: FamilyApi,
        current: FamilyCredentials,
    ): FamilyError? {
        val token = current.deviceToken ?: return FamilyError.NOT_CONNECTED
        val groupId = current.groupId ?: return FamilyError.NOT_CONNECTED
        val pending = sentPhotoDao.pendingRevocations(groupId)
        if (pending.isEmpty()) return null

        var firstError: FamilyError? = null
        for (photo in pending) {
            try {
                api.deletePhoto(token, photo.photoId)
                sentPhotoDao.delete(photo.photoId)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val status = (error as? FamilyApiException)?.status
                if (status == 404 || status == 403) {
                    // The server no longer knows the photo, or it belongs to a
                    // previous group. Nothing useful left to retry.
                    sentPhotoDao.delete(photo.photoId)
                    continue
                }
                val mapped = mapError(error)
                if (firstError == null) firstError = mapped
                if (
                    mapped == FamilyError.NETWORK ||
                    mapped == FamilyError.UNAUTHORIZED ||
                    mapped == FamilyError.RATE_LIMITED
                ) {
                    break
                }
            }
        }
        return firstError
    }

    private suspend fun deleteReceivedPhoto(photo: FamilyPhotoEntity) {
        withContext(Dispatchers.IO) {
            runCatching {
                val root = familyPhotosDir().canonicalFile
                val file = File(photo.filePath).canonicalFile
                if (file.path.startsWith(root.path + File.separator)) {
                    file.delete()
                }
            }
        }
        photoDao.delete(photo.id)
    }

    private suspend fun saveReceivedPhoto(
        groupId: String,
        photoId: String,
        bytes: ByteArray,
    ): File = withContext(Dispatchers.IO) {
        val root = familyPhotosDir().canonicalFile
        val directory = File(root, groupId).apply { mkdirs() }.canonicalFile
        require(directory.path.startsWith(root.path + File.separator)) {
            "invalid photo directory"
        }
        val target = File(directory, "$photoId.jpg").canonicalFile
        require(target.path.startsWith(directory.path + File.separator)) {
            "invalid photo path"
        }
        target.writeBytes(bytes)
        target
    }

    private fun isSafePhotoId(id: String): Boolean =
        runCatching { UUID.fromString(id) }.isSuccess

    private fun familyPhotosDir(): File = File(context.filesDir, "family_photos")

    private suspend fun clearReceivedPhotos(groupId: String) {
        photoDao.deleteForGroup(groupId)
        withContext(Dispatchers.IO) {
            runCatching {
                val root = familyPhotosDir().canonicalFile
                val directory = File(root, groupId).canonicalFile
                if (directory.path.startsWith(root.path + File.separator)) {
                    directory.deleteRecursively()
                }
            }
        }
    }

    private fun decryptSpot(
        current: FamilyCredentials,
        deviceId: String,
        remoteLabel: String,
        spot: RemoteFamilySpot,
        groupKey: ByteArray?,
    ): FamilySpotPayload? {
        if (!current.encrypted) {
            val latitude = spot.latitude ?: return null
            val longitude = spot.longitude ?: return null
            val label = remoteLabel.ifBlank {
                if (deviceId == current.deviceId) current.carLabel else ""
            }
            return FamilySpotPayload(
                carLabel = label,
                latitude = latitude,
                longitude = longitude,
                accuracyMeters = spot.accuracyMeters,
                savedAtEpochMs = spot.savedAtEpochMs ?: System.currentTimeMillis(),
            )
        }
        val payload = spot.payload ?: return null
        val key = groupKey ?: return null
        val version = spot.keyVersion ?: current.keyVersion
        return crypto {
            FamilyCrypto.decryptSpot(
                groupKey = key,
                groupId = current.groupId!!,
                deviceId = deviceId,
                keyVersion = version,
                encoded = payload,
            )
        }
    }

    private suspend fun saveSession(
        current: FamilyCredentials,
        session: FamilySession,
        label: String,
        inviteSecret: String?,
        wrappedGroupKey: String?,
    ) {
        val mode = session.crypto.mode
        store.saveSession(
            serverUrl = current.serverUrl,
            groupId = session.groupId,
            deviceId = session.deviceId,
            deviceToken = session.deviceToken,
            adminToken = session.adminToken,
            inviteCode = session.inviteCode,
            carLabel = label,
            cryptoMode = mode,
            keyVersion = if (mode == FamilyCryptoMode.E2EE) {
                session.crypto.keyVersion
            } else {
                FamilyCrypto.KEY_VERSION
            },
            wrappedGroupKey = wrappedGroupKey,
            inviteSecret = inviteSecret,
        )
    }

    private fun unwrapGroupKey(secret: String, crypto: FamilyCryptoInfo): ByteArray? {
        val salt = crypto.keySalt ?: return null
        val iv = crypto.keyIv ?: return null
        val wrapped = crypto.wrappedKey ?: return null
        return crypto {
            FamilyCrypto.unwrapGroupKey(secret, salt, iv, wrapped)
        }
    }

    private fun cryptoHash(secret: String): String = crypto {
        FamilyCrypto.joinAuthKey(secret)
    }

    private fun groupKeyFor(current: FamilyCredentials): ByteArray {
        if (!current.encrypted) throw FamilyApiException(FamilyError.CRYPTO)
        val wrapped = current.wrappedGroupKey ?: throw FamilyApiException(FamilyError.CRYPTO)
        return try {
            keyVault.unwrap(wrapped)
        } catch (error: Exception) {
            throw FamilyApiException(FamilyError.CRYPTO, cause = error)
        }
    }

    private inline fun <T> crypto(block: () -> T): T = try {
        block()
    } catch (error: FamilyCryptoException) {
        throw error
    } catch (error: Exception) {
        throw FamilyCryptoException("Family crypto failed", error)
    }

    private fun fail(error: Exception): FamilyError {
        if (error is CancellationException) throw error
        val mapped = mapError(error)
        runtime.update { it.copy(error = mapped) }
        return mapped
    }

    private fun mapError(error: Exception): FamilyError = when (error) {
        is FamilyApiException -> error.error
        is FamilyCryptoException -> FamilyError.CRYPTO
        else -> FamilyError.UNKNOWN
    }

    private fun apiFor(current: FamilyCredentials): FamilyApi {
        val normalized = normalizeServerUrl(current.serverUrl)
            ?: throw FamilyApiException(FamilyError.INVALID_URL)
        return FamilyApi(normalized)
    }

    private suspend fun clearRemoteSpot(current: FamilyCredentials) {
        if (!current.joined || current.deviceToken == null) return
        mutex.withLock {
            val error = try {
                clearSpotLocked(apiFor(current), current)
            } catch (exception: Exception) {
                mapError(exception)
            }
            if (error != null) {
                // Sharing is already off locally. Keep the deletion pending
                // so a later refresh can retry without re-enabling uploads.
                runtime.update { it.copy(pendingDelete = true, error = null) }
            }
        }
    }

    private suspend fun clearLocalSession() {
        val current = store.current()
        store.clear()
        current.groupId?.let { groupId ->
            clearReceivedPhotos(groupId)
            sentPhotoDao.deleteForGroup(groupId)
        }
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
        const val MAX_RECEIVED_PHOTO_BYTES = 2_100_000
        const val SENT_PHOTO_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}
