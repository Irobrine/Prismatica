package com.prism.music.playback

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** GitHub #343: background song scans make way for the listener on slow phones, and DJ mode's pre-scan waits for an idle phone. */
class ScanMannersTest {
    private val slow = { true }
    private val fast = { false }

    @Test
    fun scansStepAsideWhileTheListenerTouchesASlowPhone() {
        // A finger on the screen, and the 2 s after the last touch (a fling).
        assertTrue(MixTempo.stepsAside(slow, down = true, sinceTouchMs = 10_000, urgent = 0))
        assertTrue(MixTempo.stepsAside(slow, down = false, sinceTouchMs = 1_500, urgent = 0))
        assertFalse(MixTempo.stepsAside(slow, down = false, sinceTouchMs = 2_500, urgent = 0))
    }

    @Test
    fun fastPhonesNeverWait() {
        var asked = false
        assertFalse(MixTempo.stepsAside(fast, down = true, sinceTouchMs = 0, urgent = 0))
        // Nobody touching: the phone isn't even asked (it reads a setting).
        assertFalse(MixTempo.stepsAside({ asked = true; true }, down = false, sinceTouchMs = 60_000, urgent = 0))
        assertFalse(asked)
    }

    @Test
    fun aSongsEndWaitingForItsScanComesFirst() {
        // Crossfade near a song's end scans it from the phone: nothing makes way for touches meanwhile.
        assertFalse(MixTempo.stepsAside(slow, down = true, sinceTouchMs = 0, urgent = 1))
        runBlocking {
            val during = MixTempo.urgently { MixTempo.stepsAside(slow, down = true, sinceTouchMs = 0, urgent = 1) }
            assertFalse(during)
        }
    }

    @Test
    fun whichPhonesCountAsSlow() {
        assertTrue(MixTempo.isSlowPhone(cores = 2, mayBeSlow = false, performanceMode = false))
        assertTrue(MixTempo.isSlowPhone(cores = 4, mayBeSlow = false, performanceMode = false))
        assertTrue(MixTempo.isSlowPhone(cores = 8, mayBeSlow = true, performanceMode = false))
        assertTrue(MixTempo.isSlowPhone(cores = 8, mayBeSlow = false, performanceMode = true))
        assertFalse(MixTempo.isSlowPhone(cores = 8, mayBeSlow = false, performanceMode = false))
    }

    @Test
    fun djPreScanOnlyWhileThePhoneIsLeftAlone() {
        assertTrue(DjPrescanWorker.mayRun(screenOn = false, playing = false))
        assertFalse(DjPrescanWorker.mayRun(screenOn = true, playing = false))
        assertFalse(DjPrescanWorker.mayRun(screenOn = false, playing = true))
        assertEquals(false, DjPrescanWorker.mayRun(screenOn = true, playing = true))
    }

    /** 1.9: two songs at once only where the phone can genuinely spare them, and never beside playback or out of sight. */
    @Test
    fun twoSongsAtOnceOnlyOnAPhoneThatCanSpareThem() {
        val heap = 512L * 1024 * 1024
        val bigFree = 256L * 1024 * 1024
        fun two(where: BackgroundBudget.Where, cores: Int = 8, slow: Boolean = false, lowRam: Boolean = false, free: Long = bigFree, max: Long = heap) =
            MixTempo.twoAtOnce(cores = cores, where = where, slow = slow, lowRam = lowRam, freeHeap = free, maxHeap = max)
        assertTrue("a walk", two(BackgroundBudget.Where.WORKING))
        assertTrue("on screen the processor is there too", two(BackgroundBudget.Where.ON_SCREEN))
        assertFalse("music playing has a core's worth", two(BackgroundBudget.Where.PLAYING))
        assertFalse("out of sight with nothing playing", two(BackgroundBudget.Where.AWAY))
        assertFalse("a slow phone keeps to one", two(BackgroundBudget.Where.WORKING, slow = true))
        assertFalse("four cores aren't enough", two(BackgroundBudget.Where.WORKING, cores = 4))
        assertFalse("a low-RAM device", two(BackgroundBudget.Where.WORKING, lowRam = true))
        assertFalse("a heap for one song's samples", two(BackgroundBudget.Where.WORKING, max = 192L * 1024 * 1024))
        assertFalse("not enough free beside the first song", two(BackgroundBudget.Where.WORKING, free = 64L * 1024 * 1024))
    }

    /** 1.9: a scan's key pass shares the processor with its bands only where it may spare a thread. */
    @Test
    fun aScansPassesShareAThreadOnlyWhereItCan() {
        assertTrue(MixTempo.maySpread(cores = 8, where = BackgroundBudget.Where.WORKING, slow = false))
        assertTrue(MixTempo.maySpread(8, BackgroundBudget.Where.ON_SCREEN, false))
        assertFalse("music playing", MixTempo.maySpread(8, BackgroundBudget.Where.PLAYING, false))
        assertFalse("out of sight with nothing playing", MixTempo.maySpread(8, BackgroundBudget.Where.AWAY, false))
        assertFalse("a slow phone", MixTempo.maySpread(8, BackgroundBudget.Where.WORKING, true))
        assertFalse("four cores", MixTempo.maySpread(4, BackgroundBudget.Where.WORKING, false))
    }
}
