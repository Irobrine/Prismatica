package com.prism.music.data.enhance

import com.prism.music.data.model.Song
import kotlinx.serialization.Serializable

/** One queued job: one operation on one song. */
@Serializable
data class EnhanceTask(
    val song: Song,
    val kind: EnhanceKind,
    /**
     * What identifies this job in the queue. A song can be queued again for the same operation after a cancel, so the
     * pair (song, kind) isn't enough: the older job's last update must not land on the newer one.
     */
    val id: Long = System.nanoTime(),
    val createdAt: Long = System.currentTimeMillis(),
    val state: String = "queued",
    val progress: Float = 0f,
    val message: String? = null,
    /** Re-run even when a valid result is cached; a batch leaves finished songs alone instead. */
    val force: Boolean = true,
)

enum class EnhanceState { QUEUED, RUNNING, DONE, FAILED, CANCELLED, INTERRUPTED }

data class EnhanceTaskView(val task: EnhanceTask, val state: EnhanceState, val progress: Float, val message: String?)

/**
 * The queue's rules, on lists alone: which jobs an action adds, which ones a cancel touches, and how a job's state is
 * recorded. They live here (rather than inside [EnhancementService]) so they can be read and tested without an Android
 * container — a song can be queued again for the same operation, and each job keeps its own progress.
 */
internal object EnhanceTasks {
    val LIVE get() = setOf("queued", "running", "interrupted")

    /** The jobs a batch adds: one per song and operation, never doubling one that's already waiting or running. */
    fun add(
        current: List<EnhanceTask>,
        songs: Collection<Song>,
        kinds: Set<EnhanceKind>,
        force: Boolean,
    ): List<EnhanceTask> {
        val busy = current.filter { it.state in LIVE }.mapTo(HashSet()) { it.song.id to it.kind }
        val out = ArrayList(current)
        for (song in songs) for (kind in kinds) {
            if (kind == EnhanceKind.EXTERNAL) continue
            if (!busy.add(song.id to kind)) continue
            out += EnhanceTask(song = song, kind = kind, force = force)
        }
        return out
    }

    fun withState(list: List<EnhanceTask>, id: Long, state: String, progress: Float, message: String?): List<EnhanceTask> =
        list.map { if (it.id == id) it.copy(state = state, progress = progress, message = message) else it }

    fun withProgress(list: List<EnhanceTask>, id: Long, progress: Float): List<EnhanceTask> =
        list.map { if (it.id == id) it.copy(progress = progress.coerceIn(0f, 1f)) else it }

    /** Cancels everything still to come or running; finished jobs are left as they are. */
    fun cancelAll(list: List<EnhanceTask>): List<EnhanceTask> =
        list.map { if (it.state in LIVE) it.copy(state = "cancelled", progress = 0f) else it }

    fun cancel(list: List<EnhanceTask>, songId: String, kind: EnhanceKind): List<EnhanceTask> =
        list.map { if (it.song.id == songId && it.kind == kind && it.state in LIVE) it.copy(state = "cancelled", progress = 0f) else it }

    /** Stops one job (by identity): the one the listener pointed at, not another of the same operation queued since. */
    fun cancelTask(list: List<EnhanceTask>, id: Long): List<EnhanceTask> =
        list.map { if (it.id == id && it.state in LIVE) it.copy(state = "cancelled", progress = 0f) else it }

    /** Jobs a restart cut short: shown, never run on their own. */
    fun interrupted(list: List<EnhanceTask>): List<EnhanceTask> =
        list.map { if (it.state == "running" || it.state == "queued") it.copy(state = "interrupted", progress = 0f) else it }

    fun resumed(list: List<EnhanceTask>): List<EnhanceTask> {
        // One already waiting or running for the same song and operation takes precedence: resuming must not double it.
        val live = list.filter { it.state == "queued" || it.state == "running" }.mapTo(HashSet()) { it.song.id to it.kind }
        return list.map {
            if (it.state == "interrupted" && live.add(it.song.id to it.kind)) it.copy(state = "queued", message = null) else it
        }
    }

    fun discardInterrupted(list: List<EnhanceTask>): List<EnhanceTask> = list.filterNot { it.state == "interrupted" }

    fun clearFinished(list: List<EnhanceTask>): List<EnhanceTask> = list.filter { it.state in LIVE }

    /** The saved queue merged with what the listener queued while it was being read: the newer job of each pair wins. */
    fun merge(saved: List<EnhanceTask>, current: List<EnhanceTask>): List<EnhanceTask> {
        val out = LinkedHashMap<Long, EnhanceTask>()
        for (t in saved) out[t.id] = t
        for (t in current) out[t.id] = t
        return out.values.toList()
    }

    /** What the queue's list shows: what is running or waiting first, then the latest finished jobs. */
    fun shown(list: List<EnhanceTask>, count: Int = 8): List<EnhanceTask> {
        val live = list.filter { it.state == "queued" || it.state == "running" }
        val done = list.filterNot { it.state == "queued" || it.state == "running" }.takeLast((count - live.size).coerceAtLeast(0))
        return live.take(count) + done
    }
}
