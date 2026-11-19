package com.bartixxx.parked.family

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps the family group key with a non-exportable Android Keystore key. The
 * DataStore only ever contains the wrapped ciphertext, never the raw key.
 */
class FamilyKeyVault {

    fun wrap(groupKey: ByteArray): String {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ciphertext = cipher.doFinal(groupKey)
            return "$VERSION:${FamilyCrypto.encodeBase64(cipher.iv)}:${
                FamilyCrypto.encodeBase64(ciphertext)
            }"
        } catch (error: Exception) {
            throw FamilyCryptoException("Cannot wrap family key", error)
        }
    }

    fun unwrap(value: String): ByteArray {
        try {
            val parts = value.split(':')
            if (parts.size != 3 || parts[0] != VERSION) {
                throw FamilyCryptoException("Invalid wrapped key")
            }
            val iv = FamilyCrypto.decodeBase64(parts[1])
            val ciphertext = FamilyCrypto.decodeBase64(parts[2])
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(TAG_BITS, iv),
            )
            return cipher.doFinal(ciphertext)
        } catch (error: FamilyCryptoException) {
            throw error
        } catch (error: Exception) {
            throw FamilyCryptoException("Cannot unwrap family key", error)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val existing = keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "parked_family_group_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION = "v1"
        const val TAG_BITS = 128
    }
}
