package com.prism.music.playback

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.max

/**
 * DJ mode's quick look at a song: its key, tempo and loudness from three 25-second stretches (a quarter, half and three
 * quarters of the way in) instead of the whole song. Only those stretches are fetched (by range, through [RangedSource])
 * and decoded, about a third of the work, so DJ mode can order a queue in seconds rather than minutes. Crossfade's and
 * Normalize's own scans still read the whole song (their edges and exact loudness need it).
 */
object QuickScan {
    /** The stretches to hear, as (start, length) in ms: three in the body of the song, or a short song whole. */
    fun windows(durationMs: Long): List<Pair<Long, Long>> =
        if (durationMs < WHOLE_BELOW_MS) listOf(0L to durationMs.coerceAtLeast(1))
        else listOf(0.25, 0.5, 0.75).map { f -> ((durationMs * f).toLong() - WINDOW_MS / 2).coerceAtLeast(0) to WINDOW_MS }

    /** [brightness]: [Energy.brightness] over the stretches (1.8.3), from the same band features as the tempo. */
    class Result(val key: MusicKey?, val tempo: Tempo?, val lufs: Float?, val brightness: Double? = null)

    /** The key from all the stretches together, the tempo from the one with the clearest beat, the loudness over all. */
    fun analyse(pieces: List<FloatArray>, rate: Int): Result {
        val all = FloatArray(pieces.sumOf { it.size }).also { out -> var at = 0; pieces.forEach { p -> p.copyInto(out, at); at += p.size } }
        val key = runCatching { KeyDetector.detect(all, rate) }.getOrNull()
        val frameMs = (rate / 100) * 1000.0 / rate
        val bands = pieces.map { p -> runCatching { VideoSync.bandFeatures(p, rate) }.getOrNull() }
        val tempo = bands.mapNotNull { b -> b?.let { runCatching { Tempo.analyze(it, frameMs) }.getOrNull() } }
            .maxByOrNull { it.confidence }
        val brightness = bands.mapNotNull { b -> b?.let(Energy::brightness) }.takeIf { it.isNotEmpty() }?.average()
        val lufs = MixTempo.integratedLoudness(all, rate)?.let { it + MONO_TO_STEREO_DB }
        return Result(key, tempo, lufs, brightness)
    }

