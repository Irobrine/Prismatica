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
 * The manual "Find duplicate songs" walk. Nothing here runs at startup or when the setting is switched on: [start] is
 * only called from a button. Only unindexed, stale or failed files are decoded; the rest are read from the persistent
 * cache. A file that cannot be decoded is marked failed, skipped, and the walk carries on.
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
    /** Keys of cached files whose size or mtime changed since the last scan. */
    private var changed = emptySet<String>()

    /** Files that need a fingerprint now: no cache, a changed size/mtime, an old algorithm, or a previous failure. */
    suspend fun pending(): List<FileRef> = withContext(Dispatchers.IO) {
        val current = files().associateBy { it.key }
        val cached = store.fingerprints().associateBy { it.key }
        val excluded = store.exclusions()
        val algorithm = store.meta(DedupStore.META_ALGORITHM)?.toIntOrNull() ?: 0
        changed = current.values.filter { ref ->
            cached[ref.key]?.let { row -> row.sizeBytes != ref.sizeBytes || row.modifiedMs != ref.modifiedMs } ?: false
        }.map { it.key }.toSet()
        val pending = current.values.filter { ref ->
            ref.key !in excluded && cached[ref.key]?.let { row ->
                row.version != algorithm || row.state != "ok" || row.sizeBytes != ref.sizeBytes || row.modifiedMs != ref.modifiedMs
            } ?: true
        }
        // A new file is not hidden: it shows at once, and the index hint says a scan would group it.
        val outdated = current.keys.any { it !in cached } || pending.isNotEmpty()
        indexOutdated.value = outdated
        store.setMeta(DedupStore.META_INDEX_OUTDATED, outdated.toString())
        pending
    }

    fun start() {
        job?.cancel()
        val mine = ++walk
        scope.launch(Dispatchers.IO) {
            val todo = pending()
            if (todo.isEmpty()) {
                progress.value = null
                running.value = false
                return@launch
            }
            running.value = true
            var done = 0
            progress.value = 0 to todo.size
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
                    if (mine == walk) progress.value = done to todo.size
                }
                if (mine == walk) {
                    recompute()
                    store.setMeta(DedupStore.META_ALGORITHM, AudioFingerprint.VERSION.toString())
                    store.setMeta(DedupStore.META_INDEX_OUTDATED, "false")
                    indexOutdated.value = false
                }
            } finally {
                if (mine == walk) {
                    running.value = false
                    progress.value = null
                }
            }
        }.also { job = it }
    }

    /** Skips a walk part-way. What was already fingerprinted stays cached, so a later start resumes the rest. */
    fun stop() { job?.cancel() }

    /** Compares cached fingerprints and hands the automatic statements to the caller for persistence. */
    private suspend fun recompute() = withContext(Dispatchers.IO) {
        val rows = store.fingerprints().filter { it.state == "ok" && it.version == AudioFingerprint.VERSION }
        val refs = files().associateBy { it.key }
        // A file that was deleted (or removed from the scope) leaves no fingerprint behind.
        rows.filter { it.key !in refs }.forEach { store.forget(it.key) }
        val usable = rows.mapNotNull { row ->
            val songId = refs[row.key]?.songId ?: return@mapNotNull null
            val fp = runCatching { AudioFingerprint.decode(row.blob) }.getOrNull() ?: return@mapNotNull null
            if (fp.size == 0) null else songId to fp
        }.distinctBy { it.first }
        val fresh = ArrayList<Relation>()
        for (i in usable.indices) for (j in i + 1 until usable.size) {
            val (aId, a) = usable[i]
            val (bId, b) = usable[j]
            val m = Matcher.compare(a, b)
            if (m.level != Matcher.Level.NONE) {
                fresh += Relation(aId, bId, RelationSource.AUTO, m.votes.toDouble(), at = System.currentTimeMillis())
            }
        }
        onAutomatic(fresh)
    }
}
