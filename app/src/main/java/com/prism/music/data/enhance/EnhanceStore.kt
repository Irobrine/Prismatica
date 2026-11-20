package com.prism.music.data.enhance

import com.prism.music.data.lyrics.LyricLine
import com.prism.music.data.lyrics.LyricPart
import com.prism.music.data.lyrics.LyricWord
import com.prism.music.data.lyrics.Lyrics
import com.prism.music.data.model.Song
import com.prism.music.playback.sing.StemStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** One word the aligner placed, exactly as it sits in the line (punctuation and spaces included). */
@Serializable
data class AlignedWord(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** Per-character runs for scripts the aligner times by character (CJK); empty when the word's own span is enough. */
    val parts: List<AlignedPart> = emptyList(),
) {
    fun toLyricWord() = LyricWord(startMs, endMs, text, parts.map { LyricPart(it.startMs, it.endMs, it.length) })
}

@Serializable
data class AlignedPart(val startMs: Long, val endMs: Long, val length: Int)

/**
 * A line's generated timings. Everything about the original line (its text, its own stamps, whether it's a gap) is kept
 * here too, so the overlay can only be applied to the lyrics it was made from.
 */
@Serializable
data class AlignedLine(
    val index: Int,
    val text: String,
    val timeMs: Long,
    val endMs: Long,
    val isGap: Boolean = false,
    val words: List<AlignedWord> = emptyList(),
)

/** What the quality check made of a result. Stored with it so a warning survives a restart. */
@Serializable
data class AlignmentQuality(
    val confidence: Float = 0f,
    val phonetic: Float = 1f,
    val monotonic: Float = 1f,
    val vocalActivity: Float = 1f,
    val score: Float = 0f,
    val reliable: Boolean = false,
    val reasons: List<String> = emptyList(),
)

/**
 * The generated word timings for one song and one exact version of its lyrics and audio. [lyricsHash], [audioKey],
 * [audioFingerprint] and [modelVersion] are the cache key: any of them changing makes this result stale and it is not
 * used again.
 */
@Serializable
data class AlignmentRecord(
    val songId: String,
    val lyricsHash: String,
    val audioKey: String,
    val audioFingerprint: String = "",
    val modelVersion: String,
    val settingsKey: String = "",
    val createdAt: Long,
    val language: String = "",
    val usedVocals: Boolean = false,
    val quality: AlignmentQuality = AlignmentQuality(),
    val lines: List<AlignedLine> = emptyList(),
)

/** Which audio the separator worked on, and what it made. */
@Serializable
data class SeparationRecord(
    val songId: String,
    val audioKey: String,
    val audioFingerprint: String = "",
    val modelVersion: String,
    val createdAt: Long,
    val rate: Int,
    val frames: Long,
    val vocalsFrames: Long,
    val instrumentalFrames: Long,
    val method: String = "MELBAND",
)

/** A listener's own instrumental: the file they picked is never touched; this is the decoded copy Prism plays. */
@Serializable
data class ExternalInstrumentalRecord(
    val songId: String,
    val name: String,
    val sourceKey: String,
    val sourceFingerprint: String,
    val importedAt: Long,
    val rate: Int,
    val frames: Long,
)

enum class InstrumentalKind { ORIGINAL, GENERATED, EXTERNAL }

/**
 * Where generated lyrics, vocals and instrumentals live. Kept apart from the lyrics database and from the song's own
 * files: an enhancement never overwrites or deletes the original lyrics, audio, or an external instrumental.
 *
 * Vocals and instrumentals use the same 16-bit stereo PCM format (and reader) as Sing's separated vocals, so the player's
 * processor can use either without a second audio path.
 */
