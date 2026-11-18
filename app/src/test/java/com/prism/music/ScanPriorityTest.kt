package com.prism.music.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.2.1: the form being heard's scan goes ahead of a walk's ("Measure songs now") — the walk starts no song while one
 * waits, and gives up the scan it has under way rather than holding the lock to the end of it ([ScanPriority]).
 */
class ScanPriorityTest {
    @Test fun aWalkGivesWayWhileTheHeardFormWaits() {
        val p = ScanPriority()
        p.markWalkScan(true)
        assertFalse("nothing is waiting yet", p.abandonWalkScan())
        p.playingWants()
        assertTrue(p.anyPlaying())
        assertTrue("the heard form's scan is waiting: the walk's gives way", p.abandonWalkScan())
        p.playingDone()
        assertFalse("and goes on once it has landed", p.abandonWalkScan())
        assertFalse(p.anyPlaying())
        p.markWalkScan(false)
    }

    @Test fun onlyAWalkScanIsSetAside() {
        val p = ScanPriority()
        p.playingWants()
        assertFalse("a scan that isn't a walk's (Crossfade's, a song's own) is never abandoned", p.abandonWalkScan())
        p.markWalkScan(true)
        assertTrue(p.abandonWalkScan())
        p.markWalkScan(false)
        assertFalse("the flag is this thread's, and cleared", p.abandonWalkScan())
        p.playingDone()
    }

    @Test fun everyWaitIsCounted() {
        val p = ScanPriority()
        p.playingWants()
        p.playingWants()
        p.playingDone()
        assertTrue("one scan's end doesn't let the walk in while another waits", p.anyPlaying())
        p.playingDone()
        assertFalse(p.anyPlaying())
    }
}
