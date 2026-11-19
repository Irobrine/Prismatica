package com.prism.music

import com.prism.music.playback.SleepAudio
import com.prism.music.playback.SleepBpm
import com.prism.music.playback.SleepClock
import com.prism.music.playback.SleepConfig
import com.prism.music.playback.SleepFade
import com.prism.music.playback.SleepTimerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sleep timer's own maths and session rules (1.9): the fade's curve and its exact zero, the fade always shorter than
 * the countdown, BPM filtering (unknown tempos included), the deadline and +/-5 editing, "finish current song" with its
 * overtime, and the volume handed back when a session ends or is cancelled.
 */
class SleepTimerTest {
    private class FakeAudio : SleepAudio {
        override var itemKey: String? = "0:a"
        override var isPlaying: Boolean = true
        override var positionMs: Long = 0L
        override var durationMs: Long = 180_000L
        override var speed: Float = 1f

        val fades = ArrayList<Float>()
        var sessionLevel = 1f
        var pauseAtEndSet = false
        var pauses = 0
        var restores = 0
        val reports = ArrayList<Int>()

        override fun pause() { pauses++; isPlaying = false }
        override fun setPauseAtEnd(on: Boolean) { pauseAtEndSet = on }
        override fun setFade(gain: Float) { fades += gain }
        override fun setSessionVolume(volume: Float) { sessionLevel = volume }
        override fun restoreVolume() { restores++; fades += 1f; sessionLevel = 1f }
        override fun reportNoEligibleSongs(maxBpm: Int) { reports += maxBpm }
    }

    private var now = 1_000_000L

    private fun engine(audio: FakeAudio, bpms: Map<String, Double?> = emptyMap(), wall: () -> Long = { now }, onEnded: () -> Unit = {}): SleepTimerEngine =
        SleepTimerEngine(audio, { bpms[it] }, { now }, wall, onEnded)

    private fun config(minutes: Int = 30, fade: Int = 0, maxBpm: Int = 0, finish: Boolean = false, volume: Float = 1f) =
        SleepConfig(minutes, fade, maxBpm, finish, volume)

    // ---------------------------------------------------------------- the fade

    @Test fun fadeIsFullAtTheStartAndExactlySilentAtTheEnd() {
        assertEquals(1f, SleepFade.gain(0f), 0f)
        assertEquals(0f, SleepFade.gain(1f), 0f)
        assertEquals(0f, SleepFade.gain(1.7f), 0f)
        assertEquals(1f, SleepFade.gain(-0.3f), 0f)
    }

    @Test fun theFadeFallsEvenlyInTheDecibelDomain() {
        fun db(g: Float) = 20.0 * kotlin.math.log10(g.toDouble())
        // Noticeable from the beginning: a tenth of the way in, several dB are already gone (the old power curve was
        // under 1 dB there).
        assertTrue("only ${db(SleepFade.gain(0.1f))} dB at a tenth", db(SleepFade.gain(0.1f)) < -3.0)
        // Each fifth of the main fade takes a similar share (48 dB over 94%, so about 10 dB each), not the last fifth.
        val fifths = listOf(0f, 0.2f, 0.4f, 0.6f, 0.8f).map { SleepFade.gain(it) }
        val drops = fifths.zipWithNext { a, b -> db(a) - db(b) }
        for ((i, drop) in drops.withIndex()) {
            assertTrue("fifth $i takes $drop dB", drop in 8.0..11.5)
        }
        assertTrue("the first fifth is not the biggest: $drops", drops.max() - drops.min() < 3.0)
        var previous = 1f
        for (i in 0..1000) {
            val g = SleepFade.gain(i / 1000f)
            assertTrue("gain went back up at $i", g <= previous + 1e-6f)
            previous = g
        }
        // The final taper: the last hundredth is much smaller than the middle, and the end is exactly zero with no step.
        val last = SleepFade.gain(0.99f) - SleepFade.gain(1f)
        val middle = SleepFade.gain(0.50f) - SleepFade.gain(0.51f)
        assertTrue("the end should taper, not cut: $last against $middle", last < middle)
        assertTrue("a step at the very end: ${SleepFade.gain(0.999999f)}", SleepFade.gain(0.999999f) < 1e-5f)
    }

