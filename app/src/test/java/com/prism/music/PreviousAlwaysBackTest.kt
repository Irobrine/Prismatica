package com.prism.music

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.core.net.toUri
import com.prism.music.playback.LyricsOverlayPlayer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * 1.1.3: Settings → Gestures & buttons → "Previous always goes back". Off (as Prism comes), Previous goes back to the
 * song before only near the start of this one and otherwise starts it over, as Media3's own seekToPrevious does. On, it
 * goes to the song before wherever the playhead is — for Prism's buttons and for the controls outside it (the
 * notification, the lock screen, a car), which is [LyricsOverlayPlayer]'s previous — and starts this one over only when
 * there's nothing before it.
 *
 * Four ten-second WAVs are the queue, so the player has real positions to be past (the point where Previous restarts
 * instead of going back is three seconds).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PreviousAlwaysBackTest {
    private val app = RuntimeEnvironment.getApplication()
    private val made = lazy { AppContainer(app) }
    private val c by made
    private lateinit var player: ExoPlayer
    private val tones = ArrayList<File>()
    private var alwaysBackWas = false

    @Before fun setUp() {
        alwaysBackWas = c.settings.current.behavior.previousAlwaysBack
        player = ExoPlayer.Builder(app).build()
        player.setMediaItems((0 until 4).map { MediaItem.fromUri(tone(it).toUri()) }, 0, 0)
        player.prepare()
        c.player.testPlayer = player
        idle("player ready") { player.playbackState == Player.STATE_READY }
    }

    @After fun tearDown() {
        runCatching { player.release() }
        c.player.testPlayer = null
        tones.forEach { runCatching { it.delete() } }
        // The settings store is one for the whole test run: leave it as this test found it.
        runCatching { alwaysBack(alwaysBackWas) }
        made.closeAfterTest()
    }

    /** A ten-second silent 8-bit WAV, so the player has a timeline and real positions. */
    private fun tone(n: Int): File {
        val rate = 8000
        val samples = rate * 10
        val data = ByteArray(samples) { 128.toByte() }
        val f = File(app.cacheDir, "previous-$n.wav")
        f.outputStream().use { out ->
            fun le32(v: Int) = out.write(byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(), ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte()))
            fun le16(v: Int) = out.write(byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte()))
            out.write("RIFF".toByteArray()); le32(36 + data.size); out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1); le32(rate); le32(rate); le16(1); le16(8)
            out.write("data".toByteArray()); le32(data.size); out.write(data)
        }
        tones += f
        return f
    }

    private fun idle(label: String = "condition", what: () -> Boolean) {
        val started = System.currentTimeMillis()
        val main = shadowOf(android.os.Looper.getMainLooper())
        val until = started + 20_000
        while (!what() && System.currentTimeMillis() < until) {
            main.idle()
            // Robolectric pauses every looper. Media3 loads the queue's files on its playback thread and then waits out a
            // short delay on that thread's own looper, which only moves when the test moves it: left alone, the player
            // sat BUFFERING for the whole wait. Give each looper the few milliseconds a phone's clock would.
            org.robolectric.shadows.ShadowLooper.getAllLoopers().forEach { looper ->
                if (looper !== android.os.Looper.getMainLooper()) {
                    runCatching { shadowOf(looper).idleFor(5, java.util.concurrent.TimeUnit.MILLISECONDS) }
                }
            }
            Thread.sleep(2)
        }
        main.idle()
        val ok = what()
        assertTrue("never happened: $label", ok)
    }

    private fun alwaysBack(on: Boolean) {
        c.settings.setPreviousAlwaysBack(on)
        idle("setting previousAlwaysBack=$on") { c.settings.current.behavior.previousAlwaysBack == on }
    }

    /** Into [index] at [ms]: past the point where Media3's Previous restarts instead of going back (3 s). */
    private fun intoTheSong(index: Int, ms: Long = 5_000) {
        player.seekTo(index, ms)
        idle("into song $index at $ms") { player.currentMediaItemIndex == index && player.currentPosition >= ms }
    }

    @Test fun itIsOffAsPrismComes() {
        assertFalse(com.prism.music.data.prefs.AppSettings().behavior.previousAlwaysBack)
    }

    @Test fun offPreviousRestartsThisSongPastItsStart() {
        alwaysBack(false)
        intoTheSong(1)
        c.player.previous()
        idle("this song from its start") { player.currentPosition == 0L }
        assertEquals("still the same song", 1, player.currentMediaItemIndex)
        assertEquals("from its start", 0, player.currentPosition)
    }

    @Test fun offPreviousGoesBackNearTheStartOfTheSong() {
        alwaysBack(false)
        intoTheSong(1, ms = 1_000)
        c.player.previous()
        idle("the song before") { player.currentMediaItemIndex == 0 }
        assertEquals("the song before", 0, player.currentMediaItemIndex)
    }

    @Test fun onPreviousGoesToTheSongBeforeWhereverItIs() {
        alwaysBack(true)
        intoTheSong(2)
        c.player.previous()
        idle("the song before, again") { player.currentMediaItemIndex == 1 }
        assertEquals("the song before, not this one from its start", 1, player.currentMediaItemIndex)
    }

    @Test fun onPreviousAtTheFirstSongStartsItOver() {
        alwaysBack(true)
        intoTheSong(0)
        c.player.previous()
        idle("this song from its start") { player.currentPosition == 0L }
        assertEquals("nothing before it", 0, player.currentMediaItemIndex)
        assertEquals("so this one from its start", 0, player.currentPosition)
    }

    @Test fun theControlsOutsidePrismFollowIt() {
        alwaysBack(false)
        val session = LyricsOverlayPlayer(player) { c.settings.current.behavior.previousAlwaysBack }
        intoTheSong(2)
        session.seekToPrevious()
        idle("this song from its start") { player.currentPosition == 0L }
        assertEquals("off, as before: this one from its start", 2, player.currentMediaItemIndex)
        alwaysBack(true)
        intoTheSong(2)
        session.seekToPrevious()
        idle("the song before, again") { player.currentMediaItemIndex == 1 }
        assertEquals("on: the song before", 1, player.currentMediaItemIndex)
    }
}
