package com.prism.music

import com.prism.music.data.enhance.EngineSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sessions a batch reuses: built once, closed when the queue is done, and rebuilt only when they have to be. */
class EngineSlotTest {
    private class Engine : AutoCloseable {
        var closed = false
        override fun close() { closed = true }
    }

    @Test fun aSessionIsBuiltOnceAndReused() {
        var built = 0
        val slot = EngineSlot { Engine().also { built++ } }
        val first = slot.get()
        assertFalse("built before it was asked for", slot.cached().not())
        assertSame("a second job pays nothing for the model", first, slot.get())
        assertEquals(1, built)
    }

    @Test fun droppingClosesItAndTheNextJobBuildsAFreshOne() {
        var built = 0
        val slot = EngineSlot { Engine().also { built++ } }
        val first = slot.get()
        slot.drop()
        assertTrue("the session was not closed", first.closed)
        assertFalse(slot.cached())
        val second = slot.get()
        assertEquals(2, built)
        assertFalse("a new session, not the closed one", second === first)
    }

    @Test fun droppingASlotThatWasNeverUsedIsHarmless() {
        val slot = EngineSlot { Engine() }
        slot.drop()
        assertFalse(slot.cached())
        assertTrue(EngineSlot { Engine() }.cached().not())
    }

    @Test fun aFailedBuildIsNotKept() {
        var attempts = 0
        val slot = EngineSlot<Engine> {
            attempts++
            if (attempts == 1) error("no model") else Engine()
        }
        runCatching { slot.get() }
        assertFalse("a half-built session was kept", slot.cached())
        slot.get()
        assertEquals("the next job tried again", 2, attempts)
    }
}
