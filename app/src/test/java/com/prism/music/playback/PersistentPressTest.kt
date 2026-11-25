package com.prism.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persistent card's shuffle buttons: a burst of taps while the first set is still being made is one press, not one
 * set started after another (each finished set restarted the music, so a burst shuffled through several songs).
 */
class PersistentPressTest {
    // A clock in milliseconds that only moves forward; the moments are arbitrary.
    private fun gate() = PressGate<String>()

    @Test fun aBurstWhileTheSetIsBeingMadeIsOnePress() {
        val gate = gate()
        val press = gate.start("quick_picks", now = 1_000)!!
        assertNull("the tap while Quick picks is being made", gate.start("quick_picks", now = 1_300))
        assertNull("and the one after it", gate.start("quick_picks", now = 1_900))
        gate.finish("quick_picks", press, now = 6_000)
        assertNotNull("once the set has started playing, a press is a new set", gate.start("quick_picks", now = 6_900))
    }

    @Test fun aDoubleTapOnASetThatIsAlreadyKeptIsOnePress() {
        val gate = gate()
        val press = gate.start("quick_picks", now = 0)!!
        gate.finish("quick_picks", press, now = 40)
        assertNull("the second tap of a double tap", gate.start("quick_picks", now = 300))
        assertNotNull("a deliberate second press", gate.start("quick_picks", now = 940))
    }

    @Test fun theNewestButtonIsTheOneThatPlays() {
        val gate = gate()
        val picks = gate.start("quick_picks", now = 0)!!
        val library = gate.start("library_songs", now = 400)!!
        assertFalse("Quick picks was still being made", gate.isCurrent(picks))
        assertTrue("Library songs is what the listener pressed last", gate.isCurrent(library))
        gate.finish("quick_picks", picks, now = 5_000)
        assertFalse("a set that finishes late doesn't take the card back", gate.isCurrent(picks))
        assertTrue(gate.isCurrent(library))
    }

    @Test fun playingSomethingElseLetsTheSameButtonBePressedAgain() {
        val gate = gate()
        assertNotNull(gate.start("quick_picks", now = 0))
        val play = gate.supersede()
        val again = gate.start("quick_picks", now = 1_000)
        assertNotNull("the card's Play doesn't hold the button", again)
        assertFalse("and the set being made before it no longer plays", gate.isCurrent(play))
        assertTrue(gate.isCurrent(again))
    }

    @Test fun eachButtonHasItsOwnDoubleTap() {
        val gate = gate()
        val picks = gate.start("quick_picks", now = 0)!!
        gate.finish("quick_picks", picks, now = 2_000)
        val library = gate.start("library_songs", now = 2_100)
        assertNotNull("Library songs is untouched by Quick picks' press", library)
        gate.finish("library_songs", library!!, now = 2_200)
        assertNotNull("and each button is free again once its own gap is over", gate.start("quick_picks", now = 2_950))
    }

    @Test fun anOlderSetFinishingLeavesItsButtonsNewestPressAlone() {
        val gate = gate()
        val first = gate.start("quick_picks", now = 0)!!
        gate.supersede()
        val second = gate.start("quick_picks", now = 1_000)!!
        gate.finish("quick_picks", first, now = 6_000)
        assertTrue("the older build's finish doesn't free the newest press", gate.isCurrent(second))
        assertNull("so the button is still working on it", gate.start("quick_picks", now = 7_000))
        gate.finish("quick_picks", second, now = 8_000)
        assertNotNull("and it is free once its own build is done", gate.start("quick_picks", now = 8_900))
    }

    @Test fun aSupersededSetFinishingDoesNotHoldItsButton() {
        val gate = gate()
        val stale = gate.start("quick_picks", now = 0)!!
        gate.supersede()
        gate.finish("quick_picks", stale, now = 6_000)
        assertNotNull("a press that never played doesn't start a double tap's window", gate.start("quick_picks", now = 6_100))
    }

    @Test fun aFinishForAPressThatIsNotThisButtonsLeavesItAlone() {
        val gate = gate()
        val press = gate.start("daily_1", now = 0)!!
        gate.finish("daily_2", press, now = 500)
        assertTrue("the press that is running is still the one that plays", gate.isCurrent(press))
        assertTrue(gate.isRunning)
        gate.finish("daily_1", press, now = 4_000)
        assertFalse(gate.isRunning)
    }

    @Test fun aPressWithoutAGenerationIsAlwaysTheOneToPlay() {
        val gate = gate()
        assertEquals(1, gate.start("quick_picks", now = 1_000))
        assertTrue("a caller that doesn't press the card (its fallback, say) isn't held up", gate.isCurrent(null))
        assertEquals(2, gate.start("daily_3", now = 1_100))
    }
}
