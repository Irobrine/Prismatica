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
        /** What the phone reports as already connected when watching starts (the real callback does this once). */
        var initial: List<OutputReturn.Sink> = emptyList()
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
            devices(initial, emptyList())
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

    private class Rig(
        on: Boolean = true,
        val memory: PlaybackGuard.Memory = PlaybackGuard.Memory.None,
        var switchDevices: Set<String> = setOf("Buds"),
    ) {
        var setting = on
        var muteHandlesVolume = false
        val watcher = FakeWatcher()
        val player = FakePlayer()
        val guard = PlaybackGuard(watcher, { setting }, { muteHandlesVolume }, { switchDevices }, player, log = {}, memory = memory)
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

    /** A pause from the Bluetooth device (a TWS switching modes): held until its own play. */
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

    /** Only the chosen pair's pause is a switch; an empty list means none. */
    @Test fun onlyTheChosenHeadphonesAreASwitch() {
        val r = Rig()
        r.start()
        r.watcher.added(bluetooth())
        r.player.headsetConnected = true
        r.player.set(true)
        r.switchDevices = emptySet()
        r.guard.onRemotePause()
        assertFalse(r.guard.switchedNow)
        r.switchDevices = setOf("Another pair")
        r.guard.onRemotePause()
        assertFalse(r.guard.switchedNow)
        r.switchDevices = setOf("Buds")
        r.guard.onRemotePause()
        assertTrue(r.guard.switchedNow)
    }

    /** A service restart replays the connected pair; that snapshot must not count as the pair coming back. */
    @Test fun aPrimingSnapshotKeepsTheRememberedSwitch() {
        val memory = FakeMemory(bluetooth(), switched = true)
        val r = Rig(memory = memory)
        r.watcher.initial = listOf(bluetooth())
        r.start()
        assertTrue("still switched", r.guard.switchedNow)
        r.player.set(true)
        assertFalse(r.player.playWhenReady)
        assertEquals(PlaybackGuard.Hold.LOST, r.guard.holding)
        r.guard.onRemotePlay()
        r.player.set(true)
        assertTrue(r.player.playWhenReady)
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
        r.player.set(true)
        assertTrue(r.player.playWhenReady)
        assertFalse(memory.switched)
    }

    /** A play control the listener presses releases the switch and starts the music, even on the phone's route. */
    @Test fun aPlayControlReleasesTheSwitch() {
        val r = Rig()
        r.start()
        r.watcher.added(bluetooth())
        r.player.headsetConnected = true
        r.player.set(true)
        r.guard.onRemotePause()
        assertTrue(r.guard.switchedNow)
        // Back on Bluetooth without a media button from the pair: the route still reads as the phone's.
        r.player.routedOutput = speaker()
        r.guard.playFromControl()
        assertFalse("the play control releases it", r.guard.switchedNow)
        assertNull(r.guard.holding)
        r.player.set(true)
        assertTrue("and the play goes through", r.player.playWhenReady)
    }

    /** Another pair cannot clear the switch, but playback on it is normal while it is the route. */
    @Test fun anotherPairCannotUnsetTheSwitch() {
        val r = Rig()
        r.start()
        r.watcher.added(bluetooth())
        r.player.headsetConnected = true
        r.player.set(true)
        r.guard.onRemotePause()
        assertTrue(r.guard.switchedNow)
        val other = OutputReturn.Sink(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "11:22", "Other pair")
        r.player.routedOutput = other
        r.watcher.added(other)
        assertTrue("the other pair's connection does not unset it", r.guard.switchedNow)
        assertNull("its hold is released", r.guard.holding)
        assertTrue("and playback is normal on it", r.player.playWhenReady)
        r.guard.onRemotePlay()
        r.guard.onRemotePlayToggle()
        assertTrue("its play does not unset it either", r.guard.switchedNow)
        // The phone is switched back to the chosen pair while it is still in 2.4 GHz: its entry alone is not a return,
        // and the music is held again.
        r.watcher.removed(other)
        r.player.routedOutput = bluetooth()
        r.watcher.added(bluetooth())
        assertTrue("its entry back does not unset it", r.guard.switchedNow)
        r.guard.check()
        assertFalse("and the music is held again", r.player.playWhenReady)
        assertEquals(PlaybackGuard.Hold.LOST, r.guard.holding)
        // Its own play is the switch back.
        r.guard.onRemotePlay()
        assertFalse(r.guard.switchedNow)
    }

    /** The sound is on the phone and only the chosen pair is listed: its play over AVRCP is the switch back. */
    @Test fun theChosenPairsPlayClearsTheSwitchWhenTheSoundIsOnThePhone() {
        val r = Rig()
        r.start()
        r.watcher.added(bluetooth())
        r.player.headsetConnected = true
        r.player.set(true)
        r.guard.onRemotePause()
        assertTrue(r.guard.switchedNow)
        // The pair switched to its 2.4 GHz receiver: the sound is on the phone, its Bluetooth entry still listed.
        r.player.routedOutput = speaker()
        r.guard.check()
        assertTrue("the route is not taken for the pair coming back", r.guard.switchedNow)
        r.guard.onRemotePlay()
        assertFalse("its own play is the switch back", r.guard.switchedNow)
        r.player.set(true)
        assertTrue(r.player.playWhenReady)
    }

    /** Another pair connected with the sound on the phone may have sent the play: it must not clear the switch. */
    @Test fun anUnchosenPairsPlayOnThePhoneRouteKeepsTheSwitch() {
        val r = Rig()
        r.start()
        r.watcher.added(bluetooth())
        r.player.headsetConnected = true
        r.player.set(true)
        r.guard.onRemotePause()
        r.player.routedOutput = speaker()
        r.watcher.removed(bluetooth())
        val other = OutputReturn.Sink(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "11:22", "Other pair")
        r.watcher.added(other)
        r.guard.onRemotePlay()
        r.guard.onRemotePlayToggle()
        assertTrue("an unchosen pair's play does not clear it", r.guard.switchedNow)
        // The chosen pair listed again is not its play either.
        r.watcher.added(bluetooth())
        assertTrue(r.guard.switchedNow)
    }

    /** The reported flow: another pair plays, then the phone switches back to the chosen pair still in 2.4 GHz. */
    @Test fun switchingBackToTheChosenPairIn24GHzHoldsTheMusicAgain() {
        val r = Rig()
        r.start()
        r.watcher.added(bluetooth())
        r.player.headsetConnected = true
        r.player.set(true)
        r.guard.onRemotePause()
        assertTrue(r.guard.switchedNow)
        // The phone is switched to another pair: it plays there normally, the switch stays set.
        val other = OutputReturn.Sink(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "11:22", "Other pair")
        r.watcher.removed(bluetooth())
        r.player.routedOutput = other
        r.watcher.added(other)
        assertTrue("the other pair plays", r.player.playWhenReady)
        assertNull(r.guard.holding)
        assertTrue(r.guard.switchedNow)
        // Back to the chosen pair, still in 2.4 GHz: the entry comes back, nothing plays into it.
        r.watcher.removed(other)
        r.player.routedOutput = bluetooth()
        r.watcher.added(bluetooth())
        assertTrue("its entry is not its switch back", r.guard.switchedNow)
        r.guard.check()
        assertFalse("the music is held", r.player.playWhenReady)
        assertEquals(PlaybackGuard.Hold.LOST, r.guard.holding)
        // The pair's own play is what starts it again.
        r.guard.onRemotePlay()
        r.player.set(true)
        assertTrue(r.player.playWhenReady)
        assertFalse(r.guard.switchedNow)
    }

    /** The route says which pair paused: not the pair that happened to connect last. */
    @Test fun theRoutedPairsPauseDecidesTheSwitch() {
        val r = Rig()
        r.start()
        val other = OutputReturn.Sink(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "11:22", "Other pair")
        r.watcher.added(other)
        r.watcher.added(bluetooth())
        // The chosen pair connected last, but the other pair carries the sound: its pause is an ordinary pause.
        r.player.routedOutput = other
        r.player.set(true)
        r.guard.onRemotePause()
        assertFalse("another pair's pause is not a switch", r.guard.switchedNow)
        assertNull(r.guard.holding)
        // The other pair connected last, but the chosen one carries the sound: its pause is the switch.
        r.watcher.added(other)
        r.player.routedOutput = bluetooth()
        r.guard.onRemotePause()
        assertTrue("the chosen pair's pause is", r.guard.switchedNow)
    }

    /** A pair taken off the chosen list is no longer a switch: its flag and its hold go. */
    @Test fun aPairTakenOffTheListClearsItsSwitch() {
        val memory = FakeMemory()
        val r = Rig(memory = memory)
        r.start()
        r.watcher.added(bluetooth())
        r.player.headsetConnected = true
        r.player.set(true)
        r.guard.onRemotePause()
        assertTrue(r.guard.switchedNow)
        assertEquals(PlaybackGuard.Hold.LOST, r.guard.holding)
        r.switchDevices = emptySet()
        r.guard.check()
        assertFalse("not a switch any more", r.guard.switchedNow)
        assertFalse(memory.switched)
        assertNull("its pause is an ordinary pause now", r.guard.holding)
        assertFalse("nothing starts by itself", r.player.playWhenReady)
        r.player.set(true)
        assertTrue("a play goes through", r.player.playWhenReady)
    }

    /** A flag remembered without its pair (an older build's) counts only while some pair is chosen. */
    @Test fun aRememberedFlagWithoutItsPairNeedsAChosenPair() {
        val r = Rig(memory = FakeMemory(bluetooth(), switched = true), switchDevices = emptySet())
        r.start()
        assertFalse(r.guard.switchedNow)
        val kept = Rig(memory = FakeMemory(bluetooth(), switched = true))
        kept.start()
        assertTrue(kept.guard.switchedNow)
    }

    /** A play control that started nothing doesn't let a later play out of the speaker after the headphones went. */
    @Test fun anUnusedPlayControlIsNoPassOnceTheHeadphonesGo() {
        val r = Rig()
        r.start()
        r.watcher.added(bluetooth())
        r.player.headsetConnected = true
        r.guard.onRemotePause()
        r.guard.playFromControl()
        assertFalse(r.guard.switchedNow)
        // Nothing played (an empty queue); then the pair goes and the sound is on the phone.
        r.player.routedOutput = speaker()
        r.watcher.removed(bluetooth())
        r.player.set(true)
        assertFalse("not out of the speaker", r.player.playWhenReady)
        assertEquals(PlaybackGuard.Hold.LOST, r.guard.holding)
    }

    /** A pair pause for 2.4 GHz while nothing is playing: no hold, but the switch waits for its play. */
    @Test fun aPairPauseWhileStoppedWaitsForItsPlay() {
        val r = Rig()
        r.start()
        r.watcher.added(bluetooth())
        r.player.headsetConnected = true
        r.guard.onRemotePause()
        assertNull("no song to pause", r.guard.holding)
        assertTrue(r.guard.switchedNow)
        r.player.set(true)
        assertFalse("a play waits for the pair", r.player.playWhenReady)
        r.guard.onRemotePlay()
        r.player.set(true)
        assertTrue("the pair's own play goes through", r.player.playWhenReady)
    }

    /** A queue stopped while the switch hold was up: the hold goes, the switch flag stays. */
    @Test fun aStoppedQueueClearsTheHoldButKeepsTheSwitch() {
        val r = Rig()
        r.start()
        r.watcher.added(bluetooth())
        r.player.headsetConnected = true
        r.player.set(true)
        r.guard.onRemotePause()
        assertEquals(PlaybackGuard.Hold.LOST, r.guard.holding)
        r.guard.stopped()
        assertNull("the hold goes", r.guard.holding)
        assertTrue(r.guard.switchedNow)
        r.player.set(true)
        assertFalse("a play still waits for the pair", r.player.playWhenReady)
        r.guard.onRemotePlay()
        r.player.set(true)
        assertTrue("the pair's own play goes through", r.player.playWhenReady)
    }

    /** The pair's own play at volume 0 waits for the volume too. */
    @Test fun aPairPlayAtVolumeZeroWaitsForTheVolume() {
        val r = Rig()
        r.start()
        r.watcher.added(bluetooth())
        r.player.headsetConnected = true
        r.player.set(true)
        r.guard.onRemotePause()
        r.player.silent = true
        r.guard.onRemotePlay()
        r.player.set(true)
        assertFalse("the pair's play at volume 0 is held", r.player.playWhenReady)
        r.player.silent = false
        r.watcher.volumeChanged()
        assertTrue("the volume back: it plays", r.player.playWhenReady)
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
