package com.bartixxx.parked.family

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class FamilyCryptoMode {
    PLAINTEXT,
    E2EE,
    ;

    val wireValue: String
        get() = if (this == E2EE) "e2ee" else "plaintext"

    companion object {
        fun fromWire(raw: String?): FamilyCryptoMode =
            if (raw == "e2ee") E2EE else PLAINTEXT
    }
}

class FamilyCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Everything the server needs to let a new device unwrap the group key. */
data class FamilyGroupKeyMaterial(
    val groupKey: ByteArray,
    val inviteSecret: String,
    val keySalt: String,
    val keyIv: String,
    val wrappedKey: String,
)

data class FamilyWrappedKey(
    val keySalt: String,
    val keyIv: String,
    val wrappedKey: String,
)

data class FamilyInvite(
    val code: String,
    val secret: String?,
)

/** Values that travel inside the encrypted spot payload. */
data class FamilySpotPayload(
    val carLabel: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double?,
    val savedAtEpochMs: Long,
)

/**
 * End-to-end encryption for family mode.
 *
 * The server only sees ciphertext. The 256-bit group key is generated on the
 * phone and stored locally wrapped by a non-exportable Android Keystore key.
 * A joiner receives the group key wrapped with a 128-bit secret that lives only
 * in the invite link fragment; the Worker never sees that secret.
 */
object FamilyCrypto {

    const val KEY_VERSION = 1
    const val MAX_LABEL_BYTES = 240

    private const val KEY_BYTES = 32
    private const val SECRET_BYTES = 16
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val PAYLOAD_BYTES = 384
    private const val FORMAT_VERSION = 1
    private const val FLAG_ACCURACY = 1
    private const val HEADER_BYTES = 8
    private val MAGIC = byteArrayOf(0x50, 0x4B, 0x53, 0x50) // PKSP

    private val random = SecureRandom()
    private val urlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val urlDecoder: Base64.Decoder = Base64.getUrlDecoder()
    private val keyWrapAad = "parked-key-wrap|v1".toByteArray(Charsets.UTF_8)
    private val keyWrapSalt = "parked-key-wrap-salt-v1".toByteArray(Charsets.UTF_8)
    private val keyWrapInfo = "key-wrap|v1".toByteArray(Charsets.UTF_8)
    private val joinAuthSalt = "parked-join-auth-v1".toByteArray(Charsets.UTF_8)
    private val joinAuthInfo = "join-auth|v1".toByteArray(Charsets.UTF_8)
    private val spotSalt = "parked-spot-salt-v1".toByteArray(Charsets.UTF_8)

    fun newGroupKeyMaterial(): FamilyGroupKeyMaterial {
        val groupKey = randomBytes(KEY_BYTES)
        val secret = newInviteSecret()
        val wrapped = wrapGroupKey(groupKey, secret)
        return FamilyGroupKeyMaterial(
            groupKey = groupKey,
            inviteSecret = secret,
            keySalt = wrapped.keySalt,
            keyIv = wrapped.keyIv,
            wrappedKey = wrapped.wrappedKey,
        )
    }

    fun newInviteSecret(): String = encodeBase64(randomBytes(SECRET_BYTES))

    fun joinAuthKey(secret: String): String =
        encodeBase64(hkdf(decodeBase64(secret), joinAuthSalt, joinAuthInfo, KEY_BYTES))

    fun wrapGroupKey(groupKey: ByteArray, secret: String): FamilyWrappedKey {
        require(groupKey.size == KEY_BYTES) { "invalid group key" }
        val secretBytes = decodeBase64(secret)
        require(secretBytes.size == SECRET_BYTES) { "invalid invite secret" }
        val salt = randomBytes(SALT_BYTES)
        val iv = randomBytes(IV_BYTES)
        val wrappingKey = hkdf(secretBytes, salt, keyWrapInfo, KEY_BYTES)
        val ciphertext = aesGcmEncrypt(wrappingKey, iv, groupKey, keyWrapAad)
        return FamilyWrappedKey(
            keySalt = encodeBase64(salt),
            keyIv = encodeBase64(iv),
            wrappedKey = encodeBase64(ciphertext),
        )
    }

