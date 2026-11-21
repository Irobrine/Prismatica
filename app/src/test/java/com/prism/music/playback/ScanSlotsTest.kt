package com.prism.music.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1.9: the places whole scans take ([ScanSlots]) — one at a time as they always were, or two during a walk, with an
 * exclusive scan (the form being heard) waiting for all of them. A place lost to a cancelled scan would hang every scan
 * in the process for good, so the accounting is what these check.
 */
class ScanSlotsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun close() = scope.cancel()

    /** Waits for [check] to hold, failing with what was waited for rather than hanging. */
    private fun until(what: String, check: () -> Boolean) = runBlocking {
        try {
            withTimeout(5_000) { while (!check()) delay(5) }
        } catch (e: Throwable) {
            throw AssertionError("$what: it didn't happen", e)
        }
    }

    @Test
    fun twoSharedScansRunAtOnce() {
        val slots = ScanSlots(2)
        val both = CompletableDeferred<Unit>()
        val inside = AtomicInteger()
        val held = CompletableDeferred<Unit>()
        repeat(2) {
            scope.launch {
                slots.with(shared = true) {
                    if (inside.incrementAndGet() == 2) both.complete(Unit)
                    withTimeout(5_000) { both.await() }
                    held.await()
                }
            }
        }
        until("both inside") { both.isCompleted }
        assertEquals("both places are taken", 0, slots.free)
        held.complete(Unit)
        until("both finished") { slots.free == 2 }
    }

    @Test
    fun anExclusiveScanWaitsForTheOneUnderWay_andThenRunsAlone() {
        val slots = ScanSlots(2)
        val sharedIn = CompletableDeferred<Unit>()
        val releaseShared = CompletableDeferred<Unit>()
        val exclusiveIn = CompletableDeferred<Unit>()
        val releaseExclusive = CompletableDeferred<Unit>()
        scope.launch { slots.with(shared = true) { sharedIn.complete(Unit); releaseShared.await() } }
        until("the shared scan") { sharedIn.isCompleted }
        scope.launch { slots.with(shared = false) { exclusiveIn.complete(Unit); releaseExclusive.await() } }
        runBlocking { delay(100) }
        assertFalse("the exclusive scan waits while one runs", exclusiveIn.isCompleted)
        releaseShared.complete(Unit)
        until("the exclusive scan") { exclusiveIn.isCompleted }
        assertEquals("and it holds every place", 0, slots.free)
        // A shared scan can't slip in beside it.
        var sharedRan = false
        scope.launch { slots.with(shared = true) { sharedRan = true } }
        runBlocking { delay(100) }
        assertFalse("nothing runs beside an exclusive scan", sharedRan)
        releaseExclusive.complete(Unit)
        until("the places are free again") { slots.free == 2 }
    }

    /**
     * The one that matters: a scan cancelled while it waits for its second place must not keep the first. Before, both
     * places were taken after the `try`, so a cancellation between them held one for the life of the process and every
     * later scan hung.
     */
    @Test
    fun aScanCancelledWhileItWaitsForItsSecondPlaceKeepsNothing() {
        val slots = ScanSlots(2)
        val sharedIn = CompletableDeferred<Unit>()
        val releaseShared = CompletableDeferred<Unit>()
        scope.launch { slots.with(shared = true) { sharedIn.complete(Unit); releaseShared.await() } }
        until("the shared scan") { sharedIn.isCompleted }
        assertEquals("one place each", 1, slots.free)
        // The exclusive scan takes the free place, then waits for the one the shared scan holds.
        val waiting = scope.launch { slots.with(shared = false) { } }
        until("the exclusive scan to take its first place") { slots.free == 0 }
        waiting.cancel()
        runBlocking { runCatching { waiting.join() } }
        releaseShared.complete(Unit)
        until("both places back") { slots.free == 2 }
    }

    @Test
    fun aScanThatFinishesGivesItsPlaceBack() {
        val slots = ScanSlots(2)
        runBlocking {
            repeat(5) {
                slots.with(shared = true) { }
                assertEquals(2, slots.free)
            }
            repeat(5) {
                slots.with(shared = false) { }
                assertEquals(2, slots.free)
            }
        }
    }
}