class EnhanceStore(private val filesDir: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val alignDir = File(filesDir, "lyrics-enhance").apply { mkdirs() }
    private val alignRecords = File(alignDir, "alignment").apply { mkdirs() }
    private val fingerprints = File(alignDir, "audio-fingerprints.json")
    /** Which songs show their generated words; absent is on. */
    private val overlayPrefs = File(alignDir, "lyrics-shown.json")

    /** Separated vocals, generated instrumentals and imported instrumental copies, each in its own folder. */
    val vocals = StemStore(filesDir, "enhance-vocals")
    val generated = StemStore(filesDir, "enhance-instrumentals")
    val external = StemStore(filesDir, "enhance-external")

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
    private fun alignmentFile(id: String) = File(alignRecords, "${safe(id)}.json")
    private fun recordFile(store: StemStore, id: String) = File(store.dir, "${safe(id)}.record.json")

    fun alignment(songId: String): AlignmentRecord? = read(alignmentFile(songId))
    fun putAlignment(r: AlignmentRecord) = write(alignmentFile(r.songId), r)
    fun deleteAlignment(songId: String) { alignmentFile(songId).delete() }

    /**
     * Whether the generated words are shown for this song: on until the listener turns them off, and the record is kept
     * either way (turning them back on is instant, and a new alignment starts on).
     */
    fun lyricsEnabled(songId: String): Boolean = read<Map<String, String>>(overlayPrefs)?.get(safe(songId)) != "0"

    fun setLyricsEnabled(songId: String, on: Boolean) {
        val map = LinkedHashMap(read<Map<String, String>>(overlayPrefs).orEmpty())
        map[safe(songId)] = if (on) "1" else "0"
        write(overlayPrefs, map)
    }

    /** The last fingerprint Prism measured for a song, when it last enhanced it (null before any). */
    fun currentFingerprint(songId: String): String? = read<Map<String, String>>(fingerprints)?.get(safe(songId))

    fun putCurrentFingerprint(songId: String, fingerprint: String) {
        if (fingerprint.isEmpty()) return
        val map = LinkedHashMap(read<Map<String, String>>(fingerprints).orEmpty())
        map[safe(songId)] = fingerprint
        write(fingerprints, map)
    }

    fun separation(songId: String): SeparationRecord? = read(recordFile(vocals, songId))
    fun putSeparation(r: SeparationRecord) = write(recordFile(vocals, r.songId), r)

    fun generatedInstrumental(songId: String): SeparationRecord? = read(recordFile(generated, songId))
    fun putGeneratedInstrumental(r: SeparationRecord) = write(recordFile(generated, r.songId), r)

    fun externalInstrumental(songId: String): ExternalInstrumentalRecord? = read(recordFile(external, songId))
    fun putExternalInstrumental(r: ExternalInstrumentalRecord) = write(recordFile(external, r.songId), r)

    fun hasVocals(songId: String) = vocals.complete(songId)
    fun hasGeneratedInstrumental(songId: String) = generated.complete(songId)
    fun hasExternalInstrumental(songId: String) = external.complete(songId)

    fun instrumentalKind(songId: String): InstrumentalKind = when {
        hasGeneratedInstrumental(songId) -> InstrumentalKind.GENERATED
        hasExternalInstrumental(songId) -> InstrumentalKind.EXTERNAL
        else -> InstrumentalKind.ORIGINAL
    }

    /** Forgets this song's generated vocals and instrumental; an imported instrumental is kept. */
    fun deleteGeneratedAudio(songId: String) {
        vocals.delete(songId); generated.delete(songId)
        recordFile(vocals, songId).delete(); recordFile(generated, songId).delete()
    }

    /** Forgets an imported instrumental copy; the file the listener picked is never touched. */
    fun deleteExternalInstrumental(songId: String) {
        external.delete(songId); recordFile(external, songId).delete()
    }

    /** How much room the generated audio takes, for Settings. */
    fun bytes(): Long = vocals.bytes() + generated.bytes() + external.bytes()

    /** Clears generated vocals and instrumentals. Imported instrumentals are the listener's, so they stay. */
    fun clearAudio() { vocals.clear(); generated.clear() }

    /**
     * Trims generated audio to [limit], keeping the playing and queued songs (and every imported instrumental). 0 (or
     * less) is no limit. Each store gets the share of the room its size had, so work that only fills one of them isn't
     * cut to half the room the listener chose.
     */
    fun trim(limit: Long, keep: Set<String>) {
        if (limit <= 0) return
        val total = bytes()
        if (total <= limit) return
        for (store in listOf(vocals, generated)) {
            store.trim((limit.toDouble() * store.bytes() / total).toLong(), keep)
        }
    }

    /**
     * The separated vocals as a WAV in [out], for the sheet's own preview (the store's PCM has no header, so no player
     * could open it). Null when there are no vocals.
     */
    fun vocalsWav(songId: String, out: File): File? {
        val meta = vocals.meta(songId) ?: return null
        val reader = vocals.reader(songId) ?: return null
        val frames = meta.totalFrames
        return try {
            java.io.BufferedOutputStream(out.outputStream(), 1 shl 16).use { sink ->
                val data = frames * 4
                val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray()).putInt((36 + data).toInt()).put("WAVE".toByteArray())
                header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2)
                header.putInt(meta.rate).putInt(meta.rate * 4).putShort(4).putShort(16)
                header.put("data".toByteArray()).putInt(data.toInt())
                sink.write(header.array())
                val samples = FloatArray(24_000 * 2)
                val bytes = java.nio.ByteBuffer.allocate(samples.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                var at = 0L
                while (at < frames) {
                    val n = minOf(24_000L, frames - at).toInt()
                    reader.read(at, n, samples)
                    bytes.clear()
                    for (i in 0 until n * 2) bytes.putShort((samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                    sink.write(bytes.array(), 0, n * 4)
                    at += n
                }
            }
            out
        } catch (e: Throwable) {
            null
        } finally {
            runCatching { reader.close() }
        }
    }

    /** The overlay on [lyrics]: the generated words applied to the original lines, or [lyrics] when they don't fit. */
    fun apply(lyrics: Lyrics, record: AlignmentRecord): Lyrics {
        if (record.lines.isEmpty()) return lyrics
        val byIndex = record.lines.associateBy { it.index }
        var applied = 0
        var mismatched = 0
        val out = lyrics.lines.mapIndexed { i, line ->
            val gen = byIndex[i] ?: return@mapIndexed line
            if (line.isGap != gen.isGap || line.text != gen.text) { mismatched++; return@mapIndexed line }
            // Line-synced lyrics keep their own line stamps; a plain line takes the generated span.
            val time = if (line.timeMs >= 0) line.timeMs else gen.timeMs.coerceAtLeast(0)
            var end = if (line.endMs > line.timeMs) line.endMs else gen.endMs.coerceAtLeast(time)
            // An unreliable alignment can leave a line with no span at all (every word at one instant, and a source
            // with start times only taking that instant as its own). The room up to the next line is used instead —
            // decided *before* the words are fitted, or every word clamps to that one instant and none of them moves.
            if (end <= time) {
                // The source may have no stamps at all (plain lyrics): the next *generated* line is what follows it.
                val next = record.lines.filter { it.index > i && !it.isGap }.map { it.timeMs }.filter { it > time }.minOrNull()
                end = (next ?: (time + 4_000L)).coerceAtLeast(time + 400L)
            }
            val words = fitWords(gen.words, line.text, time, end)
            if (words.isEmpty()) return@mapIndexed line
            applied++
            // Generated words are a change to the words only; every other field (background vocals, alignment side,
            // source readings, the line's own text and stamps) stays exactly as the source had it.
            line.copy(timeMs = time, endMs = end, words = words)
        }
        if (applied == 0) return lyrics
        // A line the record doesn't know stays as it was; a different text for the same index is a stale result.
        if (mismatched > maxOf(2, lyrics.lines.size / 10)) return lyrics
        return if (lyrics.synced) lyrics.copy(lines = out) else lyrics.copy(synced = true, lines = out)
    }

    /**
     * Keeps the original line's characters: word texts are the record's, and anything the generated words don't cover
     * (an unusual symbol at an edge) is attached to the word beside it so the line still reads exactly as written.
     */
    private fun fitWords(words: List<AlignedWord>, text: String, lineStart: Long, lineEnd: Long): List<LyricWord> {
        if (words.isEmpty()) return emptyList()
        // The generated words were built from this same line, so their texts normally add up to it. When they don't
        // (a source with odd spacing), only the words that are found in order are used.
        val joined = words.joinToString("") { it.text }
        if (joined != text && joined.trim() != text.trim()) {
            val found = ArrayList<LyricWord>()
            var at = 0
            for (w in words) {
                val i = text.indexOf(w.text.trim(), at)
                if (i < 0) continue
                found += w.toLyricWord()
                at = i + w.text.trim().length
            }
            return if (found.isEmpty()) emptyList() else repairInstants(found, lineEnd)
        }
        // Timings never leave their line: a generated word two seconds outside it is clamped, and the quality check has
        // already flagged the result. The line's own stamp stays the source's.
        val fitted = words.map { w ->
            val s = w.startMs.coerceIn(lineStart, lineEnd.coerceAtLeast(lineStart))
            val e = w.endMs.coerceIn(s, lineEnd.coerceAtLeast(s))
            w.copy(startMs = s, endMs = e).toLyricWord()
        }
        return repairInstants(fitted, lineEnd)
    }

    /**
     * A word the model gave no duration to (its start and end are the same instant) is given the room up to the next
     * word that has one, or to the line's end: a word that lights and vanishes within one instant can't be followed
     * while everything around it moves. A run of such words left at one instant shares that room naively, by text
     * length, from where the first one sits to where the next timed word starts. The record itself is left as the model
     * answered (its quality line already says the timings are doubtful).
     */
    private fun repairInstants(words: List<LyricWord>, lineEnd: Long): List<LyricWord> {
        if (words.none { it.endMs <= it.startMs }) return words
        val out = words.toMutableList()
        var i = 0
        while (i < out.size) {
            val first = out[i]
            if (first.endMs > first.startMs) { i++; continue }
            var last = i
            while (last + 1 < out.size && out[last + 1].endMs <= out[last + 1].startMs) last++
            val next = (last + 1 until out.size).firstOrNull { out[it].startMs > first.startMs }
            val room = (if (next != null) out[next].startMs else lineEnd) - first.startMs
            if (room <= 0) { i = last + 1; continue }
            val run = i..last
            val letters = run.sumOf { maxOf(1, out[it].text.trimEnd().length) }
            var at = first.startMs
            for (k in run) {
                val share = room * maxOf(1, out[k].text.trimEnd().length) / letters
                out[k] = out[k].copy(startMs = at, endMs = (at + share).coerceAtLeast(at + 1))
                at += share
            }
            i = last + 1
        }
        return out
    }

    private inline fun <reified T> read(f: File): T? = runCatching { json.decodeFromString<T>(f.readText()) }.getOrNull()

    private fun write(f: File, value: Any) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        val text = when (value) {
            is AlignmentRecord -> json.encodeToString(AlignmentRecord.serializer(), value)
            is SeparationRecord -> json.encodeToString(SeparationRecord.serializer(), value)
            is ExternalInstrumentalRecord -> json.encodeToString(ExternalInstrumentalRecord.serializer(), value)
            is Map<*, *> -> json.encodeToString(
                kotlinx.serialization.builtins.MapSerializer(String.serializer(), String.serializer()),
                @Suppress("UNCHECKED_CAST") (value as Map<String, String>),
            )
            else -> error("unknown record")
        }
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.copyTo(f, overwrite = true); tmp.delete() }
    }
}