    @Test fun everyFadeLengthUsesTheSameEvenShape() {
        // 15, 30, 60 and 120 seconds: a quarter of the way in the volume has already come down clearly, and the rest is
        // spread over the fade rather than saved for the end.
        for (seconds in listOf(15, 30, 60, 120)) {
            now = 1_000_000L
            val audio = FakeAudio()
            val engine = engine(audio)
            engine.start(config(minutes = 10, fade = seconds))
            val deadline = engine.deadlineElapsed
            val fadeStart = deadline - seconds * 1_000L
            val gains = listOf(0.25f, 0.5f, 0.75f).map { fraction ->
                now = fadeStart + (seconds * 1_000L * fraction).toLong()
                engine.tick()
                audio.fades.last()
            }
            assertTrue("${seconds}s: too loud a quarter in (${gains[0]})", gains[0] < 0.5f)
            assertTrue("${seconds}s: not falling (${gains})", gains[0] > gains[1] && gains[1] > gains[2])
            assertEquals(SleepFade.gain(0.5f), gains[1], 1e-4f)
            assertEquals(0f, SleepFade.gain(1f), 0f)
        }
    }

    @Test fun theFadeDoesNotDependOnHowOftenItIsTicked() {
        now = 1_000_000L
        val seldomAudio = FakeAudio()
        val seldom = engine(seldomAudio)
        seldom.start(config(minutes = 1, fade = 10))
        val deadline = seldom.deadlineElapsed
        now = deadline - 5_000
        seldom.tick()
        val atHalf = seldomAudio.fades.last()

        now = 1_000_000L
        val oftenAudio = FakeAudio()
        val often = engine(oftenAudio)
        often.start(config(minutes = 1, fade = 10))
        var t = now
        while (t < deadline - 5_000) {
            now = t
            often.tick()
            t += 100
        }
        now = deadline - 5_000
        often.tick()
        assertEquals(atHalf, oftenAudio.fades.last(), 1e-6f)
    }

    @Test fun fadeIsStrictlyShorterThanTheCountdown() {
        assertEquals(0L, SleepClock.fadeMs(30, 0))
        assertEquals(10_000L, SleepClock.fadeMs(30, 10))
        assertEquals(59_999L, SleepClock.fadeMs(1, 60))
        assertEquals(0L, SleepClock.fadeMs(0, 30))
        assertEquals(9_000L, SleepClock.fadeMs(30, 9))
        // It can never take the whole countdown.
        assertTrue(SleepClock.fadeMs(10, 900) < 10 * 60_000L)
    }

    // ---------------------------------------------------------------- BPM

    @Test fun bpmLimitFiltersFasterAndUnknownSongs() {
        assertTrue(SleepBpm.allows(120.0, 120))
        assertTrue(SleepBpm.allows(90.0, 120))
        assertFalse(SleepBpm.allows(121.0, 120))
        assertFalse(SleepBpm.allows(null, 120))
        assertFalse(SleepBpm.allows(Double.NaN, 120))
        assertFalse(SleepBpm.allows(Double.POSITIVE_INFINITY, 120))
        assertFalse(SleepBpm.allows(0.0, 120))
        assertFalse(SleepBpm.allows(-120.0, 120))
        // 0 means no limit at all.
        assertTrue(SleepBpm.allows(null, 0))
        assertTrue(SleepBpm.allows(300.0, 0))
    }

    @Test fun theSessionFiltersAndItsTemporaryOverrideOnlyWhileItRuns() {
        val audio = FakeAudio()
        val engine = engine(audio, mapOf("fast" to 150.0, "slow" to 100.0, "unknown" to null))
        engine.start(config(maxBpm = 120))
        assertTrue(engine.blocksBpm("fast"))
        assertTrue(engine.blocksBpm("unknown"))
        assertFalse(engine.blocksBpm("slow"))
        // Only a session has a limit to override; and the override is gone when the session ends.
        engine.setBpmDisabled(true)
        assertFalse(engine.blocksBpm("fast"))
        assertFalse(engine.blocksBpm("unknown"))
        engine.setBpmDisabled(false)
        assertTrue(engine.blocksBpm("fast"))
        engine.cancel()
        assertFalse(engine.blocksBpm("fast"))
        assertFalse(engine.blocksBpm("unknown"))
    }

    // ---------------------------------------------------------------- the countdown

    @Test fun startingAppliesTheChosenVolumeAndTheDeadline() {
        val audio = FakeAudio()
        val engine = engine(audio)
        engine.start(config(minutes = 30, volume = 0.4f))
        assertTrue(engine.active.value)
        assertEquals(now + 30 * 60_000L, engine.deadlineElapsed)
        assertEquals(now + 30 * 60_000L, engine.wallDeadline.value)
        assertEquals(0.4f, audio.sessionLevel, 0f)
        assertEquals(1f, audio.fades.last(), 0f)
        assertEquals(false, audio.pauseAtEndSet)
    }

