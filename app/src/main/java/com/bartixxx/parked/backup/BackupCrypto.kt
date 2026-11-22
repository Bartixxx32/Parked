package com.bartixxx.parked.backup

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-based encryption for the whole backup archive. The password never
 * leaves the phone and is not stored anywhere. PBKDF2 derives a 256-bit key;
 * AES-GCM authenticates both the header and the payload.
 */
internal object BackupCrypto {

    private val MAGIC = byteArrayOf(0x50, 0x52, 0x4B, 0x42) // PRKB
    private const val FORMAT_VERSION = 1
    private const val ITERATIONS = 210_000
    private const val MIN_ITERATIONS = 10_000
    private const val MAX_ITERATIONS = 1_000_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KDF = "PBKDF2WithHmacSHA256"
    private val random = SecureRandom()

    fun encrypt(source: File, target: File, password: CharArray) {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val header = buildHeader(ITERATIONS, salt, iv)
        val key = deriveKey(password, salt, ITERATIONS)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, iv),
        )
        cipher.updateAAD(header)

        FileOutputStream(target).use { fileOutput ->
            fileOutput.write(header)
            CipherOutputStream(fileOutput, cipher).use { encrypted ->
                FileInputStream(source).use { input -> input.copyTo(encrypted) }
            }
        }
    }

    /**
     * @throws BackupException with [BackupFailure.WRONG_PASSWORD_OR_CORRUPT] when
     * the password is wrong or the ciphertext was modified, and
     * [BackupFailure.INVALID_FORMAT] for a file that is not a Parked backup.
     */
    fun decrypt(source: File, target: File, password: CharArray) {
        var salt: ByteArray
        var iv: ByteArray
        var iterations: Int
        var header: ByteArray
        try {
            FileInputStream(source).use { input ->
                val data = DataInputStream(input)
                val magic = ByteArray(MAGIC.size)
                data.readFully(magic)
                if (!magic.contentEquals(MAGIC)) throw invalidFormat()
                val version = data.readUnsignedByte()
                if (version != FORMAT_VERSION) {
                    throw BackupException(BackupFailure.UNSUPPORTED_VERSION)
                }
                iterations = data.readInt()
                if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw invalidFormat()
                val saltLength = data.readUnsignedByte()
                if (saltLength != SALT_BYTES) throw invalidFormat()
                salt = ByteArray(saltLength).also(data::readFully)
                val ivLength = data.readUnsignedByte()
                if (ivLength != IV_BYTES) throw invalidFormat()
                iv = ByteArray(ivLength).also(data::readFully)
                header = buildHeader(iterations, salt, iv)

                val key = deriveKey(password, salt, iterations)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(TAG_BITS, iv),
                )
                cipher.updateAAD(header)
                try {
                    CipherInputStream(input, cipher).use { clear ->
                        FileOutputStream(target).use { output -> clear.copyTo(output) }
                    }
                } catch (error: IOException) {
                    target.delete()
                    throw BackupException(BackupFailure.WRONG_PASSWORD_OR_CORRUPT, error)
                } catch (error: SecurityException) {
                    target.delete()
                    throw BackupException(BackupFailure.WRONG_PASSWORD_OR_CORRUPT, error)
                }
            }
        } catch (error: BackupException) {
            throw error
        } catch (error: IOException) {
            target.delete()
            throw BackupException(BackupFailure.WRONG_PASSWORD_OR_CORRUPT, error)
        } catch (error: Exception) {
            target.delete()
            throw BackupException(BackupFailure.INVALID_FORMAT, error)
        }
    }

    private fun buildHeader(iterations: Int, salt: ByteArray, iv: ByteArray): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.write(MAGIC)
            out.writeByte(FORMAT_VERSION)
            out.writeInt(iterations)
            out.writeByte(salt.size)
            out.write(salt)
            out.writeByte(iv.size)
            out.write(iv)
        }
        return buffer.toByteArray()
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun invalidFormat() = BackupException(BackupFailure.INVALID_FORMAT)
}

enum class BackupFailure {
    WRONG_PASSWORD_OR_CORRUPT,
    INVALID_FORMAT,
    UNSUPPORTED_VERSION,
    TOO_LARGE,
    IO,
}

class BackupException(
    val failure: BackupFailure,
    cause: Throwable? = null,
) : Exception(failure.name, cause)

data class BackupSummary(
    val spots: Int,
    val photos: Int,
    val bytes: Long,
)