    /** Returns null when the secret is wrong or the wrapped key is corrupt. */
    fun unwrapGroupKey(
        secret: String,
        keySalt: String,
        keyIv: String,
        wrappedKey: String,
    ): ByteArray? = try {
        val salt = decodeBase64(keySalt)
        val iv = decodeBase64(keyIv)
        val ciphertext = decodeBase64(wrappedKey)
        if (salt.size != SALT_BYTES || iv.size != IV_BYTES) {
            null
        } else {
            val wrappingKey = hkdf(decodeBase64(secret), salt, keyWrapInfo, KEY_BYTES)
            aesGcmDecrypt(wrappingKey, iv, ciphertext, keyWrapAad)
                ?.takeIf { it.size == KEY_BYTES }
        }
    } catch (_: Exception) {
        null
    }

    fun inviteLink(code: String, secret: String): String = "parked://join/$code#$secret"

    /**
     * Accepts a full invite link or a bare code. The secret fragment never goes
     * to the server; only an HKDF-derived auth value is sent when joining.
     */
    fun parseInvite(raw: String): FamilyInvite? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        val marker = "parked://join/"
        val markerIndex = text.indexOf(marker, ignoreCase = true)
        if (markerIndex >= 0) {
            val rest = text
                .substring(markerIndex + marker.length)
                .substringBefore(' ')
                .substringBefore('\n')
                .substringBefore('\r')
                .trim()
            if (rest.isEmpty()) return null
            val hash = rest.indexOf('#')
            val codePart = if (hash >= 0) rest.substring(0, hash) else rest
            val secretPart = if (hash >= 0) rest.substring(hash + 1) else null
            val code = InviteCode.normalize(codePart) ?: return null
            val secret = secretPart?.let { candidate ->
                candidate.takeIf { isValidSecret(it) } ?: return null
            }
            return FamilyInvite(code, secret)
        }

