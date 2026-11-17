package com.prism.music

import androidx.media3.exoplayer.ExoPlayer
import com.prism.music.data.model.Song
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.playback.toMediaItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Library → Songs → Shuffle with a large library (1.1.1). Before, the whole list's media items were built on the main
 * thread inside the tap, so 800 songs (and far more) waited before the first sound. A shuffled start now builds only a
 * small window around the random song and puts the rest in behind it, off the main thread, in the same random order.
 *
 * These tests drive a real ExoPlayer (Robolectric) through [com.prism.music.playback.PlayerConnection.playQueue] and
 * measure what was built before playback could begin, what the whole queue becomes, and its play order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ShuffleStartTest {
    private val app = RuntimeEnvironment.getApplication()
    private var player: ExoPlayer? = null
    private var container: AppContainer? = null
    private val songs = (0 until 800).map { Song("s$it", "Song $it", artists = listOf(com.prism.music.data.model.ArtistRef("Artist ${it % 20}")), durationSec = 200) }

    private fun start() {
        val c = AppContainer(app)
        container = c
        val p = ExoPlayer.Builder(app).build()
        player = p
        c.player.testPlayer = p
    }

    private fun idleUntil(what: () -> Boolean) {
        val main = shadowOf(android.os.Looper.getMainLooper())
        val until = System.currentTimeMillis() + 20_000
        while (!what() && System.currentTimeMillis() < until) {
            main.idle()
            Thread.sleep(2)
        }
        main.idle()
    }

    private fun source(title: String) = QueueSource(QueueKind.LIBRARY, title)

    /** The queue indices in the order they'll play, from the current song on. */
    private fun playOrder(p: ExoPlayer): List<Int> {
        val t = p.currentTimeline
        val out = mutableListOf<Int>()
        var i = t.getFirstWindowIndex(true)
        while (i != androidx.media3.common.C.INDEX_UNSET && out.size < t.windowCount) {
            out += i
            i = t.getNextWindowIndex(i, androidx.media3.common.Player.REPEAT_MODE_OFF, true)
        }
        return out
    }

    @Test fun eightHundredSongsShuffleStartsOnAFewAndQueuesTheRest() {
        start()
        val p = player!!
        val c = container!!
        val at = System.nanoTime()
        c.player.playQueue(songs, 0, source("Songs"), shuffle = true)
        val syncMs = (System.nanoTime() - at) / 1_000_000.0
        val measured = c.player.lastQueueStart!!
        println("[shuffle] 800: ${measured.builtBeforePlay} items built in ${measured.ms} ms before play (the whole tap took ${"%.1f".format(syncMs)} ms)")

        assertEquals("the whole queue", 800, measured.songs)
        assertTrue("shuffled", measured.shuffled)
        assertTrue("only a window before play, not 800 items: ${measured.builtBeforePlay}", measured.builtBeforePlay <= 5)
        assertEquals("the window is in the player at once", measured.builtBeforePlay, p.mediaItemCount)
        assertTrue("the random song plays first", p.currentMediaItem?.mediaId in songs.map { it.id })

        idleUntil { p.mediaItemCount == 800 }
        assertEquals("the rest joined", 800, p.mediaItemCount)
        val order = playOrder(p)
        assertEquals("every song once, in the order it plays", (0 until 800).toList(), order.sorted())
        assertEquals("the play order is the queue's own", 800, order.toSet().size)
        assertEquals("the window's first song stays first", p.currentMediaItemIndex, order.first())
        // Not accidentally in the library's order: a fresh random order for every start.
        assertTrue("shuffled, not 0..799 in order", order != (0 until 800).toList())
    }

    @Test fun aSmallLibraryIsStillAllThereAtOnce() {
        start()
        val p = player!!
        val small = songs.take(40)
        container!!.player.playQueue(small, 0, source("Songs"), shuffle = true)
        val measured = container!!.player.lastQueueStart!!
        assertEquals(40, measured.songs)
        assertEquals("a small list needs no filling", 40, measured.builtBeforePlay)
        assertEquals(40, p.mediaItemCount)
    }

    @Test fun aVeryLargeLibraryStartsJustAsQuickly() {
        start()
        val p = player!!
        val huge = (0 until 5_000).map { Song("h$it", "Huge $it", durationSec = 180) }
        val at = System.nanoTime()
        container!!.player.playQueue(huge, 0, source("Songs"), shuffle = true)
        val syncMs = (System.nanoTime() - at) / 1_000_000.0
        val measured = container!!.player.lastQueueStart!!
        println("[shuffle] 5,000: ${measured.builtBeforePlay} items built in ${measured.ms} ms before play (the whole tap took ${"%.1f".format(syncMs)} ms)")
        assertTrue("a window, not 5,000 items: ${measured.builtBeforePlay}", measured.builtBeforePlay <= 5)
        idleUntil { p.mediaItemCount == 5_000 }
        assertEquals(5_000, p.mediaItemCount)
        assertEquals((0 until 5_000).toList(), playOrder(p).sorted())
    }

    @Test fun shuffleWhilePlayingAndRepeatedTapsKeepTheLastQueue() {
        start()
        val p = player!!
        val c = container!!
        c.player.playQueue(songs, 0, source("Songs"), shuffle = true)
        idleUntil { p.mediaItemCount == 800 }
        assertEquals(800, p.mediaItemCount)

        // Tapped again while a song plays: another random start, another queue, and only its window built up front.
        val next = (0 until 800).map { Song("n$it", "Next $it", durationSec = 200) }
        c.player.playQueue(next, 0, source("Songs"), shuffle = true)
        assertTrue("the second start builds a window too", c.player.lastQueueStart!!.builtBeforePlay <= 5)
        idleUntil { p.mediaItemCount == 800 && p.currentMediaItem?.mediaId?.startsWith("n") == true }
        assertEquals(800, p.mediaItemCount)
        assertTrue("the last queue's songs", (0 until 800).all { p.getMediaItemAt(it).mediaId.startsWith("n") })

        // Rapid taps: each replaces the one before, and no older fill adds its songs to the last queue.
        repeat(6) { round ->
            val list = (0 until 800).map { Song("r$round-$it", "Round $round $it", durationSec = 200) }
            c.player.playQueue(list, 0, source("Songs"), shuffle = true)
            assertTrue("round $round built a window", c.player.lastQueueStart!!.builtBeforePlay <= 5)
        }
        idleUntil { p.mediaItemCount == 800 && p.currentMediaItem?.mediaId?.startsWith("r5-") == true }
        assertEquals("the last tap's queue, no duplicates from the others", 800, p.mediaItemCount)
        assertTrue("only the last round's songs", (0 until p.mediaItemCount).all { p.getMediaItemAt(it).mediaId.startsWith("r5-") })
        assertEquals(800, playOrder(p).toSet().size)
    }

    /**
     * What the tap used to wait on: making every song's media item on the calling thread. The same list, measured, so
     * the difference is reproducible here and in future versions (the shuffle start must stay far below it).
     */
    @Test fun aWindowIsFarCheaperThanBuildingEveryItem() {
        // Warm the classes and caches the way the first start's window does.
        songs.take(5).forEach { it.toMediaItem() }
        val wholeAt = System.nanoTime()
        songs.forEach { it.toMediaItem() }
        val wholeMs = (System.nanoTime() - wholeAt) / 1_000_000.0
        val windowAt = System.nanoTime()
        songs.take(5).forEach { it.toMediaItem() }
        val windowMs = (System.nanoTime() - windowAt) / 1_000_000.0
        println("[shuffle] 800 items built whole: ${"%.1f".format(wholeMs)} ms; a 5-song window: ${"%.1f".format(windowMs)} ms")
        assertTrue("a window (${"%.1f".format(windowMs)} ms) is cheaper than the whole list (${"%.1f".format(wholeMs)} ms)", windowMs < wholeMs)
    }

    @After fun after() {
        player?.release()
        player = null
        container?.let { lazyOf(it).closeAfterTest() }
        container = null
    }
}
