package com.bartixxx.parked.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A photo that this phone uploaded to the family server. The original image
 * stays in the spot's private photo directory; only the server-side photo id is
 * kept so the sender can retract a wrong photo later, even after every family
 * phone has already downloaded it.
 *
 * [revokeRequested] survives an offline deletion: the repository retries the
 * server-side revocation on the next family refresh.
 */
@Entity(
    tableName = "family_sent_photos",
    indices = [Index("spotId"), Index("revokeRequested")],
)
data class FamilySentPhotoEntity(
    @PrimaryKey val photoId: String,
    val spotId: Long,
    val groupId: String,
    val sentAtEpochMs: Long,
    val revokeRequested: Boolean = false,
)
