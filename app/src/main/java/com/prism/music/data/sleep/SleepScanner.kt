package com.prism.music.data.sleep

import android.content.Context
import android.net.Uri
import com.prism.music.data.model.Song
import com.prism.music.data.stream.StreamResolver
import com.prism.music.playback.sing.PcmDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Reads a recording's audio and has [SleepAnalysis] judge it (1.9).
 *
 * The bytes come from where the scan's own audio does ([local], [com.prism.music.AppContainer.audioOnPhone]: a local
 * file, a finished download, a whole-song cache entry, a file matched to the song), else the smallest stream is fetched
 * whole and decoded. Audio is decoded at the file's own rate and analysed as it arrives, so a song never has to sit in
 * memory: a ten-minute recording costs the per-frame measurements (under a megabyte), not its samples. One recording is
 * scanned at a time by the walk that calls this ([com.prism.music.data.MeasureSongs]).
 *
 * Every failure (no audio on the phone and no network for it, a file that won't decode, a stopped walk) leaves the
 * recording without a figure, which the walk counts as walked but not scored; the next run takes it up again. Nothing is
 * cached here: the figures' own store ([SleepScores]) keeps what was measured, and its version gate decides what to redo.
 */
class SleepScanner(
    private val context: Context,
    private val http: OkHttpClient,
    /** The smallest stream of a recording not on the phone ([StreamResolver.analysisAudio]); only asked when [local] has nothing. */
    private val analysisStream: (id: String) -> StreamResolver.AnalysisStream = { throw IOException("No network audio source") },
    /** Where a form's audio is on the phone, if it is (may copy it to the file it's given), else null. */
    private val local: (id: String, songId: String, scratch: File) -> Uri? = { _, _, _ -> null },
    /** Blocks while the playing song is still loading, so a score calculation doesn't compete with it. */
    private val yieldToPlaying: () -> Unit = {},
) {
    /**
     * The recording's score, or null when its audio isn't reachable now (not on the phone, and [network] doesn't allow
     * fetching it). [formId] is the recording heard ([com.prism.music.playback.Loudness.formId]); [sourceSongId] is the
     * queue's song it plays for, when that's another id, where its audio may be found on the phone.
     *
     * [levelGainDb] is the normalized mode's gain: the one playback's Normalize applies to this recording, from its
     * measured loudness and peak ([com.prism.music.playback.NormalizerProcessor.gainFor]). The figure is then stored as
     * [SleepScore.normalized] = [normalized], so it only counts again in the mode it was made for.
     */
    suspend fun analyze(
        formId: String, sourceSongId: String?, network: Boolean, levelGainDb: Double = 0.0, normalized: Boolean = false,
        weights: SleepWeights = SleepWeights(), calibrationLabels: Int = 0,
    ): SleepScore? = withAudio(formId, sourceSongId, network) { uri ->
        decode(uri) { analyzer, now ->
            analyzer.finish(now, levelGainDb, weights, normalized, calibrationLabels).copy(normalized = normalized)
        }
    }

    /**
     * One decode and one set of spectra, two figures: the recording as it sounds and as Normalize plays it. The gain
     * only shifts absolute levels, so the second figure costs a second (cheap) scoring pass, not a second song scan.
     */
    suspend fun analyzeBoth(
        formId: String, sourceSongId: String?, network: Boolean, normalizedGainDb: Double,
        weights: SleepWeights = SleepWeights(), calibrationLabels: Int = 0,
    ): Pair<SleepScore, SleepScore>? = withAudio(formId, sourceSongId, network) { uri ->
        decode(uri) { analyzer, now ->
            val (plain, normalized) = analyzer.finishBoth(now, 0.0, normalizedGainDb, weights, calibrationLabels)
            plain.copy(normalized = false) to normalized.copy(normalized = true)
        }
    }

    /** Finds the bytes (or fetches them when allowed) and deletes the scratch file when the analysis is over. */
    private suspend fun <T> withAudio(
        formId: String, sourceSongId: String?, network: Boolean, block: suspend (Uri) -> T,
    ): T? {
        val scratch = File(context.cacheDir, "sleep_${Integer.toHexString(formId.hashCode())}.part")
        return try {
            withContext(Dispatchers.IO) {
                runCatching { yieldToPlaying() }
                val uri = local(formId, sourceSongId ?: formId, scratch) ?: run {
                    if (!network || formId.startsWith(Song.LOCAL_PREFIX)) return@withContext null
                    fetch(formId, scratch)
                }
                block(uri)
            }
        } finally {
            scratch.delete()
        }
    }

    private fun fetch(formId: String, into: File): Uri {
        val audio = analysisStream(formId)
        http.newCall(Request.Builder().url(audio.url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("audio returned HTTP ${r.code}")
            into.outputStream().use { out -> r.body.byteStream().copyTo(out) }
        }
        return Uri.fromFile(into)
    }

    /**
     * Decoding and analysis run side by side (1.9's speed work): the codec fills a small bounded queue on the IO pool
     * while the analysis turns the pieces into frames on the CPU pool. On a phone the two stages are of similar size, so
     * overlapping them takes the decode time out of the wall clock. Pieces are kept in order and the arithmetic per
     * frame is untouched, so the figure is the same as when both stages ran on one thread.
     */
    private suspend fun <T> decode(uri: Uri, finish: (SleepAnalysis.Analyzer, Long) -> T): T = coroutineScope {
        val decoder = PcmDecoder(context, uri)
        try {
            val rate = decoder.sampleRate
            require(rate > 0) { "the decoder gave no sample rate" }
            // The length the file says it has: a decode that stops short of it is judged with less confidence.
            val expectedMs = if (decoder.totalFrames > 0) decoder.totalFrames * 1000L / rate else 0L
            val pieces = Channel<FloatArray>(PIECES_IN_FLIGHT)
            val decoding = launch(Dispatchers.IO) {
                val before = android.os.Process.getThreadPriority(android.os.Process.myTid())
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                try {
                    decoder.use { dec ->
                        var fed = 0L
                        val limit = MAX_SECONDS.toLong() * rate
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val piece = dec.read() ?: break
                            pieces.send(downmix(piece))
                            fed += piece.size / 2L
                            if (fed >= limit) break
                        }
                    }
                } catch (t: Throwable) {
                    // A failed or stopped decode must not be scored from the pieces that arrived before it.
                    pieces.close(t)
                    throw t
                } finally {
                    pieces.close()
                    android.os.Process.setThreadPriority(before)
                }
            }
            val analyzing = async(Dispatchers.Default) {
                // At the decoder's own rate: resampling to a fixed one would cost more than the analysis itself, and the
                // features are all worked out in Hz, so 44.1 kHz, 48 kHz and 96 kHz files are judged alike.
                val analyzer = SleepAnalysis.Analyzer(rate, expectedMs)
                for (piece in pieces) {
                    currentCoroutineContext().ensureActive()
                    analyzer.feed(piece)
                }
                finish(analyzer, System.currentTimeMillis())
            }
            val result = analyzing.await()
            decoding.join()
            result
        } finally {
            runCatching { decoder.close() }
        }
    }

    /** Downmixes an interleaved stereo piece to mono. Each piece owns its buffer, so two scans can run at once. */
    private fun downmix(interleaved: FloatArray): FloatArray {
        if (interleaved.isEmpty()) return FloatArray(0)
        val frames = interleaved.size / 2
        val mono = FloatArray(frames)
        for (i in 0 until frames) mono[i] = (interleaved[2 * i] + interleaved[2 * i + 1]) * 0.5f
        return mono
    }

    companion object {
        /** Nothing past this is read (a very long recording is judged by its first fifteen minutes, like the scan). */
        private const val MAX_SECONDS = 15 * 60

        /** Pieces in flight while decoding: ~3 seconds of audio, small enough for a phone and for two scans at once. */
        private const val PIECES_IN_FLIGHT = 6


    }
}
