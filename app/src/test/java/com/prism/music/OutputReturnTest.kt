package com.prism.music

import android.media.AudioDeviceInfo
import androidx.media3.common.Player
import com.prism.music.playback.OutputReturn
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Output return (Settings → Playback → "Resume when Bluetooth comes back"): only a pause the lost Bluetooth output
 * caused is picked up, only by that same pair, and only within the window. Everything else stays paused.
 */
class OutputReturnTest {
    private val noisy = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
    private val asked = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST

    private fun buds(address: String = "AA:BB:CC:DD:EE:FF", name: String = "Prism Buds", type: Int = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) =
        OutputReturn.Sink(type, address, name)
    private fun speaker() = OutputReturn.Sink(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "", "Phone")
    private fun wired() = OutputReturn.Sink(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "", "Headphones")

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
