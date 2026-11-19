package com.prism.music

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.prism.music.data.model.Song
import com.prism.music.playback.HistoryShuffleOrder
import com.prism.music.playback.NormalizerProcessor
import com.prism.music.playback.toMediaItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The sleep timer on the real [com.prism.music.playback.PlayerConnection] and a real player: the BPM limit passes over
 * faster and unknown-tempo songs (and stops rather than skipping for ever), the temporary override brings them back and
 * re-enabling checks the current song at once, +5/-5 move the live deadline, and the listener's volume comes back when
 * the session ends or is cancelled without touching the saved choices.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PrismApp::class)
class SleepTimerConnectionTest {
    private val app get() = ApplicationProvider.getApplicationContext<PrismApp>()
    private val c get() = app.container
    private lateinit var player: ExoPlayer

    private fun song(id: String, bpm: Double?) = Song(id, "Song $id").also {
        if (bpm != null) {
            app.getSharedPreferences("dj_quick", Context.MODE_PRIVATE).edit()
                .putString(id, "$bpm|5.0|").commit()
        }
    }

    @Before fun setUp() {
        app.getSharedPreferences("sleep_timer", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("dj_quick", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("mix_tempo", Context.MODE_PRIVATE).edit().clear().commit()
        NormalizerProcessor.fadeVolume = 1f
        NormalizerProcessor.sleepVolume = 1f
        player = ExoPlayer.Builder(app).build()
        player.setShuffleOrder(HistoryShuffleOrder(IntArray(0)) { player.currentMediaItemIndex })
        c.player.testPlayer = player
    }

    @After fun tearDown() {
        c.player.cancelSleepTimer()
        c.player.testPlayer = null
        player.release()
        NormalizerProcessor.fadeVolume = 1f
        NormalizerProcessor.sleepVolume = 1f
        app.getSharedPreferences("sleep_timer", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("dj_quick", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("mix_tempo", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun queue(vararg songs: Song) {
        player.setMediaItems(songs.map { it.toMediaItem() })
        player.prepare()
    }

    private fun timer(maxBpm: Int = 120, volume: Float = 1f) {
        c.sleepPrefs.setMinutes(30)
        c.sleepPrefs.setMaxBpm(maxBpm)
        c.sleepPrefs.setFinishSong(false)
        c.sleepPrefs.setVolume(volume)
        c.player.startSleepTimer()
    }

    @Test fun aSongOverTheLimitOrWithNoTempoIsPassedOverAtOnce() {
        queue(song("fast", 150.0), song("unknown", null), song("slow", 100.0))
        timer()
        // The song playing at the start is over the limit: the first allowed one after it plays.
        assertEquals(2, player.currentMediaItemIndex)
        assertTrue(c.player.sleepBlocksBpm("fast"))
        assertTrue(c.player.sleepBlocksBpm("unknown"))
        assertFalse(c.player.sleepBlocksBpm("slow"))
    }

    @Test fun withNoAllowedSongLeftItStopsInsteadOfSkippingForEver() {
        queue(song("fast", 150.0), song("unknown", null))
        player.play()
        timer()
        assertEquals(0, player.currentMediaItemIndex)
        assertFalse(player.playWhenReady)
    }

    @Test fun theTemporaryOverrideBringsFilteredSongsBackAndReEnablingChecksAtOnce() {
        queue(song("fast", 150.0), song("slow", 100.0))
        timer()
        assertEquals(1, player.currentMediaItemIndex)
        // Off: the limit holds nothing back (the song picked plays as it is).
        c.player.setSleepBpmDisabled(true)
        assertFalse(c.player.sleepBlocksBpm("fast"))
        c.player.skipTo(0)
        assertEquals(0, player.currentMediaItemIndex)
        // On again: the current song is checked immediately, not at the next song.
        c.player.setSleepBpmDisabled(false)
        assertEquals(1, player.currentMediaItemIndex)
        // The saved limit was never touched by the override.
        assertEquals(120, c.sleepPrefs.choices.value.maxBpm)
    }

    @Test fun plusAndMinusFiveMoveTheActiveDeadline() {
        queue(song("slow", 100.0))
        timer()
        val before = c.player.sleepAt.value
        c.player.adjustSleepTimer(5)
        val after = c.player.sleepAt.value
        assertEquals(5 * 60_000.0, (after - before).toDouble(), 2_000.0)
        c.player.adjustSleepTimer(-5)
        assertEquals(before.toDouble(), c.player.sleepAt.value.toDouble(), 2_000.0)
    }

    @Test fun theBpmFilterIsOffOnceTheTimerHasRunOut() {
        queue(song("slow", 100.0))
        c.sleepPrefs.setMinutes(1)
        c.sleepPrefs.setMaxBpm(120)
        c.sleepPrefs.setFinishSong(false)
        c.sleepPrefs.setVolume(1f)
        c.player.startSleepTimer()
        assertEquals(120, c.player.sleepMaxBpm.value)
        // -5 from the one-minute countdown leaves nothing: the session runs out, and the filter was only for that session.
        c.player.adjustSleepTimer(-5)
        assertEquals(0, c.sleepPrefs.choices.value.maxBpm)
        assertEquals(0, c.player.sleepMaxBpm.value)
        assertFalse(c.player.sleepBlocksBpm("fast"))
    }

    @Test fun finishingTheCurrentSongAlsoPutsTheFilterBackOff() {
        queue(song("slow", 100.0))
        c.sleepPrefs.setMinutes(10)
        c.sleepPrefs.setMaxBpm(120)
        c.sleepPrefs.setFinishSong(true)
        c.sleepPrefs.setVolume(1f)
        c.player.startSleepTimer()
        assertEquals(120, c.player.sleepMaxBpm.value)
        c.player.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
        assertEquals(0L, c.player.sleepAt.value)
        assertEquals(0, c.sleepPrefs.choices.value.maxBpm)
    }

    @Test fun cancellingKeepsTheBpmChoiceForNextTime() {
        queue(song("slow", 100.0))
        timer(maxBpm = 120)
        c.player.cancelSleepTimer()
        assertEquals(120, c.sleepPrefs.choices.value.maxBpm)
    }

    @Test fun theEditMenusVolumeChangesTheSessionAndIsRemembered() {
        queue(song("slow", 100.0))
        timer(volume = 1f)
        c.player.setSleepVolume(0.25f)
        assertEquals(NormalizerProcessor.volumeFor(0.25f), NormalizerProcessor.sleepVolume, 1e-5f)
        // A drag is heard at once but saved when the finger lifts.
        assertEquals(1f, c.sleepPrefs.choices.value.volume, 0f)
        c.player.setSleepVolume(0.25f, remember = true)
        assertEquals(0.25f, c.sleepPrefs.choices.value.volume, 0f)
        c.player.cancelSleepTimer()
        assertEquals(1f, NormalizerProcessor.sleepVolume, 0f)
        assertEquals(0.25f, c.sleepPrefs.choices.value.volume, 0f)
    }

    @Test fun theSleepVolumeIsAppliedAndTheListenersOwnComesBack() {        queue(song("slow", 100.0))
        timer(volume = 0.3f)
        assertEquals(NormalizerProcessor.volumeFor(0.3f), NormalizerProcessor.sleepVolume, 1e-5f)
        // Cancelling is not the volume slider: the saved choices (and the saved volume) are untouched.
        c.player.cancelSleepTimer()
        assertEquals(1f, NormalizerProcessor.sleepVolume, 0f)
        assertEquals(1f, NormalizerProcessor.fadeVolume, 0f)
        assertEquals(0.3f, c.sleepPrefs.choices.value.volume, 0f)
        assertEquals(120, c.sleepPrefs.choices.value.maxBpm)
    }

    @Test fun thereIsOnlyOneSessionAndStartingAgainReplacesIt() {
        queue(song("slow", 100.0))
        timer()
        val first = c.player.sleepAt.value
        c.sleepPrefs.setMinutes(45)
        c.player.startSleepTimer()
        val second = c.player.sleepAt.value
        assertNotEquals(first, second)
        assertEquals(45 * 60_000.0, (second - System.currentTimeMillis()).toDouble(), 5_000.0)
        c.player.cancelSleepTimer()
        assertEquals(0L, c.player.sleepAt.value)
        // Cancelling leaves the choices for the next session.
        assertEquals(45, c.sleepPrefs.choices.value.minutes)
    }
}
