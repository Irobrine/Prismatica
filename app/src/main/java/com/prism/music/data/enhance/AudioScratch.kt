package com.prism.music.data.enhance

import android.net.Uri
import com.prism.music.AppContainer
import com.prism.music.playback.sing.PcmDecoder
import com.prism.music.playback.sing.Resampler
import com.prism.music.playback.sing.StemStore
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.roundToInt

/**
 * The audio the engines work on, in scratch files rather than in memory: a three-minute stereo song is ~63 MB as
 * floats, and the separator needs its gigabyte of graph on top. Everything here is 16-bit PCM, little-endian.
 */
internal object AudioScratch {
    /**
     * Decodes [uri] to [channels] at [rate], writing [out] and reporting the frames written so far after every chunk: a
     * reader can start on the first piece while the rest of the song is still being decoded. Returns the frames written.
     */
    fun decode(
        uri: Uri,
        rate: Int,
        channels: Int,
        out: File,
        container: AppContainer,
        cancelled: () -> Boolean = { false },
        progress: (Long) -> Unit = {},
    ): Long {
        PcmDecoder(container.app, uri).use { dec ->
            val to = Resampler(2, dec.sampleRate, rate)
            var frames = 0L
            // Unbuffered on purpose: what [progress] says has been written must be readable at once.
            FileOutputStream(out).use { sink ->
                while (true) {
                    if (cancelled()) throw SeparationCancelled()
                    val piece = dec.read() ?: break
                    frames += writeFloats(to.push(piece), channels, sink)
                    progress(frames)
                }
                frames += writeFloats(to.finish(), channels, sink)
                progress(frames)
            }
            return frames
        }
    }

    /** Reads a separated stem ([store], [id]) and writes it at [rate] mono or stereo into [out]. */
    fun fromStem(store: StemStore, id: String, rate: Int, channels: Int, out: File, cancelled: () -> Boolean = { false }): Long {
        val reader = store.reader(id) ?: throw IllegalStateException("The separated audio isn't there")
        reader.use { r ->
            val resampler = Resampler(2, r.meta.rate, rate)
            BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { sink ->
                val frames = r.meta.totalFrames
                var at = 0L
                var written = 0L
                val buf = FloatArray(24_000 * 2)
                while (at < frames) {
                    if (cancelled()) throw SeparationCancelled()
                    val n = minOf(24_000L, frames - at).toInt()
                    r.read(at, n, buf)
                    val atRate = resampler.push(if (n * 2 == buf.size) buf.copyOf() else buf.copyOf(n * 2))
                    written += writeFloats(atRate, channels, sink)
                    at += n
                }
                written += writeFloats(resampler.finish(), channels, sink)
                return written
            }
        }
    }

    private fun writeFloats(samples: FloatArray, channels: Int, sink: java.io.OutputStream): Long {
        if (samples.isEmpty()) return 0
        val bytes = ByteArray(16 * 1024)
        var frames = 0L
        var i = 0
        val need = if (channels == 2) 4 else 2
        while (i < samples.size) {
            var n = 0
            while (i + 1 < samples.size && n + need <= bytes.size) {
                val left = samples[i]; val right = if (i + 1 < samples.size) samples[i + 1] else left
                i += 2
                val v = if (channels == 1) (left + right) * 0.5f else left
                val s = (v.coerceIn(-1f, 1f) * 32767f).roundToInt()
                bytes[n++] = s.toByte(); bytes[n++] = (s shr 8).toByte()
                if (channels == 2) {
                    val r = (right.coerceIn(-1f, 1f) * 32767f).roundToInt()
                    bytes[n++] = r.toByte(); bytes[n++] = (r shr 8).toByte()
                }
                frames++
            }
            sink.write(bytes, 0, n)
        }
        return frames
    }

    /** Pads [file] with silence (or cuts it) to exactly [frames] of [channels] at 16-bit. */
    fun fit(file: File, frames: Long, channels: Int) {
        val want = frames * channels * 2
        RandomAccessFile(file, "rw").use { raf ->
            if (raf.length() == want) return
            if (raf.length() > want) raf.setLength(want)
            else { raf.seek(raf.length()); raf.write(ByteArray((want - raf.length()).toInt())) }
        }
    }

    /** [file] as interleaved float frames, for the aligner: read straight into the array, not through a copy of the file. */
    fun readMono16k(file: File): FloatArray {
        val out = FloatArray((file.length() / 2).toInt())
        val buf = ByteArray(64 * 1024)
        var at = 0
        var carry = -1
        file.inputStream().use { input ->
            while (true) {
                var n = input.read(buf)
                if (n <= 0) break
                var i = 0
                if (carry >= 0) {
                    // A file whose length isn't even leaves half a sample between reads: finished with this one's first byte.
                    val hi = buf[0].toInt()
                    out[at++] = ((hi shl 8) or carry).toShort() / 32768f
                    carry = -1
                    i = 1
                    n--
                }
                while (i + 1 < n) {
                    val lo = buf[i].toInt() and 0xFF
                    val hi = buf[i + 1].toInt()
                    out[at++] = ((hi shl 8) or lo).toShort() / 32768f
                    i += 2
                }
                if (i < n) carry = buf[i].toInt() and 0xFF
            }
        }
        return if (at == out.size) out else out.copyOf(at)
    }

    /** The song's own bytes (file, download or whole-song cache) or a null when they aren't on the phone. */
    fun sourceUri(container: AppContainer, songId: String, scratch: File): Uri? =
        container.audioOnPhone(songId, songId, scratch)
}
