package com.prism.music

import android.media.AudioDeviceInfo
import androidx.media3.common.Player
import com.prism.music.playback.OutputReturn
import com.prism.music.playback.PlaybackGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Play only when it can be heard" (Settings → Playback): a play request waits at volume 0, with no output, and after
 * the output went away, and starts by itself when sound is possible again. "Pause when muted" still owns music that's
 * already playing, so the two never pause or resume for the same reason.
 */
class PlaybackGuardTest {
    private fun speaker() = OutputReturn.Sink(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "", "Phone")
    private fun wired() = OutputReturn.Sink(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "", "Headphones")
    private fun bluetooth() = OutputReturn.Sink(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "AA:BB", "Buds")

    /** A player: its pause and play report back through the listener, as Media3's do. */
    private class FakePlayer : PlaybackGuard.Controls {
        lateinit var guard: PlaybackGuard
        override var silent = false
        override var hasOutput = true
        override var blocked = false
        override var playWhenReady = false
        var pauses = 0
        var plays = 0

        override fun pause() { pauses++; set(false) }
        override fun play() { plays++; set(true) }

        /** The listener's own play or pause (or one of ours, through [pause] / [play]). */
        fun set(playing: Boolean) {
            if (playing == playWhenReady) return
            playWhenReady = playing
            guard.onPlayWhenReadyChanged(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        }

        /** Media3's becoming-noisy pause. */
        fun noisy() {
            if (!playWhenReady) return
            playWhenReady = false
            guard.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
        }
    }

    private class FakeWatcher : PlaybackGuard.Watcher {
        var volume: (() -> Unit)? = null
        var devices: ((List<OutputReturn.Sink>, List<OutputReturn.Sink>) -> Unit)? = null
        var starts = 0
        var stops = 0
        val on get() = volume != null

        override fun start(
            onVolume: () -> Unit,
            devices: (added: List<OutputReturn.Sink>, removed: List<OutputReturn.Sink>) -> Unit,
        ) {
            starts++
            volume = onVolume
            this.devices = devices
        }

        override fun stop() {
            stops++
            volume = null
            devices = null
        }

        fun volumeChanged() = volume?.invoke()
        fun added(vararg sinks: OutputReturn.Sink) = devices?.invoke(sinks.toList(), emptyList())
    }

    private class Rig(on: Boolean = true) {
        var setting = on
        var muteHandlesVolume = false
        val watcher = FakeWatcher()
        val player = FakePlayer()
        val guard = PlaybackGuard(watcher, { setting }, { muteHandlesVolume }, player, log = {})
        init { player.guard = guard }
        fun start() = guard.update()
    }

    @Test fun offIsAsPrismCame() {
        val r = Rig(on = false)
        r.start()
        r.player.set(true)
        assertTrue("nothing waits", r.player.playWhenReady)
        assertEquals(0, r.watcher.starts)
        assertEquals(0, r.player.pauses)
    }

    @Test fun playAtVolumeZeroWaitsAndStartsWhenItComesBack() {
        val r = Rig()
        r.start()
        r.player.silent = true
        r.player.set(true)
        assertFalse("it doesn't play into silence", r.player.playWhenReady)
        assertEquals(PlaybackGuard.Hold.VOLUME, r.guard.holding)
        r.player.silent = false
        r.watcher.volumeChanged()
        assertTrue("it starts by itself when the volume comes back", r.player.playWhenReady)
        assertNull(r.guard.holding)
    }

    @Test fun aVolumeDropPausesWhenPauseWhenMutedIsOff() {
        val r = Rig()
        r.start()
        r.player.set(true)
        r.player.silent = true
        r.watcher.volumeChanged()
        assertFalse("the guard pauses what plays while nothing is heard", r.player.playWhenReady)
        r.player.silent = false
        r.watcher.volumeChanged()
        assertTrue(r.player.playWhenReady)
    }