    /**
     * Decodes [windows] of the extractor's selected audio track [track] to mono at about 11 kHz, one piece per window.
     * Null when it can't be decoded.
     */
    fun decode(ex: MediaExtractor, track: Int, windows: List<Pair<Long, Long>>): Pair<List<FloatArray>, Int>? {
        val fmt = ex.getTrackFormat(track)
        val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME) ?: return null)
        try {
            dec.configure(fmt, null, null, 0)
            dec.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            var factor = max(1, rate / 11025)
            val pieces = ArrayList<FloatArray>()
            val mono = Downmix()
            val info = MediaCodec.BufferInfo()
            for ((startMs, lengthMs) in windows) {
                val from = startMs * 1000
                val to = (startMs + lengthMs) * 1000
                ex.seekTo(from, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                dec.flush()
                var out = FloatArray(((lengthMs / 1000.0 + 1) * rate / factor).toInt().coerceAtLeast(1024))
                var count = 0
                mono.restart()
                var inputDone = false
                var idle = 0
                while (true) {
                    if (!inputDone) {
                        val i = dec.dequeueInputBuffer(5_000)
                        if (i >= 0) {
                            val size = runCatching { ex.readSampleData(dec.getInputBuffer(i)!!, 0) }.getOrDefault(-1)
                            if (size < 0 || ex.sampleTime > to + 250_000) {
                                dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                dec.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    MixTempo.ease()
                    val o = dec.dequeueOutputBuffer(info, 5_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = dec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        if (pieces.isEmpty() && count == 0) factor = max(1, rate / 11025)
                    } else if (o >= 0) {
                        if (info.presentationTimeUs in from until to && info.size > 0) {
                            val buf = dec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val frames = info.size / (channels * (if (isFloat) 4 else 2))
                            mono.add(buf, frames, channels, isFloat, factor) { v ->
                                if (count == out.size) out = out.copyOf(out.size * 2)
                                out[count++] = v
                            }
                        }
                        dec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    } else if (inputDone && o == MediaCodec.INFO_TRY_AGAIN_LATER && ++idle > 200) {
                        break
                    }
                }
                if (count > 0) pieces += out.copyOf(count)
            }
            val sr = rate / factor
            return if (pieces.sumOf { it.size } >= sr * 10) pieces to sr else null
        } finally {
            runCatching { dec.stop() }
            runCatching { dec.release() }
        }
    }

    private const val WINDOW_MS = 25_000L
    /** A song shorter than this is heard whole. */
    private const val WHOLE_BELOW_MS = 100_000L
    /** As [MixTempo]'s full scan: mono reads about 3 dB under the two channels counted apart. */
    private const val MONO_TO_STEREO_DB = 3.0f
}

/**
 * A stream's bytes on demand, in [BLOCK]-sized pieces fetched with range requests (kept, the last few), so a decoder
 * that seeks to a few stretches of a song downloads only those, not the whole file.
 *
 * [prefetch] fetches the block after the one being read while the decoder works on this one (1.9: a whole song's scan
 * reads them in order, so the network and the decode overlap instead of taking turns). One block ahead at most, so a
 * seek elsewhere costs nothing but a stale block.
 */
class RangedSource(private val http: OkHttpClient, private val url: String, private val length: Long, private val prefetch: Boolean = false) : MediaDataSource() {
    private val blocks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 12
    }
    /** Bytes downloaded so far. */
    private val bytes = java.util.concurrent.atomic.AtomicLong()
    val fetched: Long get() = bytes.get()
    /** A read the decoder asked for that failed, if one did (a block fetched ahead and never wanted isn't one). */
    @Volatile var failure: IOException? = null
        private set

    /** The block after the one being read, on its own thread ([prefetch]); one at a time, so nothing piles up. */
    private val ahead = if (prefetch) java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "prism-range").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    } else null
    private var next: Pair<Long, java.util.concurrent.Future<ByteArray>>? = null
    /** The whole file, when the server answered a range request with it ([fetch]): every block comes out of here after. */
    @Volatile private var whole: ByteArray? = null

    override fun getSize(): Long = length

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= length || size <= 0) return -1
        val index = position / BLOCK
        val block = blockAt(index)
        val inBlock = (position - index * BLOCK).toInt()
        val n = minOf(size, block.size - inBlock)
        if (n <= 0) return -1
        System.arraycopy(block, inBlock, buffer, offset, n)
        return n
    }

    /** Block [index], from the kept ones, the one being fetched ahead, or a fetch now; and the next one set going. */
    private fun blockAt(index: Long): ByteArray {
        synchronized(blocks) { blocks[index] }?.let { return it }
        val awaited = synchronized(this) { next?.takeIf { it.first == index }?.also { next = null } }
        val block = try {
            awaited?.second?.get() ?: fetch(index)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw failed((e.cause as? IOException) ?: IOException("a stream read failed: ${e.cause}"))
        } catch (e: IOException) {
            throw failed(e)
        }
        synchronized(blocks) { blocks[index] = block }
        schedule(index + 1)
        return block
    }

    /**
     * [e], noted as a read the decoder asked for and didn't get: the extractor takes a failed read for the song's end,
     * so the caller checks [failure] once the decoding is over. A block fetched ahead and never wanted isn't this.
     */
    private fun failed(e: IOException): IOException { failure = e; return e }

    /** Sets the block after [index] going ([prefetch]): it's the one the decoder will want next, in a whole song. */
    private fun schedule(index: Long) {
        val pool = ahead ?: return
        synchronized(this) {
            val pending = next
            // Past the end there's nothing to want: a block set going for before a seek isn't kept either.
            if (index * BLOCK >= length) {
                pending?.second?.cancel(true)
                next = null
                return
            }
            if (pending?.first == index) return
            pending?.second?.cancel(true)
            next = index to pool.submit(java.util.concurrent.Callable { fetch(index) })
        }
    }

    /**
     * Block [index]'s bytes. A range request's body is only trusted when the answer is a `206` for exactly the bytes
     * asked for; a server that ignores the range and sends the whole file (a `200`) is answered from that whole file
     * from then on, which is what the old whole-file download effectively did. Silence there would file another
     * recording's audio as this one's: block n's bytes read from offset 0 of the file, with nothing to show for it.
     */
    private fun fetch(index: Long): ByteArray {
        whole?.let { return slice(it, index) }
        val from = index * BLOCK
        val to = minOf(length, from + BLOCK) - 1
        val asked = to - from + 1
        http.newCall(Request.Builder().url(url).header("Range", "bytes=$from-$to").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("audio returned HTTP ${r.code}")
            val body = r.body.bytes()
            bytes.addAndGet(body.size.toLong())
            if (r.code == 206) {
                // Where the server says these bytes start: a block that isn't the one asked for would decode as noise.
                val start = r.header("Content-Range")?.substringAfter(' ')?.substringBefore('-')?.toLongOrNull()
                if (start != null && start != from) throw IOException("audio sent bytes $start- for a block at $from")
                if (body.size > asked) throw IOException("audio sent ${body.size} bytes for a $asked-byte block")
                return body
            }
            // 200: the range was ignored and this is the whole file. Kept, so every later block comes out of it.
            if (body.size.toLong() < length) throw IOException("audio sent ${body.size} bytes of $length with no range")
            whole = body
            return slice(body, index)
        }
    }

    /** [file]'s block [index]: its 512 KiB, or what's left of the song. */
    private fun slice(file: ByteArray, index: Long): ByteArray {
        val from = index * BLOCK
        if (from >= file.size) return ByteArray(0)
        val to = minOf(file.size.toLong(), from + BLOCK)
        return file.copyOfRange(from.toInt(), to.toInt())
    }

    override fun close() {
        ahead?.shutdownNow()
        synchronized(this) { next = null }
        synchronized(blocks) { blocks.clear() }
        whole = null
    }

    private companion object { const val BLOCK = 512L * 1024 }
}
