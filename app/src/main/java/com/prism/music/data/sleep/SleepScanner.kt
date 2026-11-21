package com.prism.music.data.sleep

import android.content.Context
import android.net.Uri
import com.prism.music.data.model.Song
import com.prism.music.data.stream.StreamResolver
import com.prism.music.playback.sing.PcmDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
     */
    suspend fun analyze(formId: String, sourceSongId: String?, network: Boolean): SleepScore? {
        val scratch = File(context.cacheDir, "sleep_${Integer.toHexString(formId.hashCode())}.part")
        return try {
            withContext(Dispatchers.IO) {
                runCatching { yieldToPlaying() }
                val uri = local(formId, sourceSongId ?: formId, scratch) ?: run {
                    if (!network || formId.startsWith(Song.LOCAL_PREFIX)) return@withContext null
                    fetch(formId, scratch)
                }
                val before = android.os.Process.getThreadPriority(android.os.Process.myTid())
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                try {
                    decodeAndAnalyze(uri)
                } finally {
                    android.os.Process.setThreadPriority(before)
                }
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

    private suspend fun decodeAndAnalyze(uri: Uri): SleepScore? {
        PcmDecoder(context, uri).use { dec ->
            val rate = dec.sampleRate
            if (rate <= 0) return null
            // The length the file says it has: a decode that stops short of it is judged with less confidence.
            val expectedMs = if (dec.totalFrames > 0) dec.totalFrames * 1000L / rate else 0L
            // At the decoder's own rate: resampling to a fixed one would cost more than the analysis itself, and the
            // features are all worked out in Hz, so 44.1 kHz, 48 kHz and 96 kHz files are judged alike.
            val analyzer = SleepAnalysis.Analyzer(rate, expectedMs)
            var fed = 0L
            val limit = MAX_SECONDS.toLong() * rate
            while (true) {
                currentCoroutineContext().ensureActive()
                val piece = dec.read() ?: break
                fed += downmix(piece, analyzer)
                if (fed >= limit) break
            }
            return analyzer.finish(System.currentTimeMillis())
        }
    }

    /** One piece's mono, reused between pieces (one scan runs at a time): about 0.5 s of audio. */
    private var mono = FloatArray(0)

    /** Downmixes an interleaved stereo piece to mono and feeds it to [analyzer]; returns the frames fed. */
    private fun downmix(interleaved: FloatArray, analyzer: SleepAnalysis.Analyzer): Int {
        if (interleaved.isEmpty()) return 0
        val frames = interleaved.size / 2
        if (mono.size < frames) mono = FloatArray(frames)
        for (i in 0 until frames) mono[i] = (interleaved[2 * i] + interleaved[2 * i + 1]) * 0.5f
        analyzer.feed(mono, 0, frames)
        return frames
    }

    companion object {
        /** Nothing past this is read (a very long recording is judged by its first fifteen minutes, like the scan). */
        private const val MAX_SECONDS = 15 * 60
    }
}