/** Stable keys for caching: the lyrics' exact text and timings, and the song's audio identity. */
object EnhanceKeys {
    /** Everything about [lyrics] that the generated words depend on, including the source's own line stamps. */
    fun lyricsHash(lyrics: Lyrics): String {
        val sb = StringBuilder()
        sb.append(lyrics.synced).append('\u0001')
        for (line in lyrics.lines) {
            // The source's own word timings don't change what the aligner is asked to time: the line's text and stamps
            // do. Including them would make a word-timed source and the same source stripped for alignment look like two
            // different lyrics versions, and a fresh alignment would never be shown on the word-timed copy.
            sb.append(line.timeMs).append('\u0002').append(line.endMs).append('\u0002')
                .append(line.isGap).append('\u0002').append(line.text).append('\u0003')
        }
        return sha256(sb.toString().toByteArray())
    }

    /** A song's identity as the enhancement features see it: which recording, and how long it is. */
    fun audioKey(song: Song): String = buildString {
        append(song.id).append('|').append(song.isLocal).append('|').append(song.durationSec)
        if (song.isLocal) append('|').append(song.title)
    }

    fun settingsKey(usedVocals: Boolean, language: String): String = "$usedVocals|$language|v1"

    /**
     * A cheap fingerprint of the audio on the phone: its length and SHA-256 over the first and last 128 KB (and from
     * the middle of files over 1 MB). Two different masters of a song almost always differ in length or at an edge; a
     * full hash would read gigabytes on the main path. Null when the audio isn't on the phone.
     */
    fun fingerprint(file: File): String? {
        if (!file.isFile) return null
        val md = MessageDigest.getInstance("SHA-256")
        val len = file.length()
        md.update(longBytes(len))
        file.inputStream().buffered().use { input ->
            val head = ByteArray(minOf(128 * 1024L, len).toInt())
            var got = 0
            while (got < head.size) { val n = input.read(head, got, head.size - got); if (n < 0) break; got += n }
            md.update(head, 0, got)
        }
        if (len > 1024 * 1024) {
            java.io.RandomAccessFile(file, "r").use { raf ->
                for (at in longArrayOf(len / 2 - 64 * 1024, len - 128 * 1024)) {
                    val n = 128 * 1024
                    val b = ByteArray(n)
                    raf.seek(at.coerceAtLeast(0))
                    var got = 0
                    while (got < n) { val r = raf.read(b, got, n - got); if (r < 0) break; got += r }
                    md.update(b, 0, got)
                }
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun longBytes(v: Long) = ByteArray(8) { i -> (v ushr (8 * i)).toByte() }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
