package com.bartixxx.parked.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A family photo that was downloaded, decrypted and saved on this phone.
 * Photos are always stored locally in the app's private files directory; the
 * server copy is deleted after every family phone confirms the download.
 */
@Entity(tableName = "family_photos", indices = [Index("groupId")])
data class FamilyPhotoEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val senderDeviceId: String,
    val senderLabel: String,
    val savedAtEpochMs: Long,
    val receivedAtEpochMs: Long,
    val filePath: String,
)
