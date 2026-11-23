package com.prism.music

import android.net.Uri
import com.prism.music.data.Notifications
import com.prism.music.ui.Routes
import com.prism.music.ui.screens.settings.SettingsPageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A progress notification's tap lands on the option that started the work ([Notifications.settingsLink]). The group's
 * title travels in the link — it contains spaces and "&" in most languages — and the page route carries it on, so the
 * Settings page opens at the group with the title as the listener reads it.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsLinkTest {
    @Test
    fun aGroupTitleSurvivesTheNotificationLinkAndTheRoute() {
        val link = Notifications.settingsLink(SettingsPageId.SOUND_QUALITY.key, SOUND_AND_QUALITY)
        val uri = Uri.parse(link)
        assertEquals("prism", uri.scheme)
        assertEquals("settings", uri.host)
        assertEquals(SettingsPageId.SOUND_QUALITY.key, uri.pathSegments.single())
        assertEquals(SOUND_AND_QUALITY, uri.getQueryParameter("find"))

        val route = Routes.settings(uri.pathSegments.single(), uri.getQueryParameter("find"))
        assertEquals("settings/sound_quality?find=Sound%20%26%20quality", route)
        val again = Uri.parse(route)
        assertEquals("the page, as Navigation reads it", SettingsPageId.SOUND_QUALITY.key, again.pathSegments.last())
        assertEquals("the group, as Navigation reads it", SOUND_AND_QUALITY, again.getQueryParameter("find"))
    }

    @Test
    fun aSectionStaysOneSegment() {
        // A link — or an explicit intent from another app, Prism's screen being exported — can put anything in the path.
        // The page route keeps it one segment, so it can't turn into another destination.
        val route = Routes.settings("../album/x#y")
        val page = route.substringAfter("settings/")
        assertFalse("no raw separator: $route", page.contains('/'))
        assertFalse("no raw fragment: $route", page.contains('#'))
    }

    private companion object {
        const val SOUND_AND_QUALITY = "Sound & quality"
    }
}
