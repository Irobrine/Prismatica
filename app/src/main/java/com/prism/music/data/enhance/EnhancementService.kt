package com.prism.music.data.enhance

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.prism.music.AppContainer
import com.prism.music.R
import com.prism.music.data.ModelStore
import com.prism.music.data.lyrics.LyricLine
import com.prism.music.data.lyrics.Lyrics
import com.prism.music.data.model.Song
import com.prism.music.playback.sing.OrtLoader
import com.prism.music.playback.sing.Resampler
import com.prism.music.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** What a manual enhancement needs and doesn't have. The UI offers the download, never starts it. */
class EnhanceMissingModel(val model: EnhanceModel?, val runtime: Boolean) : IOException(
    when {
        runtime && model != null -> tr(R.string.enhance_needs_runtime_and, model.title)
        runtime -> tr(R.string.enhance_needs_runtime)
        model != null -> tr(R.string.enhance_needs_model, model.title)
        else -> tr(R.string.enhance_needs_something)
    },
)

class EnhanceNoAudio : IOException(tr(R.string.enhance_song_isn_t_on_the_phone))
class EnhanceNoLyrics : IOException(tr(R.string.enhance_no_lyrics))
class EnhanceUnsupportedLanguage : IOException(tr(R.string.enhance_unsupported_language))

/** The three operations are separate: a listener can sync words without separating, or make a karaoke track without them. */
enum class EnhanceKind { LYRICS, VOCALS, INSTRUMENTAL, EXTERNAL }

data class EnhanceOptions(
    /** Use the separated vocals as the aligner's input when they're there (cleaner than the mix). */
    val useVocals: Boolean = true,
    /** Re-run even when a valid result is cached (the manual action always re-runs; batch honors this). */
    val force: Boolean = true,
)

/**
 * Runs lyrics alignment and vocal separation off the main thread, one heavy job at a time, and keeps a queue that
 * survives a restart. Both engines are optional downloads; a job that finds one missing fails with
 * [EnhanceMissingModel] before decoding anything, and the UI offers the download. Nothing here touches the original
 * audio, the original lyrics, or an external instrumental.
 */
@UnstableApi
class EnhancementService(private val c: AppContainer, val store: EnhanceStore) {
    val models = EnhanceModelStore(c.app, c.http)

