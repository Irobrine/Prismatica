package com.prism.music.playback

import java.util.concurrent.atomic.AtomicInteger

/**
 * Which scan goes first when the form being heard and another scan both want one (1.2.1).
 *
 * The volume the listener is hearing now is worked out from a scan of that form ([MixTempo.measure]'s measured
 * figure), and it only sets the volume in the form's first seconds ([LoudnessCatchUp]'s window). A running walk the
 * listener started ("Measure songs now", [com.prism.music.data.MeasureSongs]) scans songs one at a time under the same
 * lock, so without this the heard form's figure queued behind whole songs nobody was hearing and landed too late: a
 * file on the phone has no YouTube figure to stand in, so it played at 0 dB for the whole first listen.
 *
 * A scan for the heard form says so while it waits ([playingWants]); a background scan ([deferToPlaying]) starts no
 * song while one waits, and the scan it has under way gives way where it next checks ([abandonWalkScan], polled by
 * [MixTempo.ease]) rather than holding the lock to the end of a minute-long scan. What it gives up isn't a failure:
 * the song is still unmeasured, so it's walked again.
 *
 * Process-wide ([scanPriority]), as there is one player and one lock, and cheap: a counter and a thread flag.
 */
internal class ScanPriority {
    private val playing = AtomicInteger()
    private val walkScan = ThreadLocal<Boolean>()

    /** A scan for the form being heard is waiting: background scans hold off, and one under way gives way. */
    fun playingWants() {
        playing.incrementAndGet()
    }

    /** That scan is done (or was cancelled): background scans go on. */
    fun playingDone() {
        playing.decrementAndGet()
    }

    /** Whether the form being heard is waiting for a scan now. */
    fun anyPlaying(): Boolean = playing.get() > 0

    /** Marks this thread as scanning for a background walk (or clears it), so it may be set aside. */
    fun markWalkScan(walking: Boolean) {
        if (walking) walkScan.set(true) else walkScan.remove()
    }

    /** Whether the scan this thread is in should give way to the form being heard now (a walk's, while one waits). */
    fun abandonWalkScan(): Boolean = walkScan.get() == true && playing.get() > 0
}

/** Thrown inside a background walk's scan that gives way to the form being heard ([ScanPriority.abandonWalkScan]). */
internal class ScansAbandoned : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
}

/** The one player's scans, process-wide: see [ScanPriority]. */
internal val scanPriority = ScanPriority()
