package com.prism.music

import com.prism.music.data.MeasureSongs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Settings → Sound & quality's "Measure songs now" (1.1.3, [MeasureSongs]): a set of songs through the whole-song scan,
 * one at a time, counting the ones it has walked; a stop measures nothing more and keeps its count, and being carried on
 * from there measures only what's left; the songs a walk is made of are each song's form (the recording that actually
 * plays), once each, minus what's measured already; and whether songs not on the phone may be fetched is the network's
 * and the listener's setting's together.
 */
class MeasureSongsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The forms measured so far, by the current scan (as [com.prism.music.playback.MixTempo.loudnessCurrent] has it). */
    private val measuredNow = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** The recording each song plays as, where that isn't the song itself (a version picked for it). */
    private val versions = mutableMapOf<String, String>()

    /** What the walk asked for: the form, the song it plays for, and whether it could use the network. */
    private val asked = java.util.Collections.synchronizedList(ArrayList<Triple<String, String?, Boolean>>())

    @After fun close() = scope.cancel()

    private fun engine(
        metered: Boolean = false,
        measure: suspend (String, String?, Boolean) -> Unit = { form, song, network -> asked += Triple(form, song, network); measuredNow += form },
    ) = MeasureSongs(scope, measure, { it in measuredNow }, { versions[it] ?: it }, { !metered })


    /** Waits for [check] to hold (the walk runs on threads of its own), failing with what was waited for instead of hanging. */
    private fun until(what: String, check: () -> Boolean) = runBlocking {
        try {
            withTimeout(5_000) { while (!check()) delay(5) }
        } catch (e: Throwable) {
            throw AssertionError("$what: it didn't happen", e)
        }
    }

    /** Each song is asked for as its own form, and the song it plays for is carried only where that's another id. */
    @Test fun everySongIsMeasuredAsTheRecordingThatPlays() {
        versions["picked"] = "cover"
        val e = engine()
        val pairs = e.pending(listOf("a", "picked", "b"))
        assertEquals(listOf("a" to "a", "cover" to "picked", "b" to "b"), pairs)
        e.start(pairs, network = false)
        until("the walk") { e.progress.value == null && asked.size == 3 }
        assertEquals(
            "a form that is its own song carries no source (null), and every one is asked for with the network it was given",
            listOf(Triple("a", null, false), Triple("cover", "picked", false), Triple("b", null, false)), asked.toList(),
        )
    }

    /** What's measured with the current scan isn't work: it's left out of the pairs, and one form is one scan. */
    @Test fun measuredFormsAndRepeatsAreLeftOut() {
        measuredNow += "b"
        measuredNow += "cover"
        versions["a"] = "cover"
        val e = engine()
        assertEquals("'a' plays as 'cover', which is measured, and 'b' is", listOf("c" to "c"), e.pending(listOf("a", "b", "c", "c")))
        assertEquals(emptyList<Pair<String, String>>(), e.pending(emptyList()))
    }

    /** One at a time, and the count is what has been walked: 0 of 3 while the first is measured, 3 of 3 at the end. */
    @Test fun itWalksOneAtATimeAndCountsThem() {
        val gates = List(3) { CompletableDeferred<Unit>() }
        val at = AtomicInteger()
        val e = engine(measure = { form, _, _ -> asked += Triple(form, null, false); gates[at.getAndIncrement()].await() })
        e.start(listOf("a" to "a", "b" to "b", "c" to "c"), network = false)
        until("the first song") { e.progress.value == (0 to 3) }
        assertEquals("only the first is asked for", listOf("a"), asked.map { it.first })
        gates[0].complete(Unit)
        until("the second song") { e.progress.value == (1 to 3) }
        assertEquals(listOf("a", "b"), asked.map { it.first })
        gates[1].complete(Unit)
        until("the third song") { e.progress.value == (2 to 3) }
        gates[2].complete(Unit)
        until("the end") { e.progress.value == null }
        assertEquals(listOf("a", "b", "c"), asked.map { it.first })
    }

    /**
     * Stopped part-way: the song being measured is cancelled, nothing after it is measured, and the count it got to is
     * kept — not cleared — so the row can carry on from there rather than start the set over.
     */
    @Test fun stoppingItKeepsTheCountAndMeasuresNothingMore() {
        val gate = CompletableDeferred<Unit>()
        val entered = AtomicInteger()
        val e = engine(measure = { form, _, _ -> asked += Triple(form, null, false); if (entered.incrementAndGet() == 1) gate.await(); measuredNow += form })
        e.start(listOf("a" to "a", "b" to "b", "c" to "c", "d" to "d"), network = false)
        until("the first song") { entered.get() == 1 }
        assertEquals(0 to 4, e.progress.value)
        assertTrue(e.running.value)
        e.stop()
        until("the walk to end") { !e.running.value }
        assertEquals("its count stays where it got to", 0 to 4, e.progress.value)
        assertEquals("the one being measured was cancelled inside the scan", 1, entered.get())
        runBlocking { delay(50) }
        assertEquals("and no song after it", 1, entered.get())
        assertFalse("nothing it walked was counted measured", "a" in measuredNow)
    }

    /**
     * Carried on after a stop ([MeasureSongs.resume]), a walk measures only what's left — the finished song never
     * again — and its count runs on from where it stopped: 1 of 3 at the stop, then on to the end.
     */
    @Test fun aStoppedWalkCarriesOnWhereItStopped() {
        val gate = CompletableDeferred<Unit>()
        val e = engine(measure = { form, _, _ -> asked += Triple(form, null, false); if (form == "b") gate.await(); measuredNow += form })
        e.start(listOf("a" to "a", "b" to "b", "c" to "c"), network = false)
        until("the second song") { asked.map { it.first } == listOf("a", "b") }
        assertEquals("the first finished, the second is under way", 1 to 3, e.progress.value)
        e.stop()
        until("the walk to end") { !e.running.value }
        assertEquals("its count stays at what finished", 1 to 3, e.progress.value)
        gate.complete(Unit)
        e.resume()
        until("the rest") { e.progress.value == null && asked.map { it.first } == listOf("a", "b", "b", "c") }
        assertFalse("the finished first song is never walked again", asked.count { it.first == "a" } > 1)
    }

    /** A second tap stops the walk running and starts its own: two walks never count at once. */
    @Test fun aSecondStartTakesOver() {
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        val e = engine(measure = { form, _, _ -> if (form == "a" || form == "b") first.await() else second.await() })
        e.start(listOf("a" to "a", "b" to "b"), network = false)
        until("the first walk") { e.progress.value == (0 to 2) && e.running.value }
        e.start(listOf("c" to "c", "d" to "d", "e" to "e"), network = false)
        until("the newer walk's count at once") { e.progress.value == (0 to 3) }
        second.complete(Unit)
        until("its end") { e.progress.value == null && !e.running.value }
        assertFalse("the first walk was stopped, not waited for", first.isCompleted)
    }

    /** Songs not on the phone are fetched on an unmetered network, or with mobile data allowed for measuring. */
    @Test fun theNetworkIsTheUnmeteredOneOrTheListenersSetting() {
        assertTrue("on Wi-Fi", engine(metered = false).network(cellular = false))
        assertFalse("on mobile data, not allowed", engine(metered = true).network(cellular = false))
        assertTrue("on mobile data, allowed", engine(metered = true).network(cellular = true))

        val e = engine(metered = true)
        val network = e.network(cellular = false)
        e.start(e.pending(listOf("a")), network)
        until("the walk") { e.progress.value == null && asked.isNotEmpty() }
        assertEquals("a metered network with it off: nothing is fetched", false, asked[0].third)
    }
}
