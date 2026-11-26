package com.prism.music

import android.media.AudioDeviceInfo
import androidx.media3.common.Player
import com.prism.music.playback.OutputReturn
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Output return (Settings → Playback → "Resume when Bluetooth comes back"): only a pause the lost Bluetooth output
 * caused is picked up, by that same pair (or the earbuds' own 2.4 GHz dongle, a USB output, soon after), and only within
 * the window. Everything else stays paused.
 */
class OutputReturnTest {
    private val noisy = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
    private val asked = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST

    private fun buds(address: String = "AA:BB:CC:DD:EE:FF", name: String = "Prism Buds", type: Int = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) =
        OutputReturn.Sink(type, address, name)
    private fun speaker() = OutputReturn.Sink(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "", "Phone")
    private fun wired() = OutputReturn.Sink(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "", "Headphones")
    private fun dongle() = OutputReturn.Sink(AudioDeviceInfo.TYPE_USB_DEVICE, "", "Dongle")

    @Test fun theSamePairComingBackResumes() {
        val r = OutputReturn()
        r.current(buds())
        r.removed(listOf(buds()), 1_000)
        r.paused(noisy, 1_100)
        assertTrue(r.waitingFor)
        assertTrue("the same pair connected again", r.returned(listOf(buds()), 4_000))
        assertFalse(r.waitingFor)
    }

    /**
     * Seen on a vivo with TWS earbuds: the pause lands about a second before the phone drops the earbuds' entry, so a look
     * at the outputs just after the pause still finds them. That isn't them coming back (it played on the speaker).
     */
    @Test fun anEntryNotYetRemovedIsNotAReturn() {
        val r = OutputReturn()
        r.current(buds())
        r.paused(noisy, 1_000)
        assertFalse("still listed, never seen leaving", r.returnedAlready(listOf(speaker(), buds()), 1_400))
        assertTrue("the wait stays", r.waitingFor)
        r.removed(listOf(buds()), 2_100)
        assertFalse("gone now", r.returnedAlready(listOf(speaker()), 2_500))
        assertTrue("back after leaving", r.returnedAlready(listOf(speaker(), buds()), 9_000))
    }

    /** A quick switch: the entry went and came back before the pause reached Prism. That one is back already. */
    @Test fun anEntryThatLeftAndCameBackBeforeThePauseIsAReturn() {
        val r = OutputReturn()
        r.current(buds())
        r.removed(listOf(buds()), 1_000)
        r.paused(noisy, 1_300)
        assertTrue(r.returnedAlready(listOf(speaker(), buds()), 1_700))
    }

    @Test fun theWaitCountsOnlyInsideItsWindow() {
        val r = OutputReturn(windowMs = 60_000)
        r.current(buds())
        r.paused(noisy, 1_000)
        assertTrue(r.waiting(30_000))
        assertFalse(r.waiting(70_000))
    }

    /** The earbuds' 2.4 GHz dongle is a USB output with a name of its own: the same listening, so the music resumes. */
    @Test fun theDongleIsTheSameListening() {
        val r = OutputReturn()
        r.current(buds())
        r.removed(listOf(buds()), 1_000)
        r.paused(noisy, 1_100)
        assertTrue(r.returned(listOf(dongle()), 6_000))
        assertFalse(r.waitingFor)
    }

    /** A quick switch: looking at the outputs after the pause finds the dongle once the earbuds' entry is gone. */
    @Test fun theDongleFoundAfterTheEntryWentIsAReturn() {
        val r = OutputReturn()
        r.current(buds())
        r.paused(noisy, 1_100)
        assertFalse("nothing was seen leaving yet", r.returnedAlready(listOf(speaker(), buds(), dongle()), 1_400))
        r.removed(listOf(buds()), 2_100)
        assertTrue(r.returnedAlready(listOf(speaker(), dongle()), 2_500))
    }

    /** Long after the switch (a dongle connected for something else): only the pair that went picks it up. */
    @Test fun aDongleLongAfterTheSwitchIsNotThePair() {
        val r = OutputReturn()
        r.current(buds("AA:BB"))
        r.removed(listOf(buds("AA:BB")), 1_000)
        r.paused(noisy, 1_100)
        assertFalse(r.returned(listOf(dongle()), 30_000))
        assertTrue(r.returned(listOf(buds("AA:BB")), 31_000))
    }

    /** On a TWS switch the phone drops the media volume to 0 as the link goes: "Pause when muted" arms the wait too. */
    @Test fun aMutePauseWithTheOutputGoneArmsIt() {
        val r = OutputReturn()
        r.current(buds())
        r.removed(listOf(buds()), 1_000)
        r.muted(1_200)
        assertTrue(r.waitingFor)
        assertTrue(r.returned(listOf(buds()), 4_000))
    }

    /** The volume reaching 0 on its own is not an output going: nothing waits, nothing plays on later. */
    @Test fun aMutePauseAloneDoesNotArmIt() {
        val r = OutputReturn()
        r.current(buds())
        r.muted(1_000)
        assertFalse(r.waitingFor)
        assertFalse(r.returned(listOf(buds()), 3_000))
    }

    /** The mute pause came first, the earbuds' entry goes a moment later (the usual order on a switch). */
    @Test fun aMutePauseThenTheOutputGoingArmsIt() {
        val r = OutputReturn()
        r.current(buds())
        r.muted(1_000)
        r.removed(listOf(buds()), 2_000)
        assertTrue(r.waitingFor)
        assertTrue(r.returned(listOf(buds()), 4_000))
    }

    /** A mute pause while an output's wait is already armed leaves that wait alone. */
    @Test fun aMutePauseDoesNotReplaceAWait() {
        val r = OutputReturn()
        r.current(buds("AA:BB"))
        r.removed(listOf(buds("AA:BB")), 1_000)
        r.paused(noisy, 1_100)
        r.muted(1_500)
        assertTrue(r.waitingFor)
        assertTrue(r.returned(listOf(buds("AA:BB")), 4_000))
    }

    /** The listener report for the mute pause itself (just before or after the notice) doesn't cancel it. */
    @Test fun theMutePausesOwnListenerReportKeepsIt() {
        val r = OutputReturn()
        r.current(buds())
        r.removed(listOf(buds()), 1_000)
        r.muted(1_200)
        r.paused(asked, 1_250)
        assertTrue(r.waitingFor)
        assertTrue(r.returned(listOf(buds()), 4_000))
    }

    /** A pause made later (the listener's own) still cancels it. */
    @Test fun aLaterListenersPauseCancels() {
        val r = OutputReturn()
        r.current(buds())
        r.removed(listOf(buds()), 1_000)
        r.muted(1_200)
        r.paused(asked, 3_000)
        assertFalse(r.waitingFor)
        assertFalse(r.returned(listOf(buds()), 4_000))
    }

    @Test fun anotherPairDoesNotTakeThePlace() {
        val r = OutputReturn()
        r.current(buds("AA:BB"))
        r.removed(listOf(buds("AA:BB")), 1_000)
        r.paused(noisy, 1_000)
        assertFalse("another pair is not the one that went", r.returned(listOf(buds("11:22")), 3_000))
        assertTrue("the pair that went still resumes", r.returned(listOf(buds("AA:BB")), 5_000))
    }

    @Test fun theListenersPauseCancels() {
        val r = OutputReturn()
        r.current(buds())
        r.removed(listOf(buds()), 1_000)
        r.paused(noisy, 1_000)
        r.paused(asked, 2_000)
        assertFalse(r.returned(listOf(buds()), 3_000))
    }

    @Test fun playingCancels() {
        val r = OutputReturn()
        r.current(buds())
        r.removed(listOf(buds()), 1_000)
        r.paused(noisy, 1_000)
        r.played()
        assertFalse(r.returned(listOf(buds()), 3_000))
    }

    @Test fun theWaitRunsOut() {
        val r = OutputReturn(windowMs = 60_000)
        r.current(buds())
        r.removed(listOf(buds()), 1_000)
        r.paused(noisy, 1_000)
        assertFalse("a minute and a half later is too late", r.returned(listOf(buds()), 91_000))
        assertFalse("and it is not kept for later", r.returned(listOf(buds()), 92_000))
    }

    @Test fun aBluetoothEntryGoingAfterAWiredPauseDoesNotArm() {
        val r = OutputReturn()
        r.current(wired())
        // The cable goes: the pause is the cable's. An earbud's case or ear detection dropping an entry just before or a
        // moment after is not what it was playing on.
        r.removed(listOf(buds()), 900)
        r.paused(noisy, 1_000)
        r.removed(listOf(buds()), 2_000)
        assertFalse(r.waitingFor)
        assertFalse(r.returned(listOf(buds()), 3_000))
    }

    @Test fun anotherPairGoingJustBeforeThePauseDoesNotStealIt() {
        val r = OutputReturn()
        r.current(buds("AA:BB"))
        r.removed(listOf(buds("11:22")), 900)
        r.paused(noisy, 1_000)
        assertFalse(r.returned(listOf(buds("11:22")), 2_000))
        assertTrue(r.returned(listOf(buds("AA:BB")), 2_500))
    }

    @Test fun anotherPairGoingDoesNotReplaceTheArmedOne() {
        val r = OutputReturn()
        r.current(buds("AA:BB"))
        r.removed(listOf(buds("AA:BB")), 1_000)
        r.paused(noisy, 1_000)
        // A second pair's entry goes while the first is waited for: the wait stays on the pair that paused it.
        r.removed(listOf(buds("11:22")), 1_500)
        assertFalse(r.returned(listOf(buds("11:22")), 3_000))
        assertTrue(r.returned(listOf(buds("AA:BB")), 3_500))
    }

    @Test fun unpluggingWiredHeadphonesIsNotPickedUp() {
        val r = OutputReturn()
        r.current(wired())
        r.removed(listOf(wired()), 1_000)
        r.paused(noisy, 1_000)
        assertFalse("only Bluetooth comes back", r.returned(listOf(buds()), 3_000))
    }

    @Test fun aRemovalJustAfterThePauseStillArmsIt() {
        val r = OutputReturn()
        // The route was read as the phone (the earbuds had not taken over yet): the pause comes first, the removal after.
        r.current(speaker())
        r.paused(noisy, 1_000)
        assertFalse(r.returned(listOf(buds()), 1_500))
        r.removed(listOf(buds()), 1_600)
        assertTrue(r.returned(listOf(buds()), 4_000))
    }

    @Test fun noBluetoothAtAllDoesNotArm() {
        val r = OutputReturn()
        r.paused(noisy, 1_000)
        assertFalse("nothing says a Bluetooth output went", r.returned(listOf(buds()), 2_000))
    }

    @Test fun aPairThatSwitchesToLeAudioIsStillTheSamePair() {
        val r = OutputReturn()
        r.current(buds())
        r.removed(listOf(buds()), 1_000)
        r.paused(noisy, 1_000)
        val le = buds(type = AudioDeviceInfo.TYPE_BLE_HEADSET)
        assertTrue(r.returned(listOf(le), 3_000))
    }

    @Test fun withoutAnAddressTheNameFindsIt() {
        val r = OutputReturn()
        val before = buds(address = "", name = "Prism Buds")
        r.current(before)
        r.removed(listOf(before), 1_000)
        r.paused(noisy, 1_000)
        val after = buds(address = "", name = "Prism Buds", type = AudioDeviceInfo.TYPE_BLE_HEADSET)
        assertTrue(r.returned(listOf(after), 3_000))
    }

    @Test fun anOldRemovalIsNotTakenAsTheOutputThatWent() {
        val r = OutputReturn()
        r.current(speaker())
        r.removed(listOf(buds()), 1_000)
        // Ten seconds later, playing on the phone: a becoming-noisy pause now is not that pair going.
        r.paused(noisy, 11_000)
        assertFalse(r.returned(listOf(buds()), 12_000))
    }
}