    @Test fun pauseWhenMutedKeepsItsOwnPauseAndResume() {
        val r = Rig()
        r.muteHandlesVolume = true
        r.start()
        r.player.set(true)
        r.player.silent = true
        r.watcher.volumeChanged()
        assertTrue("the guard leaves the mute's pause to the mute watch", r.player.playWhenReady)
        assertNull("no second pause of its own", r.guard.holding)
        assertEquals(0, r.player.pauses)
        // The mute watch's own pause (then its own resume when the volume comes back).
        r.player.set(false)
        r.player.silent = false
        r.watcher.volumeChanged()
        assertFalse("and it doesn't start anything the mute watch didn't", r.player.playWhenReady)
        r.player.set(true)
        assertTrue(r.player.playWhenReady)
    }

    @Test fun turningItOnWhileMutedPausesWhatPlays() {
        val r = Rig(on = false)
        r.start()
        r.player.set(true)
        r.player.silent = true
        r.setting = true
        r.guard.update()
        assertFalse("turned on at 0: what plays pauses at once", r.player.playWhenReady)
        assertEquals(PlaybackGuard.Hold.VOLUME, r.guard.holding)
    }

    @Test fun playWaitsForAnOutput() {
        val r = Rig()
        r.start()
        r.player.hasOutput = false
        r.player.set(true)
        assertFalse(r.player.playWhenReady)
        assertEquals(PlaybackGuard.Hold.OUTPUT, r.guard.holding)
        // The volume coming back doesn't make up for an output that isn't there.
        r.watcher.volumeChanged()
        assertFalse(r.player.playWhenReady)
        r.player.hasOutput = true
        r.watcher.added(speaker())
        assertTrue(r.player.playWhenReady)
        assertNull(r.guard.holding)
    }

    @Test fun noisyWaitsForAHeadsetNotTheSpeaker() {
        val r = Rig()
        r.start()
        r.player.set(true)
        r.player.noisy()
        assertFalse("the unplug pauses it", r.player.playWhenReady)
        assertEquals(PlaybackGuard.Hold.NOISY, r.guard.holding)
        r.watcher.volumeChanged()
        assertFalse("the volume coming back isn't what it waits for", r.player.playWhenReady)
        r.watcher.added(speaker())
        assertFalse("the phone's own speaker isn't where the music was", r.player.playWhenReady)
        assertEquals(PlaybackGuard.Hold.NOISY, r.guard.holding)
        r.watcher.added(wired())
        assertTrue("the headphones back: it starts", r.player.playWhenReady)
        assertNull(r.guard.holding)
    }

    @Test fun aManualPlayAfterTheUnplugPlaysOnTheSpeaker() {
        val r = Rig()
        r.start()
        r.player.set(true)
        r.player.noisy()
        assertFalse(r.player.playWhenReady)
        r.player.set(true)
        assertTrue("pressing Play after all means the speaker is fine", r.player.playWhenReady)
        assertNull(r.guard.holding)
    }

    @Test fun noisyWhilePausedChangesNothing() {
        val r = Rig()
        r.start()
        r.player.noisy()
        assertNull(r.guard.holding)
        assertEquals(0, r.player.pauses)
    }

    @Test fun turningTheSettingOffLetsAHeldPlayStart() {
        val r = Rig()
        r.start()
        r.player.silent = true
        r.player.set(true)
        assertFalse(r.player.playWhenReady)
        r.player.silent = false
        r.setting = false
        r.guard.update()
        assertTrue("with the guard off the request goes through", r.player.playWhenReady)
        assertNull(r.guard.holding)
        assertEquals(1, r.watcher.stops)
    }

    @Test fun releaseStopsWatchingAndTouchingThePlayer() {
        val r = Rig()
        r.start()
        assertEquals(1, r.watcher.starts)
        r.guard.release()
        assertEquals(1, r.watcher.stops)
        r.player.set(true)
        assertTrue("nothing is held or paused after the service is gone", r.player.playWhenReady)
        assertEquals(0, r.player.pauses)
    }
}