    private val json = Json { ignoreUnknownKeys = true }
    private val jobsFile = File(c.app.filesDir, "lyrics-enhance/jobs.json")
    /**
     * One background thread for the heavy work, at Android's background priority: the engines are hundreds of times
     * slower than the player, and a battery saver's processor should give the music the cores first.
     */
    private val heavy = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "PrismEnhance").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + heavy)
    /** Wakes the one worker: a burst of enqueues is a single wake, and nothing is lost between jobs. */
    private val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    /** Writes the queue's file off the caller's thread (a big batch is a multi-megabyte write). */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobsDirty = java.util.concurrent.atomic.AtomicBoolean(false)

    private val _tasks = MutableStateFlow<List<EnhanceTask>>(emptyList())
    val tasks: StateFlow<List<EnhanceTask>> = _tasks

    /**
     * The two engines, kept between songs instead of rebuilt for each one: loading the aligner costs ~9 s and the
     * separator ~5 s on a phone, which for a batch of songs is most of the time it isn't inferring. The slots are
     * emptied when the queue has been idle for a while ([unloadEnginesWhenIdle]) and after a run whose session can't be
     * trusted to carry on ([EnhanceKind] tells which engine a job used).
     */
    private val aligners = EngineSlot {
        QwenAligner.load(
            OrtLoader.environment(runtimeDir()), alignerDir(),
            threads = minOf(3, Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1),
        )
    }
    private val separators = EngineSlot {
        HtDemucs.load(
            OrtLoader.environment(runtimeDir()), separatorDir(),
            threads = minOf(4, Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1),
        )
    }

    private val cancelled = java.util.concurrent.ConcurrentHashMap<String, AtomicBoolean>()

    init {
        scope.launch(Dispatchers.IO) {
            // Scratch audio from a run the process didn't finish is never read again; the results (and a model's .part
            // download, which is resumable) are elsewhere.
            c.app.cacheDir.listFiles { f -> f.name.startsWith("enhance_") }?.forEach { it.delete() }
            loadJobs()
        }
        // One worker for the whole queue: it waits here, takes a job at a time, and goes back to waiting. A job queued
        // while the last one is finishing can't be missed, as it could when each walk had its own short-lived worker.
        scope.launch {
            for (signal in wake) {
                try {
                    drainQueue()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Nothing here should throw (a job's own failures are its own), but a walk that dies mustn't take
                    // the worker with it: the next enqueue still has to be picked up.
                    Log.w("PrismEnhance", "the queue's walk stopped: $e")
                }
            }
        }
    }

    // ------------------------------------------------------------------ readiness and downloads

    fun runtimeReady(): Boolean = ModelStore.Model.singRuntime()?.let { c.models.ready(it) } == true

    fun alignerReady(): Boolean = runtimeReady() && models.ready(EnhanceModel.ALIGNER)
    fun separatorReady(): Boolean = runtimeReady() && models.ready(EnhanceModel.SEPARATOR)

    private fun runtimeDir(): File {
        val runtime = ModelStore.Model.singRuntime()
        if (runtime == null || !c.models.ready(runtime)) throw EnhanceMissingModel(null, runtime = true)
        return c.models.dir(runtime)
    }

    private fun alignerDir(): File {
        if (!models.ready(EnhanceModel.ALIGNER)) throw EnhanceMissingModel(EnhanceModel.ALIGNER, runtime = false)
        return models.dir(EnhanceModel.ALIGNER)
    }

    private fun separatorDir(): File {
        if (!models.ready(EnhanceModel.SEPARATOR)) throw EnhanceMissingModel(EnhanceModel.SEPARATOR, runtime = false)
        return models.dir(EnhanceModel.SEPARATOR)
    }

    /** Downloads the ONNX engine too when it isn't here; returns true when both the engine and [model] are ready. */
    suspend fun download(model: EnhanceModel, onProgress: ((Float) -> Unit)? = null): Boolean {
        val runtime = ModelStore.Model.singRuntime()
        if (runtime != null && !c.models.ready(runtime)) c.models.download(runtime)
        return models.download(model, onProgress)
    }

    fun delete(model: EnhanceModel) {
        models.delete(model)
        // Generated lyrics and audio are deliberately untouched: removing a model frees the model, not the listener's work.
        // Its session goes too, after any job still running on it: a job in the next moments mustn't be served by a graph
        // whose files are gone (the record it writes would carry the new model's version).
        scope.launch {
            when (model) {
                EnhanceModel.ALIGNER -> aligners.drop()
                EnhanceModel.SEPARATOR -> separators.drop()
            }
        }
    }

    /**
     * The overlay the lyrics repository asks for on every load. Only a result made from these exact lyrics and this
     * recording, with the model version that is installed now (or with no model installed at all: removing a model never
     * deletes or hides what it made), is applied. A source's own words are never changed when the record doesn't fit.
     */
    fun overlay(song: Song, lyrics: Lyrics): Lyrics {
        val record = store.alignment(song.id) ?: return lyrics
        // The listener may have turned this song's generated words off: the source's own timings are shown instead.
        if (!store.lyricsEnabled(song.id)) return lyrics
        if (record.lyricsHash != EnhanceKeys.lyricsHash(lyrics)) return lyrics
        if (record.audioKey != EnhanceKeys.audioKey(song)) return lyrics
        // The fingerprint is checked when one has been measured for this song; before the first enhancement there is none.
        val known = store.currentFingerprint(song.id)
        if (known != null && record.audioFingerprint.isNotEmpty() && known != record.audioFingerprint) return lyrics
        val installed = models.versionOnPhone(EnhanceModel.ALIGNER)
        if (installed != null && installed != record.modelVersion) return lyrics
        return store.apply(lyrics, record)
    }

    // ------------------------------------------------------------------ public operations

    /**
     * Generates word timings for [song]'s current lyrics. The source lyrics are read from the phone (or fetched from the
     * listener's lyric sources when none is saved), never modified; the result is an overlay keyed by their exact hash.
     */
    suspend fun syncLyrics(song: Song, options: EnhanceOptions = EnhanceOptions(), report: (Float) -> Unit = {}): AlignmentRecord = withContext(heavy) {
        val lyrics = c.lyrics.enhancementSource(song) ?: throw EnhanceNoLyrics()
        val nonGap = lyrics.lines.filter { !it.isGap && it.text.isNotBlank() }
        if (nonGap.isEmpty()) throw EnhanceNoLyrics()
        val language = dominantLanguage(nonGap) ?: throw EnhanceUnsupportedLanguage()
        val lyricsHash = EnhanceKeys.lyricsHash(lyrics)
        val audioKey = EnhanceKeys.audioKey(song)
        val settingsKey = EnhanceKeys.settingsKey(options.useVocals, language.name)
        if (!options.force) store.alignment(song.id)?.takeIf {
            val known = store.currentFingerprint(song.id)
            it.lyricsHash == lyricsHash && it.audioKey == audioKey && it.modelVersion == EnhanceModel.ALIGNER.version &&
                it.settingsKey == settingsKey && (known == null || it.audioFingerprint.isEmpty() || it.audioFingerprint == known)
        }?.let { return@withContext it }
        // Missing models are told before the song is fetched or decoded.
        runtimeDir(); alignerDir()
        val cancel = cancellation(song.id, EnhanceKind.LYRICS)
        val scratch = File(c.app.cacheDir, "enhance_align_${safe(song.id)}.part")
        val alignScratch = File(c.app.cacheDir, "enhance_align_${safe(song.id)}.pcm")
        try {
            val uri = ensureAudio(song)
            val fingerprint = fingerprintOf(uri)
            if (fingerprint.isNotEmpty()) store.putCurrentFingerprint(song.id, fingerprint)
            val useVocals = options.useVocals && store.hasVocals(song.id)
            if (useVocals) AudioScratch.fromStem(store.vocals, song.id, 16_000, 1, alignScratch) { cancel.get() }
            else if (!adoptPrefetched(song.id, stereo44 = false, source = uri.toString(), target = alignScratch)) {
                AudioScratch.decode(uri, 16_000, 1, alignScratch, c) { cancel.get() }
            }
            if (cancel.get()) throw CancellationException("cancelled")
            val audio = AudioScratch.readMono16k(alignScratch)
            if (audio.isEmpty()) throw EnhanceNoAudio()
            val record = withContext(heavy) {
                alignLyrics(lyrics, lyricsHash, audio, language, audioKey, fingerprint, useVocals, settingsKey, song, cancel, report)
            }
            store.putAlignment(record)
            c.lyrics.overlayChanged()
            Log.i("PrismEnhance", "aligned ${song.id}: ${record.lines.sumOf { it.words.size }} words, score ${"%.2f".format(record.quality.score)}, reliable ${record.quality.reliable}")
            record
        } finally {
            alignScratch.delete()
            scratch.delete()
        }
    }

    /**
     * Separates [song]'s voice from its backing (HTDemucs, one 7.8 s piece at a time). The vocal stem and the
     * instrumental (the sum of drums, bass and other) are stored separately. The original audio is only read.
     */
    suspend fun separateVocals(song: Song, options: EnhanceOptions = EnhanceOptions(), report: (Float) -> Unit = {}): SeparationRecord = withContext(heavy) {
        if (!options.force) store.separation(song.id)?.takeIf {
            val known = store.currentFingerprint(song.id)
            store.hasVocals(song.id) && it.audioKey == EnhanceKeys.audioKey(song) && it.modelVersion == EnhanceModel.SEPARATOR.version &&
                (known == null || it.audioFingerprint.isEmpty() || it.audioFingerprint == known)
        }?.let { return@withContext it }
        separateInternal(song, wantInstrumental = false, report)
    }

    /** The local instrumental on its own. It runs the separator when the song's stems aren't there yet; it never replaces
     *  an external instrumental, and it never changes which one Sing plays. */
    suspend fun makeInstrumental(song: Song, options: EnhanceOptions = EnhanceOptions(), report: (Float) -> Unit = {}): SeparationRecord = withContext(heavy) {
        if (!options.force) store.generatedInstrumental(song.id)?.takeIf {
            val known = store.currentFingerprint(song.id)
            store.hasGeneratedInstrumental(song.id) && it.audioKey == EnhanceKeys.audioKey(song) && it.modelVersion == EnhanceModel.SEPARATOR.version &&
                (known == null || it.audioFingerprint.isEmpty() || it.audioFingerprint == known)
        }?.let { return@withContext it }
        separateInternal(song, wantInstrumental = true, report)
    }

    private suspend fun separateInternal(song: Song, wantInstrumental: Boolean, report: (Float) -> Unit = {}): SeparationRecord {
        val cancel = cancellation(song.id, if (wantInstrumental) EnhanceKind.INSTRUMENTAL else EnhanceKind.VOCALS)
        val src44 = File(c.app.cacheDir, "enhance_sep_${safe(song.id)}.src.pcm")
        val vTmp = File(c.app.cacheDir, "enhance_sep_${safe(song.id)}.voc.pcm")
        val iTmp = File(c.app.cacheDir, "enhance_sep_${safe(song.id)}.inst.pcm")
        // Both models are told missing before anything is decoded (the engine is started by the slots themselves).
        separatorDir(); runtimeDir()
        // Stopping the job must stop the decode too, so the gate is kept here where the finally can reach it.
        val gate = DecodeGate()
        return try {
            val uri = ensureAudio(song)
            val fingerprint = fingerprintOf(uri)
            if (fingerprint.isNotEmpty()) {
                // The recording changed (a new download, another master): the old stems would be out of step with it.
                val old = store.separation(song.id)?.audioFingerprint.orEmpty()
                if (old.isNotEmpty() && old != fingerprint) store.deleteGeneratedAudio(song.id)
                store.putCurrentFingerprint(song.id, fingerprint)
            }
            val songRate = probeRate(uri)
            // Decoding a whole song is a stretch of the job with nothing to count yet: said as its own small step, so the
            // bar leaves zero while it happens.
            report(0.02f)
            val expected = if (song.durationSec > 0) song.durationSec.toLong() * HtDemucs.RATE else 0L
            // The song is decoded on its own thread while the separator works: the first piece waits for its own bytes
            // rather than for the whole song. A prefetched decode is already whole, so it needs no gate.
            if (adoptPrefetched(song.id, stereo44 = true, source = uri.toString(), target = src44)) {
                gate.publish(src44.length() / 4)
                gate.done = true
            } else if (expected > 0) {
                startDecode(uri, src44, gate, cancel)
            } else {
                // No length to run ahead of: decode it whole, as before.
                gate.publish(AudioScratch.decode(uri, HtDemucs.RATE, 2, src44, c) { cancel.get() })
                gate.done = true
            }
            val total44 = if (expected > 0) expected else gate.frames
            if (total44 <= 0) throw EnhanceNoAudio()
            checkMemory()
            report(0.05f)
            val inferStart = android.os.SystemClock.elapsedRealtime()
            val demucs = acquireSeparator()
            val vResample = Resampler(2, HtDemucs.RATE, songRate)
            val iResample = Resampler(2, HtDemucs.RATE, songRate)
            java.io.BufferedOutputStream(java.io.FileOutputStream(vTmp), 1 shl 16).use { vOut ->
                java.io.BufferedOutputStream(java.io.FileOutputStream(iTmp), 1 shl 16).use { iOut ->
                    demucs.separate(src44, total44, { cancel.get() }, { f ->
                        report(0.05f + f * 0.9f)
                        // Between pieces, as Sing does: out of sight with nothing playing, the run waits rather
                        // than burning the cores of an idle phone (BackgroundBudget).
                        com.prism.music.playback.BackgroundBudget.pace { !cancel.get() }
                    }, waitFor = { need -> gate.await(need, cancel) }) { vocals, instrumental ->
                        writeSamples(vResample.push(vocals), 2, vOut)
                        writeSamples(iResample.push(instrumental), 2, iOut)
                    }
                    writeSamples(vResample.finish(), 2, vOut)
                    writeSamples(iResample.finish(), 2, iOut)
                }
            }
            // The decode's own count once it's finished: the piece loop may have run on the declared length.
            val real44 = gate.frames.takeIf { it > 0 } ?: total44
            val songFrames = if (songRate == HtDemucs.RATE) real44 else (real44.toDouble() * songRate / HtDemucs.RATE).roundToLong()
            AudioScratch.fit(vTmp, songFrames, 2)
            AudioScratch.fit(iTmp, songFrames, 2)
            store.vocals.delete(song.id); store.generated.delete(song.id)
            if (!vTmp.renameTo(store.vocals.pcm(song.id))) { vTmp.copyTo(store.vocals.pcm(song.id), overwrite = true); vTmp.delete() }
            if (!iTmp.renameTo(store.generated.pcm(song.id))) { iTmp.copyTo(store.generated.pcm(song.id), overwrite = true); iTmp.delete() }
            store.vocals.writeMeta(song.id, com.prism.music.playback.sing.StemStore.Meta(songRate, songFrames, complete = true, method = "HTDEMUCS"))
            store.generated.writeMeta(song.id, com.prism.music.playback.sing.StemStore.Meta(songRate, songFrames, complete = true, method = "HTDEMUCS"))
            val record = SeparationRecord(
                songId = song.id, audioKey = EnhanceKeys.audioKey(song), audioFingerprint = fingerprint,
                modelVersion = EnhanceModel.SEPARATOR.version, createdAt = System.currentTimeMillis(),
                rate = songRate, frames = songFrames, vocalsFrames = songFrames, instrumentalFrames = songFrames,
            )
            store.putSeparation(record)
            store.putGeneratedInstrumental(record)
            // Kept to the listener's room, and what's still to play, to generate or to align is never taken: an
            // instrumental Sing is playing or the queue is about to use must survive the trim (0 = no limit).
            val room = c.settings.current.enhanceAudioMb.toLong() * 1_048_576L
            store.trim(room, keepForTrim(song.id))
            report(1f)
            val inferMs = android.os.SystemClock.elapsedRealtime() - inferStart
            val audioSec = total44 / HtDemucs.RATE.toDouble()
            Log.i(
                "PrismEnhance",
                "separated ${song.id}: ${"%.1f".format(audioSec)} s of audio in $inferMs ms " +
                    "(${"%.2f".format(audioSec / (inferMs / 1000.0))}× realtime, ${HtDemucs.threadsUsed()} threads), " +
                    "(${songRate} Hz, ${songFrames} frames)",
            )
            record
        } finally {
            gate.abandoned = true
            src44.delete(); vTmp.delete(); iTmp.delete()
            File(c.app.cacheDir, "enhance_src_${safe(song.id)}.part").delete()
        }
    }

    /**
     * Copies a listener's instrumental into Prism's own 16-bit PCM (at the song's rate) and keeps the original file
     * exactly as it is. It is tracked as EXTERNAL, separately from the one the separator makes.
     */
    suspend fun addExternalInstrumental(song: Song, uri: Uri, name: String): ExternalInstrumentalRecord = withContext(heavy) {
        val cancel = cancellation(song.id, EnhanceKind.EXTERNAL)
        val tmp = File(c.app.cacheDir, "enhance_ext_${safe(song.id)}.part.pcm")
        try {
            val songUri = ensureAudio(song)
            val rate = probeRate(songUri)
            val frames = AudioScratch.decode(uri, rate, 2, tmp, c) { cancel.get() }
            if (frames <= 0) throw EnhanceNoAudio()
            // The song's own length is the right length for the copy; a shorter picked file is padded, a longer one cut.
            val want = if (song.durationSec > 0) song.durationSec.toLong() * rate else frames
            AudioScratch.fit(tmp, want, 2)
            store.external.delete(song.id)
            if (!tmp.renameTo(store.external.pcm(song.id))) { tmp.copyTo(store.external.pcm(song.id), overwrite = true); tmp.delete() }
            store.external.writeMeta(song.id, com.prism.music.playback.sing.StemStore.Meta(rate, want, complete = true, method = "EXTERNAL"))
            val record = ExternalInstrumentalRecord(
                songId = song.id, name = name, sourceKey = uri.toString(),
                sourceFingerprint = runCatching { uri.path?.let { File(it) }?.let(EnhanceKeys::fingerprint) }.getOrNull().orEmpty(),
                importedAt = System.currentTimeMillis(), rate = rate, frames = want,
            )
            store.putExternalInstrumental(record)
            record
        } finally {
            tmp.delete()
            File(c.app.cacheDir, "enhance_src_${safe(song.id)}.part").delete()
        }
    }

    // ------------------------------------------------------------------ the aligner

    private fun alignLyrics(
        lyrics: Lyrics,
        lyricsHash: String,
        audio: FloatArray,
        language: AlignLanguage,
        audioKey: String,
        fingerprint: String,
        usedVocals: Boolean,
        settingsKey: String,
        song: Song,
        cancel: AtomicBoolean,
        report: (Float) -> Unit = {},
    ): AlignmentRecord {
        val durationMs = if (song.durationSec > 0) song.durationSec * 1000L else audio.size.toLong() * 1000 / 16_000
        val plans = ChunkPlanner.plans(lyrics.lines, durationMs, language)
        if (plans.isEmpty()) throw EnhanceNoLyrics()
        // Told missing before the session is built (its own factory checks too).
        runtimeDir(); alignerDir()
        checkMemory()
        val aligner = acquireAligner()
        val allAligned = ArrayList<AlignedUnit>()
        var confidenceSum = 0f
        var confidenceWeight = 0
        // A song that fits one piece must still say the model has it: a bar that waits at 0 until a four-minute forward
        // pass ends isn't progress.
        report(0.05f)
        for ((i, plan) in plans.withIndex()) {
            if (cancel.get()) throw CancellationException("cancelled")
            if (plan.units.isEmpty()) continue
            report(0.05f + 0.9f * i / plans.size)
            val from = plan.fromMs.coerceIn(0, durationMs)
            val to = plan.toMs.coerceIn(from, durationMs)
            val fromSample = (from * 16).toInt().coerceIn(0, audio.size)
            val toSample = (to * 16).toInt().coerceIn(fromSample, audio.size)
            val slice = if (toSample > fromSample) audio.copyOfRange(fromSample, toSample) else FloatArray(0)
            val result = aligner.alignChunk(slice, plan.units, from)
            allAligned += result.units
            confidenceSum += result.confidence * result.units.size
            confidenceWeight += result.units.size
            report(((i + 1f) / plans.size) * 0.9f + 0.05f)
            com.prism.music.playback.BackgroundBudget.pace { !cancel.get() }
        }
        val quality = AlignmentQualityCheck.evaluate(allAligned, audio, if (confidenceWeight > 0) confidenceSum / confidenceWeight else 0f)
        val lines = overlayLines(lyrics.lines, allAligned, quality)
        return AlignmentRecord(
            songId = song.id, lyricsHash = lyricsHash, audioKey = audioKey, audioFingerprint = fingerprint,
            modelVersion = EnhanceModel.ALIGNER.version, settingsKey = settingsKey, createdAt = System.currentTimeMillis(),
            language = language.name, usedVocals = usedVocals, quality = quality, lines = lines,
        )
    }

    /** The generated lines, preserving every original line's text and (when it has one) its own stamps. */
    private fun overlayLines(source: List<LyricLine>, aligned: List<AlignedUnit>, quality: AlignmentQuality): List<AlignedLine> {
        val byLine = aligned.groupBy { it.line }
        val out = ArrayList<AlignedLine>(source.size)
        var previousEnd = 0L
        for ((i, line) in source.withIndex()) {
            val units = byLine[i].orEmpty()
            if (line.isGap || units.isEmpty()) {
                if (line.isGap) {
                    val start = if (line.timeMs >= 0) line.timeMs else previousEnd
                    val end = if (line.endMs > start) line.endMs else start
                    out += AlignedLine(i, line.text, start, end, isGap = true)
                    previousEnd = max(previousEnd, end)
                } else out += AlignedLine(i, line.text, line.timeMs, line.endMs)
                continue
            }
            val words = LyricsUnits.buildWords(units, line.text)
            if (words.isEmpty()) { out += AlignedLine(i, line.text, line.timeMs, line.endMs); continue }
            val start = if (line.timeMs >= 0) line.timeMs else words.first().startMs
            val end = if (line.endMs > line.timeMs) line.endMs else words.last().endMs
            out += AlignedLine(i, line.text, start, max(start + 1, end), words = words)
            previousEnd = max(previousEnd, end)
        }
        // A plain-text set has no stamps at all; the generated gaps keep the display's order sensible.
        if (source.all { it.timeMs < 0 }) {
            for (j in out.indices) {
                val l = out[j]
                if (!l.isGap || l.endMs > l.timeMs) continue
                val next = out.drop(j + 1).firstOrNull { !it.isGap && it.timeMs >= 0 }?.timeMs
                val start = out.take(j).lastOrNull { !it.isGap && it.endMs >= 0 }?.endMs ?: 0L
                out[j] = l.copy(timeMs = start, endMs = (next ?: start + 1_000).coerceAtLeast(start + 1))
            }
        }
        return out
    }

    // ------------------------------------------------------------------ queue and recovery

    fun enqueue(songs: Collection<Song>, kinds: Set<EnhanceKind>, force: Boolean = true) {
        if (songs.isEmpty() || kinds.isEmpty()) return
        _tasks.update { current -> EnhanceTasks.add(current, songs, kinds, force) }
        // The flags of a song queued again belong to the newer job.
        for (song in songs) for (kind in kinds) cancelled.remove(song.id + "|" + kind)
        saveJobs()
        startWorker()
    }

    /** Stops one job where it is; the others in the queue are not touched. */
    fun cancel(task: EnhanceTask) {
        cancelled[task.song.id + "|" + task.kind]?.set(true)
        _tasks.update { EnhanceTasks.cancelTask(it, task.id) }
        saveJobs()
    }

    fun cancel(songId: String, kind: EnhanceKind) {
        cancelled[songId + "|" + kind]?.set(true)
        _tasks.update { EnhanceTasks.cancel(it, songId, kind) }
        saveJobs()
    }

    fun cancelAll() {
        cancelled.values.forEach { it.set(true) }
        _tasks.update(EnhanceTasks::cancelAll)
        saveJobs()
    }

    /** The jobs a restart cut short: shown, never run on their own. */
    fun resumeInterrupted() {
        _tasks.update(EnhanceTasks::resumed)
        _tasks.value.forEach { cancelled.remove(it.song.id + "|" + it.kind) }
        saveJobs()
        startWorker()
    }

    fun discardInterrupted() {
        _tasks.update(EnhanceTasks::discardInterrupted)
        saveJobs()
    }

    fun clearFinished() {
        _tasks.update(EnhanceTasks::clearFinished)
        saveJobs()
    }

    private fun startWorker() {
        // The queue runs under its own foreground service: a batch outlives the screen, and Android neither closes a
        // process with one over its background CPU line nor freezes it (EnhanceService).
        EnhanceService.start(c.app)
        wake.trySend(Unit)
    }

    private suspend fun drainQueue() {
        while (true) {
            val next = _tasks.value.firstOrNull { it.state == "queued" } ?: break
            runTask(next)
        }
        unloadEnginesWhenIdle()
    }

    private suspend fun runTask(task: EnhanceTask) {
        val key = task.song.id + "|" + task.kind
        // Every job runs at normal priority: its inference threads inherit it, and the budget still caps the cores.
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
        setState(task, "running", 0f, null)
        // The next song's audio is decoded while this one is thinking, on its own thread: decoding is MediaCodec and
        // I/O, and waiting for it after the inference would be a second of the phone doing one thing at a time.
        prefetchNext(task)
        val cancel = AtomicBoolean(false)
        cancelled[key] = cancel
        // One place records how far the job is: the queue is the sheet's and the notification's one source of truth.
        val report: (Float) -> Unit = { f -> setProgress(task, f) }
        val options = EnhanceOptions(force = task.force)
        var cancelledRun = false
        try {
            when (task.kind) {
                // The setting decides whether the words are timed against the isolated vocals (when they're there).
                EnhanceKind.LYRICS -> syncLyrics(task.song, options.copy(useVocals = c.settings.current.enhanceAlignWithVocals), report)
                EnhanceKind.VOCALS -> separateVocals(task.song, options, report)
                EnhanceKind.INSTRUMENTAL -> makeInstrumental(task.song, options, report)
                EnhanceKind.EXTERNAL -> Unit
            }
            if (cancel.get()) throw CancellationException("cancelled")
            setState(task, "done", 1f, null)
        } catch (e: CancellationException) {
            cancelledRun = true
            setState(task, "cancelled", 0f, null)
        } catch (e: SeparationCancelled) {
            // The separator's own way of stopping mid-song: the listener's Cancel, not a failure.
            cancelledRun = true
            setState(task, "cancelled", 0f, null)
        } catch (e: Throwable) {
            cancelledRun = true
            Log.w("PrismEnhance", "${task.kind} for ${task.song.id} failed: $e")
            val why = if (e is OutOfMemoryError) tr(R.string.enhance_not_enough_memory) else e.message ?: tr(R.string.enhance_failed)
            setState(task, "failed", 0f, why)
        } finally {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            // A run that stopped early or ran out of memory leaves a session that may not be sound: the next job builds
            // a fresh one rather than carrying on with it (the model itself isn't loaded again for a plain failure).
            if (cancelledRun) dropEngine(task.kind)
            cancelled.remove(key)
            saveJobs()
        }
    }

    private fun setState(task: EnhanceTask, state: String, progress: Float, message: String?) {
        _tasks.update { EnhanceTasks.withState(it, task.id, state, progress, message) }
    }

    /** How far the job is, for the sheet's bar and the queue's line; the state stays whatever it is. */
    private fun setProgress(task: EnhanceTask, progress: Float) {
        _tasks.update { EnhanceTasks.withProgress(it, task.id, progress) }
    }

    private fun stateOf(s: String) = when (s) {
        "running" -> EnhanceState.RUNNING
        "done" -> EnhanceState.DONE
        "failed" -> EnhanceState.FAILED
        "cancelled" -> EnhanceState.CANCELLED
        "interrupted" -> EnhanceState.INTERRUPTED
        else -> EnhanceState.QUEUED
    }

    fun views(): List<EnhanceTaskView> = _tasks.value.map { EnhanceTaskView(it, stateOf(it.state), it.progress, it.message) }

    private fun loadJobs() {
        val loaded = EnhanceTasks.interrupted(
            runCatching { json.decodeFromString<List<EnhanceTask>>(jobsFile.readText()) }.getOrDefault(emptyList())
        )
        // A job the listener queued while the file was being read is newer than the file: keep it.
        _tasks.update { current -> EnhanceTasks.merge(loaded, current) }
        saveJobs()
    }

    /**
     * Keeps the queue's file in step with the queue, coalesced and off the calling thread: enqueueing a whole library
     * and a job finishing both ask for a save, and neither should write a multi-megabyte file on the UI thread. Only
     * one write runs at a time, so the file is never half of two.
     */
    private fun saveJobs() {
        if (!jobsDirty.compareAndSet(false, true)) return
        io.launch {
            while (jobsDirty.compareAndSet(true, false)) {
                val text = runCatching { json.encodeToString(ListSerializer, _tasks.value) }.getOrNull() ?: continue
                runCatching {
                    jobsFile.parentFile?.mkdirs()
                    val tmp = File(jobsFile.parentFile, jobsFile.name + ".tmp")
                    tmp.writeText(text)
                    if (!tmp.renameTo(jobsFile)) { jobsFile.delete(); tmp.copyTo(jobsFile, overwrite = true); tmp.delete() }
                }
            }
        }
    }

    // ------------------------------------------------------------------ engines

    /**
     * One song's audio, decoded ahead of the job that will need it: the next one in the queue. Decoding is MediaCodec
     * and reading the file, which can go on while the current piece is inferring; the job that needs it then starts at
     * its model instead of after a decode. Only audio already on the phone is fetched, so nothing downloads on a guess.
     */
    private class Prefetched(val songId: String, val stereo44: Boolean, val source: String, val file: File) {
        @Volatile var ready = false
        @Volatile var frames = 0L
        fun drop() { runCatching { file.delete() } }
    }

    private val prefetchThread = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "PrismEnhancePrefetch").apply { isDaemon = true }
    }
    @Volatile private var prefetched: Prefetched? = null
    private val prefetching = java.util.concurrent.atomic.AtomicBoolean(false)

    private val decodeThread = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "PrismEnhanceDecode").apply { isDaemon = true }
    }

    /** Decodes [uri] into [target] on its own thread, publishing its progress to [gate] as it goes. */
    private fun startDecode(uri: Uri, target: File, gate: DecodeGate, cancel: AtomicBoolean) {
        decodeThread.execute {
            try {
                val frames = AudioScratch.decode(uri, HtDemucs.RATE, 2, target, c, { cancel.get() || gate.abandoned }) { f -> gate.publish(f) }
                gate.publish(frames)
            } catch (e: Throwable) {
                gate.error = e
            } finally {
                gate.done = true
            }
        }
    }

    private fun prefetchNext(current: EnhanceTask) {
        val next = _tasks.value.firstOrNull { it.state == "queued" } ?: return
        if (next.song.id == current.song.id) return
        val stereo = next.kind != EnhanceKind.LYRICS
        val existing = prefetched
        if (existing != null && existing.songId == next.song.id && existing.stereo44 == stereo) return
        existing?.drop()
        val source = runCatching { c.audioOnPhone(next.song.id, next.song.id, File(c.app.cacheDir, "enhance_src_${safe(next.song.id)}.part")) }.getOrNull() ?: return
        val item = Prefetched(
            next.song.id, stereo, source.toString(),
            File(c.app.cacheDir, "enhance_pre_${safe(next.song.id)}_${if (stereo) "st" else "mono"}.pcm"),
        )
        prefetched = item
        if (!prefetching.compareAndSet(false, true)) return
        prefetchThread.execute {
            try {
                val frames = if (stereo) {
                    AudioScratch.decode(source, HtDemucs.RATE, 2, item.file, c) { prefetched !== item }
                } else {
                    AudioScratch.decode(source, 16_000, 1, item.file, c) { prefetched !== item }
                }
                if (prefetched !== item) { item.drop(); return@execute }
                item.frames = frames
                item.ready = true
            } catch (e: Throwable) {
                item.drop()
            } finally {
                prefetching.set(false)
            }
        }
    }

    /**
     * Takes the prefetched decode for this song, if it's the one waiting and it came from the same source file: the
     * caller then skips its own decode. [stereo44] picks the 44.1 kHz stereo piece (separation) or 16 kHz mono (words).
     */
    private fun adoptPrefetched(songId: String, stereo44: Boolean, source: String, target: File): Boolean {
        val item = prefetched ?: return false
        if (!item.ready || item.frames <= 0 || item.songId != songId || item.stereo44 != stereo44 || item.source != source) return false
        prefetched = null
        return runCatching {
            if (!item.file.renameTo(target)) { item.file.copyTo(target, overwrite = true); item.drop() }
            true
        }.getOrDefault(false)
    }

    /**
     * The aligner's session, built only when one isn't already here. Two sessions at once is about 1.8 GB; when the
     * phone is short of room the one this job doesn't need is let go first, rather than failing with no memory.
     */
    private fun acquireAligner(): QwenAligner {
        if (!aligners.cached() && separators.cached() && freeMemory() in 1..SEPARATOR_HEADROOM) separators.drop()
        return aligners.get()
    }

    private fun acquireSeparator(): HtDemucs {
        if (!separators.cached() && aligners.cached() && freeMemory() in 1..ALIGNER_HEADROOM) aligners.drop()
        return separators.get()
    }

    /** A job that stopped early may have left its session unsound: the next one builds a fresh one. */
    private fun dropEngine(kind: EnhanceKind) {
        when (kind) {
            EnhanceKind.LYRICS -> aligners.drop()
            EnhanceKind.VOCALS, EnhanceKind.INSTRUMENTAL -> separators.drop()
            EnhanceKind.EXTERNAL -> Unit
        }
    }

    /**
     * The queue is empty: the sessions go after a moment, so a phone left alone isn't holding a gigabyte of model. Every
     * job runs on this one heavy thread, so nothing can start between the check and the drop.
     */
    private fun unloadEnginesWhenIdle() {
        prefetched?.drop()
        prefetched = null
        scope.launch {
            kotlinx.coroutines.delay(ENGINE_IDLE_MS)
            if (_tasks.value.none { it.state == "queued" || it.state == "running" }) {
                aligners.drop()
                separators.drop()
            }
        }
    }

    private fun freeMemory(): Long {
        val am = c.app.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return Long.MAX_VALUE
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem
    }

    /** What a trim must never take: the song just made, what's playing, and what the queue is about to work on. */
    private fun keepForTrim(songId: String): Set<String> = buildSet {
        add(songId)
        c.player.currentSong.value?.id?.let { add(it) }
        _tasks.value.forEach { if (it.state == "queued" || it.state == "running") add(it.song.id) }
    }

    // ------------------------------------------------------------------ audio helpers

    private fun cancellation(songId: String, kind: EnhanceKind): AtomicBoolean =
        cancelled.getOrPut(songId + "|" + kind) { AtomicBoolean(false) }

    private fun ensureAudio(song: Song): Uri {
        val scratch = File(c.app.cacheDir, "enhance_src_${safe(song.id)}.part")
        var uri = c.audioOnPhone(song.id, song.id, scratch)
        if (uri == null && !song.isLocal) {
            c.warmUp(song.id, whole = true, cellular = c.settings.current.singOnCellular)
            uri = c.audioOnPhone(song.id, song.id, scratch)
        }
        if (uri == null) { scratch.delete(); throw EnhanceNoAudio() }
        return uri
    }

    private fun fingerprintOf(uri: Uri): String {
        if (uri.scheme != "file") return ""
        return uri.path?.let { EnhanceKeys.fingerprint(File(it)) }.orEmpty()
    }

    private fun probeRate(uri: Uri): Int = com.prism.music.playback.sing.PcmDecoder(c.app, uri).use { it.sampleRate }

    private fun checkMemory() {
        val am = c.app.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        // The separator's graph is ~1 GB while it runs. Below this the phone would kill Prism or the engine; fail with a
        // message the listener can act on (close some apps / restart) rather than an OutOfMemoryError.
        if (info.availMem in 1..MIN_FREE_BYTES) throw IOException(tr(R.string.enhance_not_enough_memory))
    }

    private fun writeSamples(samples: FloatArray, channels: Int, out: java.io.OutputStream) {
        if (samples.isEmpty()) return
        val bytes = ByteArray(16 * 1024)
        var i = 0
        val need = if (channels == 2) 4 else 2
        while (i < samples.size) {
            var n = 0
            while (i + 1 < samples.size && n + need <= bytes.size) {
                val l = samples[i]; val r = samples[i + 1]; i += 2
                val v = if (channels == 1) (l + r) * 0.5f else l
                val s = (v.coerceIn(-1f, 1f) * 32767f).toInt()
                bytes[n++] = s.toByte(); bytes[n++] = (s shr 8).toByte()
                if (channels == 2) { val q = (r.coerceIn(-1f, 1f) * 32767f).toInt(); bytes[n++] = q.toByte(); bytes[n++] = (q shr 8).toByte() }
            }
            out.write(bytes, 0, n)
        }
    }

    private fun dominantLanguage(lines: List<LyricLine>): AlignLanguage? {
        val counts = LinkedHashMap<AlignLanguage, Int>()
        for (l in lines) AlignLanguage.of(l.text)?.let { counts[it] = (counts[it] ?: 0) + l.text.length }
        return counts.maxByOrNull { it.value }?.key
    }

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    private companion object {
        const val MIN_FREE_BYTES = 900L * 1_048_576L
        /** Room the aligner's session needs on top of what's in use (its Q4 model is ~1.05 GB). */
        const val ALIGNER_HEADROOM = 1_700L * 1_048_576L
        /** Room the separator's session needs on top of what's in use (~166 MB of weights, ~700 MB while it runs). */
        const val SEPARATOR_HEADROOM = 1_000L * 1_048_576L
        /** An empty queue keeps the sessions this long: a pause within a batch costs nothing, an idle phone holds nothing. */
        const val ENGINE_IDLE_MS = 300_000L
        val ListSerializer = kotlinx.serialization.builtins.ListSerializer(EnhanceTask.serializer())
    }
}
