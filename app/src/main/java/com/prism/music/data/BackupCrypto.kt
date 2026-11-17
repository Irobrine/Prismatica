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
 * A backup that can be protected with a password: AES-256-GCM under a key made from the password (PBKDF2,
 * [ITERATIONS] rounds, a random salt). A protected file's contents, names included, can't be read without the password,
 * and a changed byte is refused instead of restored.
 *
 * The zip is sealed a chunk at a time ([CHUNK], each under its own IV counted from a random start), because Android's
 * AES-GCM keeps everything a [CipherOutputStream] gives it in memory until the end: a backup of a library's downloads
 * then needed its whole encrypted size in RAM and failed with an allocation error on a phone with the space for it.
 * Files written before this keep their whole-file seal and are still read ([MAGIC]).
 *
 * The file starts with a magic, then a version, the rounds, the salt and the IV (and, a chunked one, the chunk's size);
 * the rest is the sealed zip. A file without a magic is an ordinary zip (what Prism made before this, and what it makes
 * with no password).
 */
internal object BackupCrypto {
    /** A whole zip under one GCM seal (1.0.0-1.1.0): still read, never written. */
    const val MAGIC = "PRISMBK2"
    const val VERSION = 1
    /** A zip sealed a chunk at a time (1.1.1): written now. */
    const val MAGIC_CHUNKED = "PRISMBK3"
    const val VERSION_CHUNKED = 1
    const val ITERATIONS = 210_000
    private const val SALT = 16
    private const val IV = 12
    /** A chunked file's IVs: this many random bytes, then the chunk's number. */
    private const val NONCE = 8
    private const val KEY_BITS = 256
    private const val MAX_ITERATIONS = 1_000_000
    private const val TAG = 16
    /** What a chunked backup seals at a time: small enough to sit in memory beside the player, big enough to be quick. */
    const val CHUNK = 1 shl 20
    private const val MIN_CHUNK = 4 * 1024
    private const val MAX_CHUNK = 64 shl 20

    class NeedsPassword : Exception()

    /** [stream] is the backup's zip; [drain] must be called after reading it to check the GCM tag (a plain zip's does nothing). */
    class Opened(val stream: InputStream, val encrypted: Boolean, val drain: () -> Unit)

    fun isEncrypted(head: ByteArray): Boolean = magicOf(head) != null

    private fun magicOf(head: ByteArray): String? {
        if (head.size < MAGIC.length) return null
        val magic = String(head, 0, MAGIC.length, Charsets.US_ASCII)
        return magic.takeIf { it == MAGIC || it == MAGIC_CHUNKED }
    }

    /** Writes the header to [out] and returns the stream the zip goes through (closing it seals and closes [out]). */
    fun seal(out: OutputStream, password: CharArray): OutputStream {
        val salt = ByteArray(SALT).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(NONCE).also { SecureRandom().nextBytes(it) }
        out.write(MAGIC_CHUNKED.toByteArray(Charsets.US_ASCII))
        out.write(VERSION_CHUNKED)
        out.write(be32(ITERATIONS))
        out.write(salt)
        out.write(nonce)
        out.write(be32(CHUNK))
        return Sealing(out, key(password, salt, ITERATIONS), nonce)
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
        val magic = if (got == head.size) magicOf(head) else null
        if (magic == null) {
            // An ordinary zip: every byte read is put back.
            val back = PushbackInputStream(input, head.size)
            back.unread(head, 0, got)
            return Opened(BufferedInputStream(back), encrypted = false, drain = {})
        }
        if (magic == MAGIC_CHUNKED) return openChunked(input, password)
        // A whole-file seal from before 1.1.1.
        val version = input.read()
        if (version != VERSION) throw NeedsPassword()
        val rounds = readFully(input, 4).toInt32()
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
            while (stream.read(buf) >= 0) { /* read on */ }
        })
    }

    private fun openChunked(input: InputStream, password: CharArray?): Opened {
        val version = input.read()
        if (version != VERSION_CHUNKED) throw NeedsPassword()
        val rounds = readFully(input, 4).toInt32()
        if (rounds !in 1..MAX_ITERATIONS) throw NeedsPassword()
        val salt = readFully(input, SALT)
        val nonce = readFully(input, NONCE)
        val chunk = readFully(input, 4).toInt32()
        if (chunk !in MIN_CHUNK..MAX_CHUNK) throw NeedsPassword()
        val pw = password ?: throw NeedsPassword()
        val stream = BufferedInputStream(Opening(input, key(pw, salt, rounds), nonce, chunk))
        return Opened(stream, encrypted = true, drain = {
            // Every chunk is checked as it's read; reading on covers any the zip left behind.
            val buf = ByteArray(8 * 1024)
            while (stream.read(buf) >= 0) { /* read on */ }
        })
    }

    /** The zip's own bytes, sealed a chunk at a time, each under its own IV and its own tag. */
    private class Sealing(private val out: OutputStream, private val key: SecretKeySpec, private val nonce: ByteArray) : OutputStream() {
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private val buffer = ByteArray(CHUNK)
        private var filled = 0
        private var chunks = 0
        private var closed = false

        override fun write(b: Int) {
            buffer[filled++] = b.toByte()
            if (filled == CHUNK) chunk()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var at = off
            var left = len
            while (left > 0) {
                val n = minOf(left, CHUNK - filled)
                System.arraycopy(b, at, buffer, filled, n)
                filled += n
                at += n
                left -= n
                if (filled == CHUNK) chunk()
            }
        }

        private fun chunk() {
            if (filled == 0) return
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv(nonce, chunks)))
            out.write(cipher.doFinal(buffer, 0, filled))
            chunks++
            filled = 0
        }

        // Nothing to emit: an unfinished chunk waits for the rest of it (or for close).
        override fun flush() = out.flush()

        override fun close() {
            if (closed) return
            closed = true
            try {
                chunk()
                out.flush()
            } finally {
                out.close()
            }
        }
    }

    /** What [Sealing] wrote, opened a chunk at a time: one chunk's clear bytes in memory, never the file's. */
    private class Opening(
        private val input: InputStream,
        private val key: SecretKeySpec,
        private val nonce: ByteArray,
        private val chunk: Int,
    ) : InputStream() {
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private val sealed = ByteArray(chunk + TAG)
        private var clear = ByteArray(0)
        private var at = 0
        private var chunks = 0
        private var ended = false

        override fun read(): Int {
            if (!pump()) return -1
            return clear[at++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!pump()) return -1
            val n = minOf(len, clear.size - at)
            System.arraycopy(clear, at, b, off, n)
            at += n
            return n
        }

        /** Makes sure there's clear data to hand out; false at the end. */
        private fun pump(): Boolean {
            if (at < clear.size) return true
            if (ended) return false
            var got = 0
            while (got < sealed.size) {
                val n = input.read(sealed, got, sealed.size - got)
                if (n < 0) break
                got += n
            }
            if (got < TAG) {
                // Nothing left, or a chunk too short to hold its tag: cut short, or not one of ours.
                ended = true
                clear = ByteArray(0)
                at = 0
                if (got > 0) throw NeedsPassword()
                return false
            }
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv(nonce, chunks)))
            // A wrong password or a changed byte throws here (AEADBadTagException).
            clear = cipher.doFinal(sealed, 0, got)
            at = 0
            chunks++
            if (got < sealed.size) ended = true
            return true
        }

        override fun close() = input.close()
    }

    /** A chunk's IV: the file's random start, then the chunk's number. */
    private fun iv(nonce: ByteArray, n: Int): ByteArray = ByteArray(IV).also {
        System.arraycopy(nonce, 0, it, 0, NONCE)
        it[NONCE] = (n ushr 24).toByte()
        it[NONCE + 1] = (n ushr 16).toByte()
        it[NONCE + 2] = (n ushr 8).toByte()
        it[NONCE + 3] = n.toByte()
    }

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun ByteArray.toInt32(): Int =
        ((this[0].toInt() and 0xFF) shl 24) or ((this[1].toInt() and 0xFF) shl 16) or
            ((this[2].toInt() and 0xFF) shl 8) or (this[3].toInt() and 0xFF)

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
