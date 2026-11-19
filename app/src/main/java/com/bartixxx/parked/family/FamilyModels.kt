package com.bartixxx.parked.family

import com.bartixxx.parked.data.db.FamilyPhotoEntity

/** Default public Worker. Users can point the app at their own HTTPS server. */
const val DEFAULT_FAMILY_SERVER = "https://parked.bartixxx.workers.dev"

data class FamilyCredentials(
    val enabled: Boolean = false,
    val serverUrl: String = DEFAULT_FAMILY_SERVER,
    val groupId: String? = null,
    val deviceId: String? = null,
    val deviceToken: String? = null,
    val adminToken: String? = null,
    val inviteCode: String? = null,
    /**
     * The car label shown to the family (model or registration plate). New
     * groups encrypt it; the legacy API still uses `displayName` for old
     * plaintext groups.
     */
    val carLabel: String = "",
    val cryptoMode: FamilyCryptoMode = FamilyCryptoMode.PLAINTEXT,
    val keyVersion: Int = FamilyCrypto.KEY_VERSION,
    val wrappedGroupKey: String? = null,
    val inviteSecret: String? = null,
) {
    val joined: Boolean
        get() = !groupId.isNullOrBlank() && !deviceToken.isNullOrBlank()

    val isAdmin: Boolean
        get() = !adminToken.isNullOrBlank()

    val encrypted: Boolean
        get() = cryptoMode == FamilyCryptoMode.E2EE

    /** Full invite link for E2EE groups; plaintext groups share only the code. */
    val inviteLink: String?
        get() {
            if (!encrypted) return null
            val code = inviteCode?.takeIf { it.isNotBlank() } ?: return null
            val secret = inviteSecret?.takeIf { it.isNotBlank() } ?: return null
            return FamilyCrypto.inviteLink(code, secret)
        }
}

data class FamilySpot(
    val deviceId: String,
    val carLabel: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double?,
    val savedAtEpochMs: Long,
    val lastSeenAtEpochMs: Long?,
)

data class FamilyMember(
    val deviceId: String,
    val carLabel: String,
    val lastSeenAtEpochMs: Long?,
    val spot: FamilySpot?,
)

enum class FamilyError {
    NETWORK,
    INVALID_CODE,
    INVALID_INVITE,
    UNAUTHORIZED,
    INVALID_NAME,
    INVALID_URL,
    NOT_CONNECTED,
    RATE_LIMITED,
    GROUP_FULL,
    CRYPTO,
    PHOTO_LIMIT,
    PHOTO_TOO_LARGE,
    PHOTO_FAILED,
    NO_PHOTO,
    NO_RECIPIENTS,
    SERVER,
    UNKNOWN,
}

data class FamilyState(
    val credentials: FamilyCredentials = FamilyCredentials(),
    val members: List<FamilyMember> = emptyList(),
    val loading: Boolean = false,
    val syncing: Boolean = false,
    val sendingPhoto: Boolean = false,
    val pendingUpload: Boolean = false,
    val error: FamilyError? = null,
    val lastSyncEpochMs: Long? = null,
    /** Family photos already downloaded and decrypted on this phone. */
    val photos: List<FamilyPhotoEntity> = emptyList(),
) {
    val joined: Boolean
        get() = credentials.joined

    val enabled: Boolean
        get() = credentials.enabled

    val familySpots: List<FamilySpot>
        get() = members.mapNotNull { it.spot }
}

/**
 * Invite codes use Crockford base32: no I, L, O or U. Ten characters give 50 bits
 * of entropy. The UI shows a dash in the middle; the API receives the raw code.
 */
object InviteCode {
    const val LENGTH = 10
    private val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val valid = Regex("^[0-9A-HJKMNP-TV-Z]{$LENGTH}$")

    fun normalize(raw: String): String? {
        val code = raw
            .uppercase()
            .filter { it.isLetterOrDigit() }
            .replace('O', '0')
            .replace('I', '1')
            .replace('L', '1')
        return code.takeIf(valid::matches)
    }

    fun format(code: String): String =
        if (code.length == LENGTH) "${code.take(5)}-${code.drop(5)}" else code

    fun isComplete(raw: String): Boolean = normalize(raw) != null

    fun alphabet(): String = alphabet
}
