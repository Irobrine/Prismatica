package com.prism.music

import com.prism.music.data.enhance.EnhanceKind
import com.prism.music.data.enhance.EnhanceTask
import com.prism.music.data.enhance.EnhanceTasks
import com.prism.music.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue's rules on their own: one job per operation, each with its own identity and progress, and cancelling one
 * never touches a job the listener queued afterwards.
 */
class EnhanceTasksTest {
    private fun song(id: String) = Song(id = id, title = "Song $id")
    private fun task(songId: String, kind: EnhanceKind = EnhanceKind.LYRICS, state: String = "queued", progress: Float = 0f, id: Long = System.nanoTime()) =
        EnhanceTask(song = song(songId), kind = kind, id = id, state = state, progress = progress)

    @Test fun oneJobPerSongAndOperation() {
        val out = EnhanceTasks.add(emptyList(), listOf(song("a"), song("b")), setOf(EnhanceKind.LYRICS), force = true)
        assertEquals(2, out.size)
        assertEquals(setOf("a", "b"), out.map { it.song.id }.toSet())
        // An imported instrumental isn't a job (it's copied when it's picked).
        val withExternal = EnhanceTasks.add(emptyList(), listOf(song("a")), setOf(EnhanceKind.EXTERNAL), force = true)
        assertTrue(withExternal.isEmpty())
    }

    @Test fun aWaitingOrRunningJobIsNotQueuedTwice() {
        val running = task("a", EnhanceKind.VOCALS, state = "running")
        val out = EnhanceTasks.add(listOf(running), listOf(song("a")), setOf(EnhanceKind.VOCALS, EnhanceKind.LYRICS), force = false)
        assertEquals("only the operation that wasn't already queued is added", listOf(EnhanceKind.VOCALS, EnhanceKind.LYRICS), out.map { it.kind })
        assertTrue("a batch leaves finished songs alone", !out.last().force)
    }

    @Test fun aFinishedJobDoesNotBlockAnotherOne() {
        val done = task("a", EnhanceKind.LYRICS, state = "done")
        val out = EnhanceTasks.add(listOf(done), listOf(song("a")), setOf(EnhanceKind.LYRICS), force = true)
        assertEquals(2, out.size)
        assertNotEquals(done.id, out.last().id)
    }

    @Test fun progressBelongsToTheJobThatReportedIt() {
        val first = task("a", EnhanceKind.LYRICS, id = 1L)
        val second = task("a", EnhanceKind.LYRICS, id = 2L)
        // The listener cancelled the first and queued the same operation again while it was unwinding.
        val list = listOf(first.copy(state = "cancelled"), second)
        val after = EnhanceTasks.withProgress(list, first.id, 0.9f)
        assertEquals("the cancelled job took the update", 0.9f, after[0].progress, 0f)
        assertEquals("the new job was touched", 0f, after[1].progress, 0f)
        val state = EnhanceTasks.withState(list, first.id, "cancelled", 0f, null)
        assertEquals("cancelled", state[0].state)
        assertEquals("the new job must stay queued", "queued", state[1].state)
    }

    @Test fun cancellingLeavesFinishedJobsAlone() {
        val list = listOf(
            task("a", state = "queued"),
            task("a", EnhanceKind.VOCALS, state = "done", id = 2L),
            task("b", EnhanceKind.INSTRUMENTAL, state = "failed", id = 3L),
        )
        val all = EnhanceTasks.cancelAll(list)
        assertEquals("cancelled", all[0].state)
        assertEquals("done", all[1].state)
        assertEquals("failed", all[2].state)
        val one = EnhanceTasks.cancel(list, "a", EnhanceKind.LYRICS)
        assertEquals("cancelled", one[0].state)
        assertEquals("done", one[1].state)
    }

    @Test fun aRestartInterruptsWhatWasRunningAndResumeDoesNotDoubleIt() {
        val loaded = listOf(task("a", state = "running", progress = 0.4f), task("b", state = "queued", id = 2L))
        val interrupted = EnhanceTasks.interrupted(loaded + task("c", state = "done", id = 3L))
        assertEquals("interrupted", interrupted[0].state)
        assertEquals("interrupted", interrupted[1].state)
        assertEquals("done", interrupted[2].state)
        assertEquals("a fresh job already waiting wins over the resumed one", "interrupted", EnhanceTasks.resumed(interrupted + task("a", id = 4L))[0].state)
        assertEquals("queued", EnhanceTasks.resumed(interrupted)[0].state)
    }

    @Test fun theSavedQueueAndOneQueuedWhileReadingItBothSurvive() {
        val saved = listOf(task("a", id = 1L), task("b", id = 2L))
        val current = listOf(task("c", id = 3L))
        val merged = EnhanceTasks.merge(saved, current)
        assertEquals(setOf(1L, 2L, 3L), merged.map { it.id }.toSet())
    }

    @Test fun theQueueListShowsWhatIsRunningThenWhatJustFinished() {
        val list = (1..12).map { task("s$it", state = "queued", id = it.toLong()) } +
            listOf(task("done1", state = "done", id = 100L), task("done2", state = "done", id = 101L))
        val shown = EnhanceTasks.shown(list, count = 4)
        assertEquals(4, shown.size)
        assertTrue("the first four are the waiting ones", shown.take(4).all { it.state == "queued" })
        // With little live work, the latest finished jobs fill the rest.
        val mostlyDone = EnhanceTasks.shown(list.filter { it.state == "done" } + task("q", state = "running", id = 200L), count = 3)
        assertEquals(listOf("q", "done1", "done2"), mostlyDone.map { it.song.id })
    }

    @Test fun stoppingASeparationStopsTheWordTimingsQueuedBehindIt() {
        val older = task("a", EnhanceKind.VOCALS, id = 4L)
        val newer = task("a", EnhanceKind.VOCALS, id = 5L)
        val one = EnhanceTasks.cancelTask(listOf(older, newer), older.id)
        assertEquals("cancelled", one[0].state)
        assertEquals("queued", one[1].state)
    }

    @Test fun clearingKeepsOnlyLiveWork() {
        val list = listOf(
            task("a", state = "queued"), task("b", state = "done", id = 2L),
            task("c", state = "failed", id = 3L), task("d", state = "interrupted", id = 4L),
        )
        assertEquals(listOf("a", "d"), EnhanceTasks.clearFinished(list).map { it.song.id })
        assertEquals(listOf("a", "b", "c"), EnhanceTasks.discardInterrupted(list).map { it.song.id })
    }
}