        val code = InviteCode.normalize(text) ?: return null
        return FamilyInvite(code, null)
    }

    fun isValidSecret(secret: String): Boolean = try {
        decodeBase64(secret).size == SECRET_BYTES
    } catch (_: Exception) {
        false
    }

    fun encryptSpot(
        groupKey: ByteArray,
        groupId: String,
        deviceId: String,
        keyVersion: Int,
        payload: FamilySpotPayload,
    ): String {
        require(groupKey.size == KEY_BYTES) { "invalid group key" }
        require(keyVersion > 0) { "invalid key version" }
        require(payload.latitude.isFinite() && payload.latitude in -90.0..90.0) {
            "invalid latitude"
        }
        require(payload.longitude.isFinite() && payload.longitude in -180.0..180.0) {
            "invalid longitude"
        }
        require(payload.savedAtEpochMs > 0) { "invalid savedAt" }
        payload.accuracyMeters?.let {
            require(it.isFinite() && it >= 0.0) { "invalid accuracy" }
        }

        val label = payload.carLabel.toByteArray(Charsets.UTF_8)
        require(label.size in 1..MAX_LABEL_BYTES) { "invalid label" }
        check(HEADER_BYTES + label.size + 32 <= PAYLOAD_BYTES) { "payload too large" }

        val plain = ByteBuffer.allocate(PAYLOAD_BYTES)
        plain.put(MAGIC)
        plain.put(FORMAT_VERSION.toByte())
        plain.put((if (payload.accuracyMeters != null) FLAG_ACCURACY else 0).toByte())
        plain.putShort(label.size.toShort())
        plain.put(label)
        plain.putDouble(payload.latitude)
        plain.putDouble(payload.longitude)
        plain.putDouble(payload.accuracyMeters ?: 0.0)
        plain.putLong(payload.savedAtEpochMs)

        val iv = randomBytes(IV_BYTES)
        val key = spotKey(groupKey, groupId, deviceId, keyVersion)
        val ciphertext = aesGcmEncrypt(
            key,
            iv,
            plain.array(),
            spotAad(groupId, deviceId, keyVersion),
        )
        return encodeBase64(iv + ciphertext)
    }

    /** Returns null for anything that is not authentic ciphertext from this group. */
    fun decryptSpot(
        groupKey: ByteArray,
        groupId: String,
        deviceId: String,
        keyVersion: Int,
        encoded: String,
    ): FamilySpotPayload? = try {
        val combined = decodeBase64(encoded)
        if (keyVersion <= 0 || combined.size <= IV_BYTES) {
            null
        } else {
            val iv = combined.copyOfRange(0, IV_BYTES)
            val ciphertext = combined.copyOfRange(IV_BYTES, combined.size)
            val key = spotKey(groupKey, groupId, deviceId, keyVersion)
            val plain = aesGcmDecrypt(
                key,
                iv,
                ciphertext,
                spotAad(groupId, deviceId, keyVersion),
            )
            plain?.let(::parseSpotPlaintext)
        }
    } catch (_: Exception) {
        null
    }

    private fun parseSpotPlaintext(plain: ByteArray): FamilySpotPayload? {
        if (plain.size != PAYLOAD_BYTES) return null
        val buffer = ByteBuffer.wrap(plain)
        val magic = ByteArray(MAGIC.size)
        buffer.get(magic)
        if (!magic.contentEquals(MAGIC)) return null
        val version = buffer.get().toInt() and 0xFF
        if (version != FORMAT_VERSION) return null
        val flags = buffer.get().toInt() and 0xFF
        val labelLength = buffer.short.toInt() and 0xFFFF
        if (labelLength !in 1..MAX_LABEL_BYTES) return null
        if (HEADER_BYTES + labelLength + 32 > PAYLOAD_BYTES) return null

        val labelBytes = ByteArray(labelLength)
        buffer.get(labelBytes)
        val label = String(labelBytes, Charsets.UTF_8)
        val latitude = buffer.double
        val longitude = buffer.double
        val accuracyRaw = buffer.double
        val savedAt = buffer.long
        if (!latitude.isFinite() || latitude !in -90.0..90.0) return null
        if (!longitude.isFinite() || longitude !in -180.0..180.0) return null
        if (savedAt <= 0) return null
        val accuracy = if (flags and FLAG_ACCURACY != 0) accuracyRaw else null
        if (accuracy != null && (!accuracy.isFinite() || accuracy < 0.0)) return null
        if (label.isBlank()) return null
        return FamilySpotPayload(
            carLabel = label,
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracy,
            savedAtEpochMs = savedAt,
        )
    }

    private fun spotKey(
        groupKey: ByteArray,
        groupId: String,
        deviceId: String,
        keyVersion: Int,
    ): ByteArray = hkdf(
        groupKey,
        spotSalt,
        "spot|$groupId|$deviceId|$keyVersion".toByteArray(Charsets.UTF_8),
        KEY_BYTES,
    )

    private fun spotAad(groupId: String, deviceId: String, keyVersion: Int): ByteArray =
        "parked-spot|v1|$groupId|$deviceId|$keyVersion".toByteArray(Charsets.UTF_8)

    internal fun hkdf(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        require(length in 1..(255 * 32)) { "invalid HKDF length" }
        val effectiveSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val prk = hmac(effectiveSalt, ikm)
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val take = minOf(previous.size, length - offset)
            previous.copyInto(output, offset, 0, take)
            offset += take
            counter += 1
        }
        return output
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun aesGcmEncrypt(
        key: ByteArray,
        iv: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    private fun aesGcmDecrypt(
        key: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        cipher.updateAAD(aad)
        cipher.doFinal(ciphertext)
    } catch (_: Exception) {
        null
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    internal fun encodeBase64(bytes: ByteArray): String = urlEncoder.encodeToString(bytes)

    internal fun decodeBase64(value: String): ByteArray = urlDecoder.decode(value)

    internal fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}
