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
        override var routedOutput: OutputReturn.Sink? = null
        /** Test shorthand: an outside route (the same buds the helpers make) or the phone's speaker. */
        var headsetConnected: Boolean
            get() = routedOutput?.let { it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE } ?: false
            set(value) {
                routedOutput = if (value) OutputReturn.Sink(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "AA:BB", "Buds")
                else OutputReturn.Sink(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "", "Phone")
            }
        override var speakerPicked = false
        override var awaitingReturn = false
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

        /** Media3 reporting a new reason for a pause that already happened (playWhenReady stays false). */
        fun reasonWhilePaused(reason: Int) {
            check(!playWhenReady)
            guard.onPlayWhenReadyChanged(false, reason)
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
        fun removed(vararg sinks: OutputReturn.Sink) = devices?.invoke(emptyList(), sinks.toList())
    }

    private class FakeMemory(var sink: OutputReturn.Sink? = null, var switched: Boolean = false) : PlaybackGuard.Memory {
        override fun lastHeadset() = sink
        override fun rememberHeadset(sink: OutputReturn.Sink?) { this.sink = sink }
        override fun switched() = switched
        override fun rememberSwitched(on: Boolean) { switched = on }
    }

    private class Rig(on: Boolean = true, val memory: PlaybackGuard.Memory = PlaybackGuard.Memory.None) {
        var setting = on
        var muteHandlesVolume = false
        val watcher = FakeWatcher()
        val player = FakePlayer()
        val guard = PlaybackGuard(watcher, { setting }, { muteHandlesVolume }, player, log = {}, memory = memory)
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
        r.player.headsetConnected = true
        r.watcher.added(wired())
        assertTrue("the headphones back: it starts", r.player.playWhenReady)
        assertNull(r.guard.holding)
    }

    @Test fun aPlayAfterTheUnplugWaitsForTheHeadphones() {
        val r = Rig()
        r.start()
        r.watcher.added(bluetooth())
        r.player.headsetConnected = true
        r.player.set(true)
        r.player.noisy()
        r.player.headsetConnected = false
        assertFalse(r.player.playWhenReady)
        r.player.set(true)
        assertFalse("pressing Play doesn't send it to the speaker", r.player.playWhenReady)
        assertEquals("it waits for the headphones", PlaybackGuard.Hold.LOST, r.guard.holding)
        r.player.headsetConnected = true
        r.watcher.added(bluetooth())
        assertTrue(r.player.playWhenReady)
        assertNull(r.guard.holding)
    }

    /** A TWS pair switching to its 2.4 GHz dongle: the same kind of gap, and the dongle (a USB output) ends it. */
    @Test fun aSwitchToTheDongleWaitsForTheDongleNotTheSpeaker() {
        val r = Rig()
        r.start()
        r.watcher.added(bluetooth())
        r.player.headsetConnected = true
        r.player.set(true)
        r.player.noisy()
        r.player.headsetConnected = false
        r.player.set(true)
        assertFalse("not the speaker", r.player.playWhenReady)
        assertEquals(PlaybackGuard.Hold.LOST, r.guard.holding)
        r.player.headsetConnected = true
        r.watcher.added(OutputReturn.Sink(AudioDeviceInfo.TYPE_USB_DEVICE, "", "Dongle"))
        assertTrue("the dongle is where the music goes", r.player.playWhenReady)
        assertNull(r.guard.holding)
    }

    /**
     * The persistent card's silent track takes the audio focus while a play waits (compatibility mode): Media3 reports
     * the held pause again as a focus loss. The wait stays, and the volume coming back still starts the music.
     */
    @Test fun aFocusLossWhileHeldKeepsTheWait() {
        val r = Rig()
        r.start()
        r.player.silent = true
        r.player.set(true)
        assertEquals(PlaybackGuard.Hold.VOLUME, r.guard.holding)
        r.player.reasonWhilePaused(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
        assertEquals("still waiting", PlaybackGuard.Hold.VOLUME, r.guard.holding)
        r.player.silent = false
        r.watcher.volumeChanged()
        assertTrue("it starts by itself when the volume comes back", r.player.playWhenReady)
    }

    @Test fun aFocusLossDuringAnUnplugWaitKeepsIt() {
        val r = Rig()
        r.start()
        r.player.set(true)
        r.player.noisy()
        r.player.reasonWhilePaused(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
        assertEquals(PlaybackGuard.Hold.NOISY, r.guard.holding)
        r.player.headsetConnected = true
        r.watcher.added(bluetooth())
        assertTrue(r.player.playWhenReady)
    }

    /** Paused by the listener (or by "Pause when muted"), then the earbuds drop: nothing waits, nothing plays on later. */
    @Test fun anUnplugAfterTheListenersPauseIsNotWaitedOn() {
        val r = Rig()
        r.start()
        r.player.set(true)
        r.player.set(false)
        r.player.reasonWhilePaused(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
        assertNull(r.guard.holding)
        r.watcher.added(bluetooth())
        assertFalse("the listener's pause stays", r.player.playWhenReady)
        assertEquals(0, r.player.plays)
    }

    /** Earbuds connecting add their call channel first: that isn't the headphones back. */
    @Test fun aCallChannelIsNotTheHeadphonesBack() {
        val r = Rig()
        r.start()
        r.player.set(true)
        r.player.noisy()
        r.watcher.added(OutputReturn.Sink(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AA:BB", "Buds"))
        assertFalse(r.player.playWhenReady)
        assertEquals(PlaybackGuard.Hold.NOISY, r.guard.holding)
        r.player.headsetConnected = true
        r.watcher.added(bluetooth())
        assertTrue(r.player.playWhenReady)
    }

    /** A 2.4 GHz dongle already plugged in when the pair switches to it: the route moving to it ends the wait. */
    @Test fun aDongleAlreadyThereEndsTheWaitWhenThePairGoes() {
        val r = Rig()
        r.start()
        r.watcher.added(bluetooth())
        r.player.headsetConnected = true
        r.player.set(true)
        r.player.noisy()
        assertEquals(PlaybackGuard.Hold.NOISY, r.guard.holding)
        // The earbuds' A2DP entry goes; the USB dongle takes the route (no device add).
        r.watcher.removed(bluetooth())
        r.player.routedOutput = OutputReturn.Sink(AudioDeviceInfo.TYPE_USB_DEVICE, "", "Dongle")
        r.guard.check()
        assertTrue(r.player.playWhenReady)
        assertNull(r.guard.holding)
    }

    /** The route can come back without a device being added (a dongle already connected): the tick's check ends it. */
    @Test fun checkEndsTheWaitWhenTheRouteComesBack() {
        val r = Rig()
        r.start()
        r.watcher.added(bluetooth())
        r.player.headsetConnected = true
        r.player.set(true)
        r.player.noisy()
        r.player.headsetConnected = false
        assertEquals(PlaybackGuard.Hold.NOISY, r.guard.holding)
        r.player.routedOutput = OutputReturn.Sink(AudioDeviceInfo.TYPE_USB_DEVICE, "", "Dongle")
        r.guard.check()
        assertTrue(r.player.playWhenReady)
        assertNull(r.guard.holding)
    }

    /** Picking the phone's speaker in Prism's device sheet is a choice: a lost headset doesn't hold it. */
    @Test fun aPickedSpeakerIsNotHeldByALostHeadset() {
        val r = Rig()
        r.start()
        r.watcher.added(bluetooth())
        r.player.headsetConnected = true
        r.player.set(true)
        r.player.noisy()
        r.player.headsetConnected = false
        r.player.speakerPicked = true
        r.player.set(true)
        assertTrue(r.player.playWhenReady)
        assertNull(r.guard.holding)
    }

    /** "Pause when muted" resuming at the next volume change while Resume still waits for the output: held. */
    @Test fun aPlayWhileResumeWaitsForTheOutputIsHeld() {
        val r = Rig()
        r.start()
        r.watcher.added(bluetooth())
        r.player.headsetConnected = true
        r.player.set(true)
        r.player.noisy()
        r.player.headsetConnected = false
        r.player.awaitingReturn = true
        r.player.set(true)
        assertFalse("not on the phone while the output is waited for", r.player.playWhenReady)
        assertEquals(PlaybackGuard.Hold.LOST, r.guard.holding)
        r.player.awaitingReturn = false
        r.player.routedOutput = OutputReturn.Sink(AudioDeviceInfo.TYPE_USB_DEVICE, "", "Dongle")
        r.guard.check()
        assertTrue(r.player.playWhenReady)
    }

    /** Back on Bluetooth (the phone) after a 2.4 GHz switch: the same pair is the route again, so it plays. */
    @Test fun theSamePairBackOnBluetoothLetsItPlay() {
        val r = Rig()
        r.start()
        r.watcher.added(bluetooth())
        r.player.headsetConnected = true
        r.player.set(true)
        r.player.noisy()
        r.watcher.removed(bluetooth())
        r.player.headsetConnected = false
        r.player.set(true)
        assertEquals(PlaybackGuard.Hold.LOST, r.guard.holding)
        // The entry is back, but the phone is still the route for a moment.
        r.watcher.added(bluetooth())
        assertFalse(r.player.playWhenReady)
        r.player.headsetConnected = true
        r.guard.check()
        assertTrue("the pair is the route again: it plays", r.player.playWhenReady)
        assertNull(r.guard.holding)
    }

    /** A service restart while the pair is on 2.4 GHz: the last output is remembered, so the phone stays quiet. */
    @Test fun aRestartRemembersTheHeadsetThatWentToTheDongle() {
        val memory = FakeMemory(bluetooth())
        val r = Rig(memory = memory)
        r.start()
        r.player.set(true)
        assertFalse("not the phone's speaker while the pair is away", r.player.playWhenReady)
        assertEquals(PlaybackGuard.Hold.LOST, r.guard.holding)
        r.player.headsetConnected = true
        r.watcher.added(bluetooth())
        assertTrue("the pair back on Bluetooth: it plays", r.player.playWhenReady)
    }

    @Test fun theNextServiceRemembersTheHeadsetSeen() {
        val memory = FakeMemory()
        val r = Rig(memory = memory)
        r.start()
        r.watcher.added(bluetooth())
        assertEquals("Buds", memory.sink?.name)
    }

    /** A headset's entry goes while the music plays (the pair left for its 2.4 GHz mode): playback stops on the phone. */
    @Test fun theOutputLeavingDuringPlaybackPausesIt() {
        val r = Rig()
        r.start()
        r.watcher.added(bluetooth())
        r.player.headsetConnected = true
        r.player.set(true)
        assertTrue(r.player.playWhenReady)
        r.player.headsetConnected = false
        r.watcher.removed(bluetooth())
        assertFalse("not out of the phone after the pair went", r.player.playWhenReady)
        assertEquals(PlaybackGuard.Hold.LOST, r.guard.holding)
    }

    /** A pause from the Bluetooth device (a TWS switching modes): held like an output switch until a play. */
    /** A pause from the Bluetooth device (a TWS switching modes): held until its own play, not an app play. */
    @Test fun aBluetoothPauseHoldsUntilItsPlay() {
        val r = Rig()
        r.start()
        r.watcher.added(bluetooth())
        r.player.headsetConnected = true
        r.player.set(true)
        r.guard.onRemotePause()
        assertFalse(r.player.playWhenReady)
        assertEquals(PlaybackGuard.Hold.LOST, r.guard.holding)
        // The route is still the pair; the one-second check must not take that for it coming back.
        r.guard.check()
        assertFalse(r.player.playWhenReady)
        assertEquals(PlaybackGuard.Hold.LOST, r.guard.holding)
        // An app play must not start it while the pair is switched away.
        r.player.set(true)
        assertFalse("not from the app while the pair is on 2.4 GHz", r.player.playWhenReady)
        // The pair's own play (it is back on Bluetooth) starts it.
        r.guard.onRemotePlay()
        r.player.set(true)
        assertTrue(r.player.playWhenReady)
        assertNull(r.guard.holding)
    }

    /** A service restart while the pair is switched away: an app play still waits for the pair's own play. */
    @Test fun aRestartRemembersTheSwitchedPair() {
        val memory = FakeMemory(bluetooth(), switched = true)
        val r = Rig(memory = memory)
        r.start()
        r.player.set(true)
        assertFalse(r.player.playWhenReady)
        assertEquals(PlaybackGuard.Hold.LOST, r.guard.holding)
        r.guard.onRemotePlay()
        assertTrue(r.player.playWhenReady)
        assertFalse(memory.switched)
    }

    /** Swiped away/stopped, then the pair pauses for 2.4 GHz: nothing is playing, so the card is not held back. */
    @Test fun aPairPauseWithNothingPlayingDoesNotHold() {
        val r = Rig()
        r.start()
        r.watcher.added(bluetooth())
        r.player.headsetConnected = true
        r.guard.onRemotePause()
        assertNull(r.guard.holding)
    }

    /** A queue stopped while the switch hold was up: the hold goes, so the card can start something. */
    @Test fun aStoppedQueueClearsTheSwitchHold() {
        val r = Rig()
        r.start()
        r.watcher.added(bluetooth())
        r.player.headsetConnected = true
        r.player.set(true)
        r.guard.onRemotePause()
        assertEquals(PlaybackGuard.Hold.LOST, r.guard.holding)
        r.guard.stopped()
        assertNull(r.guard.holding)
        r.player.set(true)
        assertTrue("an app play goes through after the stop", r.player.playWhenReady)
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

    /** Switched off while waiting for the headphones: the play the listener asked for goes through (on the speaker). */
    @Test fun turningItOffLetsAHeldPlayStartWithTheHeadphonesGone() {
        val r = Rig()
        r.start()
        r.watcher.added(bluetooth())
        r.player.headsetConnected = true
        r.player.set(true)
        r.player.noisy()
        r.player.headsetConnected = false
        r.player.set(true)
        assertEquals(PlaybackGuard.Hold.LOST, r.guard.holding)
        r.setting = false
        r.guard.update()
        assertTrue(r.player.playWhenReady)
        assertNull(r.guard.holding)
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
