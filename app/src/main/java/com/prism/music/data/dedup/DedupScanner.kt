package com.prism.music.data.dedup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The manual "Find duplicate songs" walk, following the same shape as [com.prism.music.data.MeasureSongs]: nothing runs
 * at startup or when the setting changes; a press starts a walk, a stop keeps the count, and pressing again resumes the
 * same set from where it stopped instead of starting a new set with a smaller total and a reset counter.
 *
 * Only unindexed, stale or failed files are decoded; the rest are read from the persistent cache. A file that cannot be
 * decoded is marked failed, skipped, and the walk carries on.
 */
class DedupScanner(
    private val scope: CoroutineScope,
    private val store: DedupStore,
    /** The files in the scope of the scan (local files and downloads), as (stable file key, stable song/form id, uri). */
    private val files: suspend () -> List<FileRef>,
    /** Decodes one file and makes its fingerprint. Implemented with the app's PcmDecoder + ShazamSignature. */
    private val fingerprintOf: suspend (FileRef) -> AudioFingerprint,
    /** Called with freshly computed automatic statements after the walk. */
    private val onAutomatic: suspend (List<Relation>) -> Unit,
    /** A file that already had a fingerprint changed on disk: manual decisions involving it need a warning. */
    private val onFileChanged: suspend (FileRef) -> Unit = {},
) {
    data class FileRef(
        val key: String,
        val songId: String,
        val uri: String,
        val sizeBytes: Long,
        val modifiedMs: Long,
        val durationMs: Long,
    )

    /** Files processed of files to process, while a walk runs. Null when none is under way. */
    val progress = MutableStateFlow<Pair<Int, Int>?>(null)
    val running = MutableStateFlow(false)
    /** New files appeared since the last scan: they stay visible, with an "index out of date" hint. */
    val indexOutdated = MutableStateFlow(false)

    private var job: Job? = null
    private var walk = 0
    /** The set the current (or stopped) walk was over; kept so a second press continues it, not a new set. */
    private var walkSet: List<FileRef> = emptyList()
    /** Files processed in that walk; kept when it is stopped. */
    private var done = 0
    /** Whether the current walk finished. Only a finished walk's set is thrown away by the next press. */
    private var finished = false
    /** Keys of cached files whose size or mtime changed since the last scan. */
    private var changed = emptySet<String>()

    private suspend fun remaining(set: List<FileRef>): List<FileRef> = withContext(Dispatchers.IO) {
        val cached = store.fingerprintMetas().associateBy { it.key }
        val excluded = store.exclusions()
        // Each cached row carries its own algorithm version. Comparing against a separate "last completed walk"
        // marker would make every finished file look stale again while a walk is stopped, resetting progress to zero
        // on the next press. The row's version is the authoritative check (and still forces a re-scan after a change).
        changed = set.filter { ref ->
            cached[ref.key]?.let { row -> row.sizeBytes != ref.sizeBytes || row.modifiedMs != ref.modifiedMs } ?: false
        }.map { it.key }.toSet()
        set.filter { ref ->
            ref.key !in excluded && cached[ref.key]?.let { row ->
                row.version != AudioFingerprint.VERSION || row.state != "ok" || row.sizeBytes != ref.sizeBytes || row.modifiedMs != ref.modifiedMs
            } ?: true
        }
    }

    /** Files that need a fingerprint now: no cache, a changed size/mtime, an old algorithm, or a previous failure. */
    suspend fun pending(): List<FileRef> {
        val current = files()
        val todo = remaining(current)
        // A new file is not hidden: it shows at once, and the index hint says a scan would group it.
        val cached = store.fingerprintMetas().associateBy { it.key }
        val outdated = current.any { it.key !in cached } || todo.isNotEmpty()
        indexOutdated.value = outdated
        store.setMeta(DedupStore.META_INDEX_OUTDATED, outdated.toString())
        return todo
    }

    /**
     * Starts a walk, or resumes the one a previous press stopped. A stopped walk keeps its count and its total, so
     * pressing "Find duplicate songs now" again carries on from where it left off rather than showing a fresh count
     * over only the files that are left.
     */
    fun start() {
        val mine = ++walk
        job?.cancel()
        // Set this before launching so a foreground service started by the same tap sees the walk as running at once.
        running.value = true
        scope.launch(Dispatchers.IO) {
            // Old-format blobs are large; drop them once, at the start of a walk, before new ones are written.
            store.dropOldVersions()
            val resuming = !finished && walkSet.isNotEmpty()
            val todo = if (resuming) remaining(walkSet) else pending()
            if (todo.isEmpty()) {
                if (!resuming) {
                    walkSet = emptyList(); done = 0; finished = true; progress.value = null
                }
                running.value = false
                return@launch
            }
            if (!resuming) { walkSet = todo; done = 0; finished = false }
            val total = walkSet.size
            if (resuming) done = total - todo.size
            progress.value = done to total
            try {
                for (ref in todo) {
                    if (mine != walk || !isActive) throw CancellationException()
                    if (ref.key in changed) onFileChanged(ref)
                    runCatching { fingerprintOf(ref) }
                        .onSuccess { fp -> store.putFingerprint(ref.key, fp, ref.sizeBytes, ref.modifiedMs, System.currentTimeMillis()) }
                        .onFailure { e ->
                            if (e is CancellationException) throw e
                            // Keep the file visible and unindexed; the next scan tries it again.
                            store.putFingerprint(ref.key, AudioFingerprint(AudioFingerprint.VERSION, ref.durationMs, 16_000, LongArray(0)),
                                ref.sizeBytes, ref.modifiedMs, System.currentTimeMillis(), error = e.message ?: "could not decode")
                        }
                    done++
                    if (mine == walk) progress.value = done to total
                }
                if (mine == walk) {
                    recompute()
                    store.setMeta(DedupStore.META_ALGORITHM, AudioFingerprint.VERSION.toString())
                    store.setMeta(DedupStore.META_INDEX_OUTDATED, "false")
                    indexOutdated.value = false
                    finished = true
                    progress.value = null
                }
            } finally {
                if (mine == walk) {
                    running.value = false
                    // A stop keeps [progress] and [walkSet] for the next press to resume.
                }
            }
        }.also { job = it }
    }

    /** Skips a walk part-way. Its count and total stay on screen, and a later press resumes the same set. */
    fun stop() { job?.cancel() }

    /** Compares cached fingerprints and hands the automatic statements to the caller for persistence. */
    private suspend fun recompute() = withContext(Dispatchers.IO) {
        // Read the small rows first, then each blob one at a time: loading every fingerprint at once was the OOM.
        val metas = store.fingerprintMetas().filter { it.state == "ok" && it.version == AudioFingerprint.VERSION }
        val refs = files().associateBy { it.key }
        // A file that was deleted (or removed from the scope) leaves no fingerprint behind.
        metas.filter { it.key !in refs }.forEach { store.forget(it.key) }
        val usable = ArrayList<Pair<String, AudioFingerprint>>()
        for (meta in metas) {
            val songId = refs[meta.key]?.songId ?: continue
            val fp = store.fingerprint(meta.key) ?: continue
            if (fp.size > 0) usable += songId to fp
        }
        val distinct = usable.distinctBy { it.first }
        val fresh = ArrayList<Relation>()
        for (i in distinct.indices) for (j in i + 1 until distinct.size) {
            val (aId, a) = distinct[i]
            val (bId, b) = distinct[j]
            val m = Matcher.compare(a, b)
            if (m.level != Matcher.Level.NONE) {
                fresh += Relation(aId, bId, RelationSource.AUTO, m.votes.toDouble(), at = System.currentTimeMillis())
            }
        }
        onAutomatic(fresh)
    }
}
