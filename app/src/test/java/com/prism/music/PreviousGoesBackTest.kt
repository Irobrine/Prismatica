package com.prism.music

import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.prism.music.data.model.Song
import com.prism.music.playback.HistoryShuffleOrder
import com.prism.music.playback.seekToPreviousAsChosen
import com.prism.music.playback.toMediaItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 1.8.5: "Previous goes back a song" (off by default, as Prism was). Off, partway through a song Previous starts it
 * over; on, it always goes to the song before, in the order a shuffled queue played.
 */
@RunWith(RobolectricTestRunner::class)
class PreviousGoesBackTest {
    private val app get() = ApplicationProvider.getApplicationContext<PrismApp>()
    private lateinit var player: ExoPlayer
    private val songs = (0 until 4).map { Song("s$it", "Song $it") }

    @Before fun setUp() {
        player = ExoPlayer.Builder(app).build()
        player.setShuffleOrder(HistoryShuffleOrder(IntArray(0)) { player.currentMediaItemIndex })
        player.setMediaItems(songs.map { it.toMediaItem() }, 1, 0)
    }

    @After fun tearDown() = player.release()

    @Test fun offPartWayThroughStartsTheSongOver() {
        player.seekTo(1, 30_000)
        player.seekToPreviousAsChosen(previousGoesBack = false)
        assertEquals("the playing song", 1, player.currentMediaItemIndex)
        assertEquals("from its start", 0, player.currentPosition)
    }

    @Test fun onPartWayThroughGoesToTheSongBefore() {
        player.seekTo(1, 30_000)
        player.seekToPreviousAsChosen(previousGoesBack = true)
        assertEquals("the song before", 0, player.currentMediaItemIndex)
    }

    @Test fun onNearTheStartAlsoGoesToTheSongBefore() {
        player.seekTo(1, 1_000)
        player.seekToPreviousAsChosen(previousGoesBack = true)
        assertEquals(0, player.currentMediaItemIndex)
    }

    /** Nothing before the first song: it starts that one over instead of doing nothing. */
    @Test fun onAtTheFirstSongStartsItOver() {
        player.seekTo(0, 30_000)
        player.seekToPreviousAsChosen(previousGoesBack = true)
        assertEquals(0, player.currentMediaItemIndex)
        assertEquals(0, player.currentPosition)
    }

    /** The player's own previous item, so a shuffled queue goes back through what played. */
    @Test fun onShuffledFollowsThePlayOrder() {
        player.shuffleModeEnabled = true
        // 2 plays first, then 0 (where it is now), then 3, then 1.
        player.setShuffleOrder(HistoryShuffleOrder(intArrayOf(2, 0, 3, 1)) { player.currentMediaItemIndex })
        player.seekTo(0, 30_000)
        player.seekToPreviousAsChosen(previousGoesBack = true)
        assertEquals("the song that played before it", 2, player.currentMediaItemIndex)
    }
}
