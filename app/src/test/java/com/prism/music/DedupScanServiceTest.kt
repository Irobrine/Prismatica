package com.prism.music

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.prism.music.playback.DedupScanService
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowService

/**
 * The duplicate scan's notice only exists while its walk does. A tap starts the walk before the service is bound
 * (DedupScanner.start sets running first), so a service that finds no walk running must put itself away — a walk with
 * nothing to do must not leave a foreground notice and a wake lock behind for the rest of the process's life.
 */
@RunWith(RobolectricTestRunner::class)
class DedupScanServiceTest {
    private var service: ServiceController<DedupScanService>? = null

    @Test
    fun aServiceWithNoWalkToWatchStopsItself() {
        val app = ApplicationProvider.getApplicationContext<PrismApp>()
        assertFalse("nothing is scanned in a fresh test install", app.container.dedup.scanner.running.value)

        val controller = Robolectric.buildService(DedupScanService::class.java).create()
        service = controller
        shadowOf(Looper.getMainLooper()).idle()
        val shadow: ShadowService = shadowOf(controller.get())
        assertTrue("the service must not hold a notice for a walk that isn't running", shadow.isStoppedBySelf)
    }

    /**
     * The service's own collector and the app's work are stopped before the database closes, as `closeAfterTest` does
     * for a container a test made itself: a read of the closed database lands in whichever test runs next, as
     * "uncaught exceptions before the test started".
     */
    @After
    fun close() {
        runCatching { service?.destroy() }
        val app = ApplicationProvider.getApplicationContext<PrismApp>()
        val container = runCatching { app.container }.getOrNull() ?: return
        container.scope.cancel()
        val main = shadowOf(Looper.getMainLooper())
        val job = container.scope.coroutineContext[Job]
        val until = System.currentTimeMillis() + 5_000
        while (job != null && !job.isCompleted && System.currentTimeMillis() < until) {
            runCatching { main.idle() }
            Thread.sleep(5)
        }
        // Room's invalidation refresh runs on its own executor, outside the container's scope: let one started finish.
        runCatching { main.idle() }
        Thread.sleep(50)
        runCatching { app.onTerminate() }
    }
}