    @Test fun theTimerStopsAtItsDeadlineWithTheGainAtZero() {
        val audio = FakeAudio()
        val engine = engine(audio)
        engine.start(config(minutes = 10, fade = 5))
        val deadline = engine.deadlineElapsed
        now = deadline - 2_500
        engine.tick()
        // Half a 5 s fade: the power curve's figure, read from the clock rather than stepped down.
        assertEquals(SleepFade.gain(0.5f), audio.fades.last(), 1e-4f)
        assertEquals(0, audio.pauses)
        now = deadline
        engine.tick()
        assertEquals(1, audio.pauses)
        assertFalse(engine.active.value)
        assertEquals(0L, engine.wallDeadline.value)
        assertEquals(0f, audio.fades.first { it == 0f }, 0f)
    }

    @Test fun aLateTickLandsOnTheRightGainRatherThanBehindIt() {
        val audio = FakeAudio()
        val engine = engine(audio)
        engine.start(config(minutes = 10, fade = 10))
        val deadline = engine.deadlineElapsed
        // No ticks during the fade, then one at the deadline: it is exactly silent, not a step behind.
        now = deadline
        engine.tick()
        assertEquals(0f, audio.fades.last(), 0f)
    }

    @Test fun minusAndPlusFiveMoveTheRunningTimerAtOnce() {
        val audio = FakeAudio()
        val engine = engine(audio)
        engine.start(config(minutes = 30, fade = 10))
        val start = engine.deadlineElapsed
        now += 10 * 60_000L
        engine.adjust(5)
        assertEquals(start + 5 * 60_000L, engine.deadlineElapsed)
        assertEquals(now + 25 * 60_000L, engine.wallDeadline.value)
        engine.adjust(-5)
        assertEquals(start, engine.deadlineElapsed)
        // Never below zero: five minutes before the end, -5 leaves nothing and the timer stops there.
        now = engine.deadlineElapsed
        engine.adjust(-5)
        assertFalse(engine.active.value)
        assertEquals(0L, engine.wallDeadline.value)
        assertEquals(1, audio.pauses)
    }

    @Test fun cancellingRestoresTheVolumeAndLeavesOneSessionOnly() {
        val audio = FakeAudio()
        val engine = engine(audio)
        engine.start(config(minutes = 30, volume = 0.2f))
        engine.start(config(minutes = 45, volume = 0.6f))
        // Starting again replaces the one session; there is never a second timer.
        assertEquals(now + 45 * 60_000L, engine.deadlineElapsed)
        assertEquals(0.6f, audio.sessionLevel, 0f)
        engine.cancel()
        assertFalse(engine.active.value)
        assertEquals(1f, audio.sessionLevel, 0f)
        assertTrue(audio.restores >= 1)
        assertEquals(false, audio.pauseAtEndSet)
    }

    // ---------------------------------------------------------------- finish current song / overtime

    @Test fun finishCurrentSongStopsAtTheSongsEndInsteadOfTheDeadline() {
        val audio = FakeAudio()
        val engine = engine(audio)
        // 1 minute timer, 3 minute song: the song is allowed to finish, so the stop is two minutes after the deadline.
        engine.start(config(minutes = 1, fade = 10, finish = true))
        audio.positionMs = 0
        val start = now
        now = start + 60_000L
        audio.positionMs = 60_000L
        engine.tick()
        assertTrue(engine.active.value)
        // Overtime: the player is set to pause exactly at the end of the song being finished.
        assertTrue(audio.pauseAtEndSet)
        assertEquals(start + 180_000L, engine.stopAt())
        now = start + 90_000L
        audio.positionMs = 90_000L
        engine.tick()
        assertTrue(audio.pauseAtEndSet)
        // The fade still starts ten seconds before the real end, at 170 s, not at the deadline.
        now = start + 165_000L
        audio.positionMs = 165_000L
        engine.tick()
        assertEquals(1f, audio.fades.last(), 1e-4f)
        now = start + 175_000L
        audio.positionMs = 175_000L
        engine.tick()
        assertEquals(SleepFade.gain(0.5f), audio.fades.last(), 1e-4f)
        now = start + 180_000L
        audio.positionMs = 180_000L
        engine.tick()
        assertEquals(1, audio.pauses)
        assertFalse(engine.active.value)
    }

