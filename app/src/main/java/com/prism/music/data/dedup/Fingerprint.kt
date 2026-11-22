package com.prism.music.data.dedup

import com.prism.music.data.recognize.ShazamSignature
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A compact, persistable audio fingerprint for comparing two recordings of the same sound.
 *
 * It is derived from the phone's existing [ShazamSignature]: the signature's time/frequency peaks are turned into
 * constellation landmarks (a pair of peaks a short time apart). Each landmark is one 64-bit word: the top 24 bits
 * are a hash of (peak frequency 1, peak frequency 2, time gap) and the low 32 bits are the time of the first peak,
 * in 8 ms frames. Two recordings of the same master produce many landmarks with the same hash and the same time
 * difference; a live take, a remix or a cover does not. The time difference makes the comparison independent of
 * where a clip starts, so a video's intro/outro and dialogue only remove landmarks — they do not shift them.
 *
 * Nothing here talks to Shazam's service. [ShazamSignature.of] is an offline generator and this is its own,
 * local-only matching format (a new [VERSION] invalidates cached fingerprints).
 */
class AudioFingerprint(
    val version: Int,
    val durationMs: Long,
    val rateHz: Int,
    val landmarks: LongArray,
) {
    val size: Int get() = landmarks.size

    /** For tests and diagnostics: the same landmarks in the same order mean the same fingerprint. */
    fun sameAs(other: AudioFingerprint): Boolean =
        version == other.version && durationMs == other.durationMs && rateHz == other.rateHz &&
            landmarks.contentEquals(other.landmarks)

    /** Little-endian bytes: header (version, duration, rate, count) then one long per landmark. */
    fun encode(): ByteArray = ByteBuffer.allocate(HEADER + landmarks.size * 8).order(ByteOrder.LITTLE_ENDIAN).run {
        putInt(MAGIC)
        putInt(version)
        putLong(durationMs)
        putInt(rateHz)
        putInt(landmarks.size)
        landmarks.forEach(::putLong)
        array()
    }

    companion object {
        const val VERSION = 2
        private const val MAGIC = 0x50524446 // "PRDF"
        private const val HEADER = 24

        fun decode(bytes: ByteArray): AudioFingerprint {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(bb.capacity() >= HEADER && bb.getInt(0) == MAGIC) { "not a Prism audio fingerprint" }
            val version = bb.getInt(4)
            val duration = bb.getLong(8)
            val rate = bb.getInt(16)
            val count = bb.getInt(20)
            require(count >= 0 && HEADER + count * 8 <= bb.capacity()) { "truncated audio fingerprint" }
            val marks = LongArray(count) { bb.getLong(HEADER + it * 8) }
            return AudioFingerprint(version, duration, rate, marks)
        }
    }
}

/** Turns [ShazamSignature] peaks into the constellation landmarks [AudioFingerprint] stores. */
internal object Landmarks {
    /** 8 ms per Shazam frame; pairs 16–512 ms apart are local enough to survive an edit but far enough to be distinct. */
    private const val MIN_GAP = 2
    private const val MAX_GAP = 64

    /**
     * Frequency resolution: `correctedBin` is 64ths of a 7.8125 Hz FFT bin, so /256 rounds to ~31 Hz. That is narrow
     * enough to tell notes apart and wide enough to survive the small pitch drift of a different encoder or master.
     */
    private const val BIN_STEP = 256

    fun of(signature: ShazamSignature, limit: Int = MAX_LANDMARKS): LongArray {
        val generated = LongArray(WORKING_CAP)
        var n = 0
        for (peaks in signature.bands.values) {
            // The generator appends peaks in time order within a band.
            for (i in peaks.indices) {
                val a = peaks[i]
                val end = minOf(peaks.size, i + MAX_GAP + 1)
                for (j in (i + 1) until end) {
                    val b = peaks[j]
                    val gap = b.fftPassNumber - a.fftPassNumber
                    if (gap < MIN_GAP || gap > MAX_GAP) continue
                    val f1 = (a.correctedBin / BIN_STEP).coerceIn(0, 255)
                    val f2 = (b.correctedBin / BIN_STEP).coerceIn(0, 255)
                    val hash = (f1 shl 16) or (f2 shl 8) or gap
                    generated[n] = (hash.toLong() shl 32) or (a.fftPassNumber.toLong() and 0xffffffffL)
                    n++
                    if (n == WORKING_CAP) return spread(generated, limit)
                }
            }
        }
        return spread(generated, limit, n)
    }

    /** Keeps landmarks spread over the whole recording instead of only the first few seconds. */
    private fun spread(from: LongArray, limit: Int, have: Int = from.size): LongArray {
        val count = minOf(have, limit)
        if (count == 0) return LongArray(0)
        if (have == count) return from.copyOf(count)
        val stride = have.toDouble() / count
        return LongArray(count) { k -> from[minOf(have - 1, (k * stride).toInt())] }
    }

    fun hash(word: Long): Int = (word ushr 32).toInt()

    fun time(word: Long): Int = word.toInt()

    /**
     * A few thousand landmarks are plenty for offset-vote matching (a real copy still matches most of them), while an
     * unbounded fingerprint made a library's cache hundreds of megabytes and OOM'd the app at startup.
     */
    private const val MAX_LANDMARKS = 1_200
    private const val WORKING_CAP = 60_000
}
