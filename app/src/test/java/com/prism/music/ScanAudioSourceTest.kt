package com.prism.music

import android.app.Application
import android.net.Uri
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 1.8.5: what "Measure now" and Sing read a song from. A lossless file (or one of the listener's own) shows in Downloads
 * as the song but lives outside the player's and the download cache; it is read from that file, with no stream fetched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScanAudioSourceTest {
    private val made = lazy { AppContainer(RuntimeEnvironment.getApplication()) }
    private val c by made

    @After fun close() = made.closeAfterTest()

    @Test fun aLosslessDownloadIsReadFromItsFile() {
        val file = Uri.parse("content://media/external/audio/media/42")
        c.localLossless.linked = mapOf("abc" to file)
        assertEquals(file, c.audioOnPhone("abc", File(c.app.cacheDir, "scan.part")))
    }
}