    @Test fun aSongThatEndsBeforeTheDeadlineLeavesTheDeadlineStanding() {
        val audio = FakeAudio()
        val engine = engine(audio)
        engine.start(config(minutes = 10, finish = true))
        audio.durationMs = 120_000
        now += 60_000
        audio.positionMs = 60_000
        // This song ends at 2 minutes, before the 10 minute deadline: nothing to finish, the deadline is the stop.
        assertEquals(engine.deadlineElapsed, engine.stopAt())
    }

    @Test fun skippingDuringOvertimeStopsAndFinishingTheSongBeforeTheDeadlineDoesNot() {
        val audio = FakeAudio()
        val engine = engine(audio)
        engine.start(config(minutes = 1, finish = true))
        val start = now
        // A new song before the deadline only moves the song being finished.
        now = start + 30_000
        audio.itemKey = "1:b"
        engine.onItemChanged()
        assertTrue(engine.active.value)
        // At the deadline, that song's end is the stop; a skip after it ends the session instead of playing on.
        now = start + 61_000
        audio.positionMs = 1_000
        audio.durationMs = 200_000
        engine.tick()
        assertTrue(engine.active.value)
        audio.itemKey = "2:c"
        engine.onItemChanged()
        assertFalse(engine.active.value)
        assertEquals(1, audio.pauses)
    }

    @Test fun aTransitionAfterTheDeadlineStopsEvenWhenNoTickMarkedTheOvertime() {
        val audio = FakeAudio()
        val engine = engine(audio)
        engine.start(config(minutes = 1, finish = true))
        // The main thread was busy through the deadline; the next item starts before a tick could mark the overtime.
        now = engine.deadlineElapsed + 500
        audio.itemKey = "1:b"
        engine.onItemChanged()
        assertFalse(engine.active.value)
        assertEquals(1, audio.pauses)
    }

    @Test fun aPausedTimerStopsAtTheDeadlineWithoutOvertime() {
        val audio = FakeAudio()
        val engine = engine(audio)
        engine.start(config(minutes = 1, finish = true))
        audio.isPlaying = false
        now += 60_000
        engine.tick()
        assertFalse(engine.active.value)
        assertEquals(1, audio.pauses)
    }

    // ---------------------------------------------------------------- volume

    @Test fun aSessionThatRunsOutEndsButACancelledOneIsNotTreatedAsEnded() {
        val audio = FakeAudio()
        var ended = 0
        val engine = engine(audio, onEnded = { ended++ })
        engine.start(config(minutes = 1, maxBpm = 120))
        now += 60_000
        engine.tick()
        assertFalse(engine.active.value)
        assertEquals(1, ended)
        // Cancelling ends the session too, but it must not look like the timer ran out (its choices are kept).
        engine.start(config(minutes = 10, maxBpm = 120))
        engine.cancel()
        assertEquals(1, ended)
    }

    @Test fun theSessionsVolumeCanBeChangedWhileItRuns() {
        val audio = FakeAudio()
        val engine = engine(audio)
        engine.start(config(minutes = 10, fade = 10, volume = 0.2f))
        assertEquals(0.2f, audio.sessionLevel, 0f)
        engine.setVolume(0.7f)
        assertEquals(0.7f, audio.sessionLevel, 0f)
        // The fade is still on top of the new volume, and the listener's own volume comes back at the end.
        val deadline = engine.deadlineElapsed
        now = deadline - 5_000
        engine.tick()
        assertEquals(SleepFade.gain(0.5f), audio.fades.last(), 1e-4f)
        engine.cancel()
        assertEquals(1f, audio.sessionLevel, 0f)
    }

    @Test fun theVolumeIsTheSessionVolumeTimesTheFade() {
        val audio = FakeAudio()
        val engine = engine(audio)
        engine.start(config(minutes = 10, fade = 10, volume = 0.5f))
        assertEquals(0.5f, audio.sessionLevel, 0f)
        assertEquals(1f, audio.fades.last(), 0f)
        val deadline = engine.deadlineElapsed
        now = deadline - 5_000
        engine.tick()
        assertEquals(SleepFade.gain(0.5f), audio.fades.last(), 1e-4f)
        // Cancelling mid-fade puts the listener's own volume back at once (not zero).
        audio.isPlaying = true
        engine.cancel()
        assertEquals(1f, audio.sessionLevel, 0f)
        assertEquals(1f, audio.fades.last(), 0f)
    }
}
