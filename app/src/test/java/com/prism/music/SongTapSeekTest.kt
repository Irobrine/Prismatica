package com.prism.music

import androidx.media3.exoplayer.ExoPlayer
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tapping another song in the list already playing moves to it in the queue instead of building every media source of
 * the list again. On a 2,000-song playlist that start held the tap's frame (and the mini player's first frames) for
 * 20-50 ms; the queue is already there, so a seek is all the tap has to do.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SongTapSeekTest {
    private val made = lazy { AppContainer(RuntimeEnvironment.getApplication()) }
    private val container by made
    private var exo: ExoPlayer? = null

    private val songs = listOf("One", "Two", "Three").mapIndexed { i, n ->
        Song("s$i", n, artists = listOf(ArtistRef("Artist")), durationSec = 200)
    }
    private val source = QueueSource(QueueKind.OTHER, "a list")

    @Before fun setUp() {
        val p = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        exo = p
        container.player.testPlayer = p
        p.addListener(container.player)
    }

    @After fun close() {
        runCatching { container.player.stop() }
        runCatching { exo?.release() }
        container.player.testPlayer = null
        made.closeAfterTest()
    }

    private fun play(at: Int, from: QueueSource = source, shuffle: Boolean = false) {
        container.player.playQueue(songs, at, from, shuffle = shuffle)
        val until = System.currentTimeMillis() + 10_000
        while (container.player.currentSong.value?.id != songs[at].id && System.currentTimeMillis() < until) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertEquals("the tapped song is playing", songs[at].id, container.player.currentSong.value?.id)
    }

    @Test fun aTapInTheSameListMovesToItWithoutRebuilding() {
        play(0)
        val built = container.player.lastQueueStart ?: error("the first queue wasn't built")
        play(1)
        assertSame("the queue wasn't built again", built, container.player.lastQueueStart)
        assertEquals("on the tapped song", 1, exo!!.currentMediaItemIndex)
        assertEquals("the whole list is still queued", songs.size, exo!!.mediaItemCount)
    }

    @Test fun anotherListStillBuildsItsOwnQueue() {
        play(0)
        val built = container.player.lastQueueStart ?: error("the first queue wasn't built")
        play(1, from = QueueSource(QueueKind.OTHER, "another list"))
        assertNotSame("the new list's queue was built", built, container.player.lastQueueStart)
    }

    @Test fun aShuffledStartStillBuildsTheQueue() {
        play(0)
        val built = container.player.lastQueueStart ?: error("the first queue wasn't built")
        container.player.playQueue(songs, 1, source, shuffle = true)
        val until = System.currentTimeMillis() + 10_000
        while (container.player.lastQueueStart === built && System.currentTimeMillis() < until) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertNotSame("a shuffled start is a new queue", built, container.player.lastQueueStart)
    }
}
