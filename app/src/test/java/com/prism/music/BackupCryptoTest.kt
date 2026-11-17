package com.prism.music

import com.prism.music.data.BackupCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Password-protected backups: the zip named with the file can't be read without the password, and a changed one is refused. */
class BackupCryptoTest {
    private val content = ByteArray(64 * 1024) { (it * 31 % 251).toByte() }

    private fun sealed(password: String, bytes: ByteArray = content): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCrypto.seal(out, password.toCharArray()).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun read(file: ByteArray, password: String?): ByteArray? = runCatching {
        val opened = BackupCrypto.open(ByteArrayInputStream(file), password?.toCharArray())
        val bytes = opened.stream.readBytes()
        opened.drain()
        opened.stream.close()
        bytes
    }.getOrNull()

    @Test fun theRightPasswordOpensItAndNothingElseDoes() {
        val file = sealed("a long enough password")
        assertTrue(BackupCrypto.isEncrypted(file))
        assertArrayEquals(content, read(file, "a long enough password"))
        assertNull("a wrong password doesn't open it", read(file, "another password"))
        assertNull("nor does no password", read(file, null))
        // The zip's own bytes (names included) aren't in the file: only the sealed ones are.
        assertFalse(String(file, Charsets.ISO_8859_1).contains("prism-backup"))
    }

    @Test fun aChangedByteIsRefused() {
        val file = sealed("a long enough password").also { it[it.size - 20] = (it[it.size - 20].toInt() xor 1).toByte() }
        assertNull(read(file, "a long enough password"))
    }

    /**
     * A backup is sealed a chunk at a time (1.1.1): Android's AES-GCM keeps a `CipherOutputStream`'s whole output in
     * memory, so a library's backup couldn't be written at all. Nothing of the size may matter now.
     */
    @Test fun aBackupBiggerThanAChunkComesBackWhole() {
        val big = ByteArray(BackupCrypto.CHUNK * 2 + 1234) { (it * 31 % 251).toByte() }
        val file = sealed("a long enough password", big)
        assertArrayEquals(big, read(file, "a long enough password"))
        assertNull("nor does a wrong password", read(file, "another password"))
    }

    /** A change in any chunk is refused, not just in the last one. */
    @Test fun aChangedByteInAnEarlyChunkIsRefused() {
        val big = ByteArray(BackupCrypto.CHUNK * 2 + 1234) { (it * 31 % 251).toByte() }
        val file = sealed("a long enough password", big)
        // Past the header (8 + 1 + 4 + 16 + 8 + 4) and into the first chunk's ciphertext.
        val at = 41 + 100
        file[at] = (file[at].toInt() xor 1).toByte()
        assertNull(read(file, "a long enough password"))
    }

    /** A file written by 1.0.0-1.1.0 (one GCM seal over the whole zip) still opens. */
    @Test fun aBackupSealedTheOldWayStillOpens() {
        val password = "a long enough password"
        val salt = ByteArray(16) { it.toByte() }
        val iv = ByteArray(12) { (it * 3).toByte() }
        val rounds = BackupCrypto.ITERATIONS
        val out = ByteArrayOutputStream()
        out.write(BackupCrypto.MAGIC.toByteArray(Charsets.US_ASCII))
        out.write(BackupCrypto.VERSION)
        out.write(byteArrayOf((rounds ushr 24).toByte(), (rounds ushr 16).toByte(), (rounds ushr 8).toByte(), rounds.toByte()))
        out.write(salt)
        out.write(iv)
        val key = javax.crypto.spec.SecretKeySpec(
            javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, rounds, 256)).encoded,
            "AES",
        )
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
        javax.crypto.CipherOutputStream(out, cipher).use { it.write(content) }
        assertArrayEquals(content, read(out.toByteArray(), password))
    }

    @Test fun anOrdinaryZipIsReadAsItIs() {
        val opened = BackupCrypto.open(ByteArrayInputStream(content), null)
        assertFalse(opened.encrypted)
        assertNotNull(opened.stream)
        assertArrayEquals(content, opened.stream.use { it.readBytes() })
        opened.drain()
    }

    /** The order a restore uses: a zip read through the sealed stream, then read on for the tag. */
    @Test fun aWholeZipCanBeReadThroughIt() {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(BackupCrypto.seal(out, "password".toCharArray())).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("prism-backup.json"))
            zip.write("""{"format":2,"app":"com.prism.music"}""".toByteArray())
            zip.closeEntry()
        }
        val opened = BackupCrypto.open(ByteArrayInputStream(out.toByteArray()), "password".toCharArray())
        val zip = java.util.zip.ZipInputStream(opened.stream)
        assertEquals("prism-backup.json", zip.nextEntry.name)
        val text = String(zip.readBytes())
        assertTrue(text.contains("com.prism.music"))
        assertNull(zip.nextEntry)
        opened.drain()
        opened.stream.close()
    }
}
