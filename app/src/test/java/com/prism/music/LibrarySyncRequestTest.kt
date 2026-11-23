package com.prism.music

import org.junit.After
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The launch and the Library both ask for the startup sync, and both asks are deferred to the same moment
 * ([com.prism.music.ui.screens.LibraryScreen]'s entry included). They are one sync: a second ask while the deferred work
 * is still waiting or running returns that same job instead of starting another, which would read the account twice and
 * write the same likes twice.
 */
@RunWith(RobolectricTestRunner::class)
class LibrarySyncRequestTest {
    /** A container of the test's own, closed as [TestContainers] says to after the test. */
    private val made = lazy { AppContainer(RuntimeEnvironment.getApplication()) }

    @After fun close() = made.closeAfterTest()

    @Test
    fun theLaunchAndTheLibraryAskForOneSync() {
        val library = made.value.library
        // No activity is started in a test, so the deferred work is still waiting for a screen that never comes.
        val first = library.syncInBackground()
        assertTrue("the request is waiting for the first frame", first.isActive)
        val second = library.syncInBackground()
        assertSame("a second ask while it waits is the same work", first, second)
        // A request that was given up on must not stop the next one from being made.
        first.cancel()
        val third = library.syncInBackground()
        assertNotSame("a cancelled sync no longer answers for the library", first, third)
        third.cancel()
    }
}
