package com.prism.music

import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.container
import com.prism.music.data.enhance.EnhanceKind
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The queue's foreground service on the phone: a batch runs for an hour, so it has to come up (which Android only allows
 * from a visible app, and only with a declared type) and put itself away when there's nothing left.
 */
@RunWith(AndroidJUnit4::class)
class EnhanceQueueAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun theQueueRunsUnderItsOwnForegroundService() {
        // Android 16 refuses a foreground service from an app that isn't visible, and freezes one with no window.
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        Thread.sleep(2_000)
        val notifications = NotificationManagerCompat.from(context)
        // A channel from an earlier run would make the check below vacuous.
        runCatching { notifications.deleteNotificationChannel(CHANNEL) }

        // Nothing picked: the service still comes up for the walk and finds no work in it.
        context.container.enhance.enqueue(emptyList(), setOf(EnhanceKind.LYRICS))

        val until = SystemClock.elapsedRealtime() + 10_000
        var channel = notifications.getNotificationChannel(CHANNEL)
        while (channel == null && SystemClock.elapsedRealtime() < until) {
            Thread.sleep(100)
            channel = notifications.getNotificationChannel(CHANNEL)
        }
        assertNotNull("the queue's service never came up (a refused foreground start throws in its onCreate)", channel)
        assertTrue("a batch must stay silent: importance ${channel!!.importance}", channel.importance <= 2)

        // The walk had nothing in it, so the service puts itself away rather than staying up for an hour.
        Thread.sleep(5_000)
        assertNull(
            "the service stayed in the foreground with nothing queued",
            notifications.activeNotifications.firstOrNull { it.notification.channelId == CHANNEL },
        )
    }

    private companion object { const val CHANNEL = "enhance" }
}
