package com.bartixxx.parked.family

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
    val displayName: String = "",
) {
    val joined: Boolean
        get() = !groupId.isNullOrBlank() && !deviceToken.isNullOrBlank()

    val isAdmin: Boolean
        get() = !adminToken.isNullOrBlank()
}

data class FamilySpot(
    val deviceId: String,
    val displayName: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double?,
    val savedAtEpochMs: Long,
    val lastSeenAtEpochMs: Long?,
)

data class FamilyMember(
    val deviceId: String,
    val displayName: String,
    val lastSeenAtEpochMs: Long?,
    val spot: FamilySpot?,
)

enum class FamilyError {
    NETWORK,
    INVALID_CODE,
    UNAUTHORIZED,
    INVALID_NAME,
    INVALID_URL,
    NOT_CONNECTED,
    SERVER,
    UNKNOWN,
}

data class FamilyState(
    val credentials: FamilyCredentials = FamilyCredentials(),
    val members: List<FamilyMember> = emptyList(),
    val loading: Boolean = false,
    val syncing: Boolean = false,
    val pendingUpload: Boolean = false,
    val error: FamilyError? = null,
    val lastSyncEpochMs: Long? = null,
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
