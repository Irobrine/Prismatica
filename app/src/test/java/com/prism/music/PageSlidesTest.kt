package com.prism.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** #208 (1.7.9.2): "Slide between screens" off leaves the fades alone; on, every way between pages slides. */
class PageSlidesTest {
    @Test fun offLeavesTheFades() {
        val off = PageSlides(on = false, rtl = false)
        listOf(off.enter, off.exit, off.popEnter, off.popExit).forEach { assertNull(it) }
    }

    @Test fun onSlidesEveryWayInBothDirections() {
        for (rtl in listOf(false, true)) {
            val on = PageSlides(on = true, rtl = rtl)
            listOf(on.enter, on.exit, on.popEnter, on.popExit).forEach { assertNotNull(it) }
        }
    }

    /** #208: the slide follows the tab bar's places, whichever way reading goes. */
    @Test fun aTabComesInFromItsSideOfTheBar() {
        val tabs = listOf("home", "search", "library")
        // The second tab is right of the first: it comes in from the right, and the first from the left.
        assertEquals(1, pageSlideSide("home", "search", tabs, rtl = false))
        assertEquals(-1, pageSlideSide("library", "search", tabs, rtl = false))
        assertEquals("left to right", 1, pageSlideSide("home", "library", tabs, rtl = false))
        // In right-to-left languages the bar is mirrored too.
        assertEquals(-1, pageSlideSide("home", "search", tabs, rtl = true))
        assertEquals(1, pageSlideSide("library", "search", tabs, rtl = true))
        // A page opened from a tab, and Back, keep reading's side.
        assertEquals(1, pageSlideSide("album/{id}", "library", tabs, rtl = false))
        assertEquals(-1, pageSlideSide("library", "album/{id}", tabs, rtl = true))
        // The same tab (or an unknown one) has no side to follow.
        assertEquals(1, pageSlideSide("library", "library", tabs, rtl = false))
        assertEquals(-1, pageSlideSide("library", "library", tabs, rtl = true))
    }
}
