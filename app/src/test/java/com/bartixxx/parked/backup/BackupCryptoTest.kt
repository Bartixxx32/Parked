package com.bartixxx.parked.backup

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupCryptoTest {

    @Test
    fun `round trip restores the original bytes`() {
        val directory = createTempDir()
        try {
            val clear = File(directory, "clear.bin").apply {
                writeBytes(ByteArray(256 * 1024) { (it * 31).toByte() })
            }
            val encrypted = File(directory, "backup.parked")
            val restored = File(directory, "restored.bin")

            BackupCrypto.encrypt(clear, encrypted, "correct horse".toCharArray())
            BackupCrypto.decrypt(encrypted, restored, "correct horse".toCharArray())

            assertArrayEquals(clear.readBytes(), restored.readBytes())
            assertEquals(false, encrypted.readBytes().contentEquals(clear.readBytes()))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `wrong password is rejected`() {
        val directory = createTempDir()
        try {
            val clear = File(directory, "clear.bin").apply { writeText("secret") }
            val encrypted = File(directory, "backup.parked")
            val restored = File(directory, "restored.bin")
            BackupCrypto.encrypt(clear, encrypted, "password one".toCharArray())

            val error = assertThrows(BackupException::class.java) {
                BackupCrypto.decrypt(encrypted, restored, "password two".toCharArray())
            }

            assertEquals(BackupFailure.WRONG_PASSWORD_OR_CORRUPT, error.failure)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `foreign file is rejected as invalid`() {
        val directory = createTempDir()
        try {
            val source = File(directory, "random.bin").apply { writeText("not a backup") }
            val target = File(directory, "restored.bin")

            val error = assertThrows(BackupException::class.java) {
                BackupCrypto.decrypt(source, target, "password".toCharArray())
            }

            assertEquals(BackupFailure.INVALID_FORMAT, error.failure)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun createTempDir(): File =
        java.nio.file.Files.createTempDirectory("parked-backup-test").toFile()
}
