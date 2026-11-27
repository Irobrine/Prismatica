package com.prism.music

import android.content.pm.ActivityInfo
import com.prism.music.ui.tv.TvDevice
import com.prism.music.ui.tv.TvModeChange
import com.prism.music.ui.tv.tvModeChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Force TV mode (Settings → Speakers, TVs & browser → Prism on a TV): a phone made a TV by a switch. What every screen
 * asks is [TvDevice.isTv] (the phone's sends itself to TvActivity, Prism Connect offers it as a TV); [TvDevice.isRealTv]
 * stays the system's own answer, and the landscape ask is the window's, not the device's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TvForceModeTest {
    private val context = RuntimeEnvironment.getApplication() as android.content.Context

    @Test
    fun forcedMakesAnyDeviceATv() {
        TvDevice.forced = false
        assertFalse("a plain device isn't one", TvDevice.isTv(context))
        assertFalse(TvDevice.isRealTv(context))
        try {
            TvDevice.forced = true
            assertTrue("forced: a TV to every screen", TvDevice.isTv(context))
            assertFalse("the system's own answer doesn't change", TvDevice.isRealTv(context))
        } finally {
            TvDevice.forced = false
        }
    }

    @Test
    fun onlyAChangedModeIsRegisteredAgain() {
        assertEquals("the first emission only seeds", TvModeChange.SEED, tvModeChange(null, false))
        assertEquals(TvModeChange.SEED, tvModeChange(null, true))
        assertEquals(TvModeChange.SAME, tvModeChange(false, false))
        assertEquals(TvModeChange.SAME, tvModeChange(true, true))
        assertEquals("the record says the other thing", TvModeChange.CHANGED, tvModeChange(false, true))
        assertEquals(TvModeChange.CHANGED, tvModeChange(true, false))
    }

    @Test
    fun landscapeOnlyWhileForced() {
        assertNull("a TV keeps its own orientation", TvDevice.orientationFor(forced = false))
        assertEquals(
            "a forced phone asks for landscape (either way it's held)",
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            TvDevice.orientationFor(forced = true),
        )
    }
}
