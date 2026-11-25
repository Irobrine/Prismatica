package com.prism.music

import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.AppSettings
import com.prism.music.playback.HistoryShuffleOrder
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Settings → Playback → "Append to Play Next" (off by default): Play next puts its songs at the end of "Next in queue"
 * rather than straight after the one playing, so several of them keep the order they were tapped in. Off, the old
 * behavior: the last one tapped plays next.
 */
@RunWith(RobolectricTestRunner::class)
class AppendToPlayNextTest {
    private val app get() = ApplicationProvider.getApplicationContext<PrismApp>()
    private val c get() = app.container
    private lateinit var player: ExoPlayer

    private fun songs(vararg ids: String) = ids.map { Song(it, "Song $it") }
    private fun queueIds() = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }

    private fun append(on: Boolean) {
        runBlocking { c.settings.setAppendToPlayNext(on) }
        runBlocking { withTimeout(10_000) { c.settings.flow.first { it.behavior.appendToPlayNext == on } } }
    }

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

    private fun playing() = c.player.playQueue(songs("p0", "p1", "p2"), 0, QueueSource(QueueKind.PLAYLIST, "A playlist"))

    @Test fun offTheLastSongTappedIsNext() {
        playing()
        c.player.playNext(songs("n1"), null)
        c.player.playNext(songs("n2"), null)
        assertEquals(listOf("p0", "n2", "n1", "p1", "p2"), queueIds())
    }

    @Test fun onTheSongsKeepTheOrderTheyWereTappedIn() {
        playing()
        append(true)
        c.player.playNext(songs("n1"), null)
        c.player.playNext(songs("n2"), null)
        c.player.playNext(songs("n3"), null)
        assertEquals(listOf("p0", "n1", "n2", "n3", "p1", "p2"), queueIds())
    }

    @Test fun onACopyAlreadyQueuedMovesToTheEndRatherThanGoingInTwice() {
        playing()
        append(true)
        c.player.playNext(songs("a"), null)
        c.player.playNext(songs("b"), null)
        c.player.playNext(songs("a"), null)
        assertEquals(listOf("p0", "b", "a", "p1", "p2"), queueIds())
    }

    @Test fun onWithNothingQueuedTheFirstPlayNextIsStillNext() {
        playing()
        append(true)
        c.player.playNext(songs("n1"), null)
        assertEquals(listOf("p0", "n1", "p1", "p2"), queueIds())
    }

    @Test fun onWhileShuffledTheSecondPickFollowsTheFirstInPlayOrder() {
        val s = songs("p0", "p1", "p2", "p3", "p4")
        c.player.playQueue(s, 0, QueueSource(QueueKind.PLAYLIST, "A playlist"), shuffle = true)
        append(true)
        val coming = upcoming()
        assertEquals(4, coming.size)
        val first = coming[1]
        val second = coming[3]
        c.player.playNext(s.first { it.id == first })
        c.player.playNext(s.first { it.id == second })
        assertEquals(listOf(first, second), upcoming().take(2))
    }

    /** What's coming, in the order it will really play. */
    private fun upcoming(): List<String> {
        val p = player
        val t = p.currentTimeline
        val list = ArrayList<String>()
        var i = t.getNextWindowIndex(p.currentMediaItemIndex, androidx.media3.common.Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
        while (i != androidx.media3.common.C.INDEX_UNSET) {
            list += p.getMediaItemAt(i).mediaId
            i = t.getNextWindowIndex(i, androidx.media3.common.Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
        }
        return list
    }

    /** The setting reads back from the store, and the queue's "Next in queue" is the listener's own songs. */
    @Test fun theSettingIsStoredAndTheQueuedSongsAreMarked() {
        append(true)
        val stored: AppSettings = runBlocking { c.settings.stored.value }
        assertEquals(true, stored.behavior.appendToPlayNext)
        playing()
        c.player.playNext(songs("n1"), null)
        assertEquals(setOf("n1"), c.queue.userQueued.value)
    }
}
