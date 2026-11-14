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

    private fun sealed(password: String): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCrypto.seal(out, password.toCharArray()).use { it.write(content) }
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
