package com.prism.music.data

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * A backup that can be protected with a password: the zip is AES-256-GCM under a key made from the password (PBKDF2,
 * [ITERATIONS] rounds, a random salt), with a random IV. A protected file's contents, names included, can't be read
 * without the password, and a changed byte is refused instead of restored.
 *
 * The file starts with [MAGIC], then a version, the rounds, the salt and the IV; the rest is the sealed zip. A backup
 * without the magic is an ordinary zip (what Prism made before this, and what it makes with no password).
 */
internal object BackupCrypto {
    const val MAGIC = "PRISMBK2"
    const val VERSION = 1
    const val ITERATIONS = 210_000
    private const val SALT = 16
    private const val IV = 12
    private const val KEY_BITS = 256
    private const val MAX_ITERATIONS = 1_000_000

    class NeedsPassword : Exception()

    /** [stream] is the backup's zip; [drain] must be called after reading it to check the GCM tag (a plain zip's does nothing). */
    class Opened(val stream: InputStream, val encrypted: Boolean, val drain: () -> Unit)

    fun isEncrypted(head: ByteArray): Boolean =
        head.size >= MAGIC.length && String(head, 0, MAGIC.length, Charsets.US_ASCII) == MAGIC

    /** Writes the header to [out] and returns the stream the zip goes through (closing it seals and closes [out]). */
    fun seal(out: OutputStream, password: CharArray): OutputStream {
        val salt = ByteArray(SALT).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV).also { SecureRandom().nextBytes(it) }
        out.write(MAGIC.toByteArray(Charsets.US_ASCII))
        out.write(VERSION)
        out.write(byteArrayOf((ITERATIONS ushr 24).toByte(), (ITERATIONS ushr 16).toByte(), (ITERATIONS ushr 8).toByte(), ITERATIONS.toByte()))
        out.write(salt)
        out.write(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key(password, salt, ITERATIONS), GCMParameterSpec(128, iv))
        }
        return CipherOutputStream(out, cipher)
    }

    /** [input] from its first byte; a protected one needs [password] (null: [NeedsPassword]). */
    fun open(input: InputStream, password: CharArray?): Opened {
        val head = ByteArray(MAGIC.length)
        var got = 0
        while (got < head.size) {
            val n = input.read(head, got, head.size - got)
            if (n < 0) break
            got += n
        }
        if (got < head.size || !isEncrypted(head)) {
            // An ordinary zip: every byte read is put back.
            val back = PushbackInputStream(input, head.size)
            back.unread(head, 0, got)
            return Opened(BufferedInputStream(back), encrypted = false, drain = {})
        }
        val version = input.read()
        if (version != VERSION) throw NeedsPassword()
        val rounds = readFully(input, 4).let {
            ((it[0].toInt() and 0xFF) shl 24) or ((it[1].toInt() and 0xFF) shl 16) or
                ((it[2].toInt() and 0xFF) shl 8) or (it[3].toInt() and 0xFF)
        }
        if (rounds !in 1..MAX_ITERATIONS) throw NeedsPassword()
        val salt = readFully(input, SALT)
        val iv = readFully(input, IV)
        val pw = password ?: throw NeedsPassword()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(pw, salt, rounds), GCMParameterSpec(128, iv))
        }
        val clear = CipherInputStream(input, cipher)
        val stream = BufferedInputStream(clear)
        return Opened(stream, encrypted = true, drain = {
            // Past the zip's end, read to the end: the tag is checked there (a wrong password or a changed byte throws).
            val buf = ByteArray(8 * 1024)
            while (stream.read(buf) >= 0) Unit
        })
    }

    private fun readFully(input: InputStream, size: Int): ByteArray {
        val out = ByteArray(size)
        var got = 0
        while (got < size) {
            val n = input.read(out, got, size - got)
            if (n < 0) throw NeedsPassword()
            got += n
        }
        return out
    }

    private fun key(password: CharArray, salt: ByteArray, rounds: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, rounds, KEY_BITS)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
