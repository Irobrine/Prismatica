package com.prism.music

import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.prism.music.data.model.Song
import com.prism.music.playback.HistoryShuffleOrder
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * "Next in queue" is what the listener put there. A song taken out of the queue stops being one of them (the queue's
 * heading and "Append to Play Next"'s end both read that set), Undo puts it back as one, and taking out one copy of a
 * song that's in the queue twice leaves the other copy marked.
 */
@RunWith(RobolectricTestRunner::class)
class QueueMarksTest {
    private val app get() = ApplicationProvider.getApplicationContext<PrismApp>()
    private val c get() = app.container
    private lateinit var player: ExoPlayer
    private val playlist = QueueSource(QueueKind.PLAYLIST, "A playlist")

    private fun songs(vararg ids: String) = ids.map { Song(it, "Song $it") }
    private fun indexOf(id: String) = (0 until player.mediaItemCount).first { player.getMediaItemAt(it).mediaId == id }

    @Before fun setUp() {
        runBlocking { c.settings.editNow { it.clear() } }
        player = ExoPlayer.Builder(app).build()
        player.setShuffleOrder(HistoryShuffleOrder(IntArray(0)) { player.currentMediaItemIndex })
        c.player.testPlayer = player
        c.queue.smartShuffle.value = false
    }

    @After fun tearDown() {
        c.player.testPlayer = null
        player.release()
        c.queue.smartShuffle.value = false
        runBlocking { c.settings.editNow { it.clear() } }
    }

    @Test fun aSongTakenOutStopsBeingOneOfTheListenersNext() {
        c.player.playQueue(songs("p0", "p1", "p2"), 0, playlist)
        c.player.playNext(songs("n1"), null)
        assertEquals(setOf("n1"), c.queue.userQueued.value)
        // The queue's swipe: taken out, and out of Next in queue with it.
        val undo = c.player.removeForUndo(indexOf("n1"))
        assertEquals("a song that's gone no longer marks the queue", emptySet<String>(), c.queue.userQueued.value)
        undo!!()
        assertEquals("Undo puts it back as one of the listener's", setOf("n1"), c.queue.userQueued.value)
    }

    @Test fun takingSeveralOutDropsAllOfThem() {
        c.player.playQueue(songs("p0", "n1", "n2", "p3"), 0, playlist)
        c.queue.userQueued.value = setOf("n1", "n2")
        c.player.removeMany(listOf(1, 2))
        assertEquals(emptySet<String>(), c.queue.userQueued.value)
    }

    @Test fun removingOneCopyLeavesTheOtherMarked() {
        // A playlist that lists the same song twice (ids are what the set knows).
        c.player.playQueue(songs("p0", "a", "b", "a"), 0, playlist)
        c.queue.userQueued.value = setOf("a", "b")
        c.player.removeAt(3)
        assertEquals("the other copy is still in the queue", setOf("a", "b"), c.queue.userQueued.value)
        c.player.removeAt(1)
        assertEquals(setOf("b"), c.queue.userQueued.value)
    }
}
