package com.prism.music

import android.content.Context
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.prism.music.data.model.Song
import com.prism.music.data.sleep.SleepAnalysis
import com.prism.music.data.sleep.SleepComponents
import com.prism.music.data.sleep.SleepScore
import com.prism.music.playback.HistoryShuffleOrder
import com.prism.music.playback.NormalizerProcessor
import com.prism.music.playback.toMediaItem
import com.prism.music.playback.toSong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
        app.getSharedPreferences("sleep_scores", Context.MODE_PRIVATE).edit().clear().commit()
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
        app.getSharedPreferences("sleep_scores", Context.MODE_PRIVATE).edit().clear().commit()
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

    @Test fun theConfigMenusVolumeIsHeardAtOnceAndClosingPutsItBack() {
        queue(song("slow", 100.0))
        c.player.previewSleepVolume(0.3f)
        assertEquals(NormalizerProcessor.volumeFor(0.3f), NormalizerProcessor.sleepVolume, 1e-5f)
        c.player.clearSleepVolumePreview()
        assertEquals(1f, NormalizerProcessor.sleepVolume, 0f)
        assertEquals(1f, NormalizerProcessor.fadeVolume, 0f)
    }

    @Test fun aConfigPreviewNeverOverridesARunningSessionsVolume() {
        queue(song("slow", 100.0))
        timer(volume = 0.5f)
        c.player.previewSleepVolume(0.1f)
        assertEquals(NormalizerProcessor.volumeFor(0.5f), NormalizerProcessor.sleepVolume, 1e-5f)
        c.player.clearSleepVolumePreview()
        assertEquals(NormalizerProcessor.volumeFor(0.5f), NormalizerProcessor.sleepVolume, 1e-5f)
        c.player.cancelSleepTimer()
        assertEquals(1f, NormalizerProcessor.sleepVolume, 0f)
    }

    @Test fun theChosenTrackStartsWhenTheTimerFinishes() {
        queue(song("slow", 100.0))
        val after = Song("after", "After song")
        c.sleepPrefs.setMinutes(1)
        c.sleepPrefs.setMaxBpm(0)
        c.sleepPrefs.setFinishSong(false)
        c.sleepPrefs.setVolume(0.4f)
        c.sleepPrefs.setAfterSong(after)
        c.sleepPrefs.setFadeInSeconds(0)
        c.sleepPrefs.setLoopAfter(false)
        c.player.startSleepTimer()
        // -5 from a one-minute timer runs it out; the after-track then takes over at the bedtime volume.
        c.player.adjustSleepTimer(-5)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("after", player.currentMediaItem?.toSong()?.id)
        assertTrue(player.playWhenReady)
        assertEquals(NormalizerProcessor.volumeFor(0.4f), NormalizerProcessor.sleepVolume, 1e-5f)
        assertEquals(1f, NormalizerProcessor.fadeVolume, 0f)
        // When its special handling ends, the listener's own volume comes back.
        c.player.cancelSleepTimer()
        assertEquals(1f, NormalizerProcessor.sleepVolume, 0f)
    }

    @Test fun theChosenTrackAndItsFadeSurviveACancel() {
        queue(song("slow", 100.0))
        timer()
        c.sleepPrefs.setAfterSong(Song("after", "After song"))
        c.sleepPrefs.setFadeInSeconds(20)
        c.sleepPrefs.setLoopAfter(true)
        c.player.cancelSleepTimer()
        assertEquals("after", c.sleepPrefs.choices.value.afterSong?.id)
        assertEquals(20, c.sleepPrefs.choices.value.fadeInSeconds)
        assertTrue(c.sleepPrefs.choices.value.loopAfter)
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

    // ---------------------------------------------------------------- the minimum sleep score (1.9)

    /** A song with a sleep score already calculated (stored under its own id, the form a plain song plays as). */
    private fun scored(id: String, value: Int): Song = Song(id, "Scored $id").also {
        val figure = SleepScore(
            score = value, confidence = 90,
            components = SleepComponents(0, 0, 0, 0, 0, 0, 0, 0),
            warnings = emptyList(), modelVersion = SleepAnalysis.MODEL_VERSION, durationMs = 180_000, analyzedAtMs = 1L,
        )
        app.getSharedPreferences("sleep_scores", Context.MODE_PRIVATE).edit()
            .putString(id, SleepScore.encode(figure)).commit()
    }

    private fun scoreTimer(minScore: Int = 60, maxBpm: Int = 0) {
        c.sleepPrefs.setMinutes(30)
        c.sleepPrefs.setMaxBpm(maxBpm)
        c.sleepPrefs.setMinScore(minScore)
        c.sleepPrefs.setFinishSong(false)
        c.sleepPrefs.setVolume(1f)
        c.player.startSleepTimer()
    }

    @Test fun aSongBelowTheLimitOrWithNoScoreIsPassedOverAtOnce() {
        queue(scored("loud", 20), song("uncalculated", null), scored("calm", 82))
        scoreTimer(minScore = 60)
        // The first song is below the minimum and the next has no figure: the calm one after them plays.
        assertEquals(2, player.currentMediaItemIndex)
        assertTrue(c.player.sleepBlocksScore("loud"))
        assertTrue("an uncalculated song is never assumed suitable", c.player.sleepBlocksScore("uncalculated"))
        assertFalse(c.player.sleepBlocksScore("calm"))
        assertTrue(c.player.sleepFilteringScore())
        // Nothing was taken out of the queue: all of it is still there, in order.
        assertEquals(3, player.mediaItemCount)
    }

    @Test fun withNoAllowedSongLeftItStopsAndTurningTheLimitOffBringsPlaybackBack() {
        queue(scored("loud", 20), song("uncalculated", null))
        player.play()
        scoreTimer(minScore = 60)
        assertEquals(0, player.currentMediaItemIndex)
        assertFalse(player.playWhenReady)
        c.player.setSleepMinScore(0)
        assertTrue("the pause the filter caused ends with the filter", player.playWhenReady)
        assertFalse(c.player.sleepBlocksScore("loud"))
        assertEquals(0, c.sleepPrefs.choices.value.minScore)
        assertEquals(0, c.player.sleepMinScore.value)
        // Both songs are still there: nothing was removed, so there's nothing to restore.
        assertEquals(2, player.mediaItemCount)
    }

    @Test fun theThresholdIsExactAndTheFilterIsOffOnceTheTimerHasRunOut() {
        queue(scored("border", 60), scored("under", 59), scored("loud", 10))
        scoreTimer(minScore = 60)
        assertEquals("a song exactly at the minimum passes", 0, player.currentMediaItemIndex)
        assertFalse(c.player.sleepBlocksScore("border"))
        assertTrue(c.player.sleepBlocksScore("under"))
        // A one-minute session runs out at -5: the score filter was the session's own, like the BPM limit.
        c.sleepPrefs.setMinutes(1)
        c.player.startSleepTimer()
        c.player.adjustSleepTimer(-5)
        assertEquals(0, c.sleepPrefs.choices.value.minScore)
        assertFalse(c.player.sleepBlocksScore("under"))
    }

    @Test fun queueEditsMadeWhileFilteringAreKeptAndNeverUndoneByTurningItOff() {
        queue(scored("loud", 10), scored("calm", 90), scored("loud2", 15), scored("calm2", 88))
        scoreTimer(minScore = 60)
        // The first is passed over: the calm one next to it plays.
        assertEquals(1, player.currentMediaItemIndex)
        // The listener removes the passed-over song and adds another while the filter runs.
        c.player.removeAt(0)
        c.player.addToQueue(listOf(scored("added", 5)), null)
        assertEquals(4, player.mediaItemCount)
        // Turning the filter off restores no snapshot: it brings the queue back exactly as the listener left it.
        c.player.cancelSleepTimer()
        assertEquals(4, player.mediaItemCount)
        assertEquals(listOf("calm", "loud2", "calm2", "added"), (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toSong()!!.id })
        assertEquals("calm", player.currentMediaItem?.toSong()?.id)
    }

    @Test fun theTemporaryScoreOverrideBringsSongsBackAndReEnablingChecksTheCurrentOne() {
        queue(scored("loud", 10), scored("calm", 90))
        scoreTimer(minScore = 60)
        assertEquals(1, player.currentMediaItemIndex)
        c.player.setSleepScoreDisabled(true)
        assertFalse(c.player.sleepBlocksScore("loud"))
        assertFalse(c.player.sleepFilteringScore())
        c.player.skipTo(0)
        assertEquals(0, player.currentMediaItemIndex)
        // On again: the song playing now is checked at once, not at the next one.
        c.player.setSleepScoreDisabled(false)
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals("the override never touches the saved limit", 60, c.sleepPrefs.choices.value.minScore)
    }

    @Test fun cancellingKeepsTheScoreChoiceForNextTime() {
        queue(scored("calm", 90))
        scoreTimer(minScore = 70)
        c.player.cancelSleepTimer()
        assertEquals(70, c.sleepPrefs.choices.value.minScore)
        assertFalse(c.player.sleepBlocksScore("calm"))
    }

    @Test fun bothLimitsApplyWithNothingRemovedFromTheQueue() {
        // One song too fast, one with too low a score, and one that fits both.
        queue(song("fast", 150.0), scored("low", 10), scored("fits", 90))
        app.getSharedPreferences("dj_quick", Context.MODE_PRIVATE).edit().putString("fits", "100|5.0|").commit()
        scoreTimer(minScore = 60, maxBpm = 120)
        assertEquals(2, player.currentMediaItemIndex)
        assertTrue(c.player.sleepBlocksFilter("fast"))
        assertTrue(c.player.sleepBlocksFilter("low"))
        assertFalse(c.player.sleepBlocksFilter("fits"))
        assertEquals(3, player.mediaItemCount)
    }

    @Test fun duplicateSongsAreJudgedInPlaceAndTheQueueKeepsItsOrder() {
        queue(scored("low", 10), scored("calm", 90), scored("low", 10), scored("calm2", 80))
        scoreTimer(minScore = 60)
        assertEquals(1, player.currentMediaItemIndex)
        // Jumping onto the repeated unsuitable song: the filter passes it over for the one that fits.
        c.player.skipTo(2)
        c.player.enforceSleepBpm()
        assertEquals(3, player.currentMediaItemIndex)
        assertEquals(
            listOf("low", "calm", "low", "calm2"),
            (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toSong()!!.id },
        )
    }

    @Test fun aChangedMinimumIsReappliedAtOnce() {
        queue(scored("border", 60), scored("calm", 75))
        scoreTimer(minScore = 60)
        assertEquals("a song exactly at the minimum plays", 0, player.currentMediaItemIndex)
        // Raised while the timer runs: the song playing no longer passes, and the next one that does plays at once.
        c.player.setSleepMinScore(70)
        assertEquals(70, c.sleepPrefs.choices.value.minScore)
        assertEquals(1, player.currentMediaItemIndex)
        // Switched off: the limit is gone from the session and from next time's choices.
        c.player.setSleepMinScore(0)
        assertEquals(0, c.sleepPrefs.choices.value.minScore)
        assertFalse(c.player.sleepBlocksScore("border"))
        assertEquals(2, player.mediaItemCount)
    }
}
