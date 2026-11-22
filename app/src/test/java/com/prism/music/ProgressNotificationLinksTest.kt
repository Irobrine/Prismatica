package com.prism.music

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The progress notifications are statuses, but a tap on one is a way back: to the menu the work was started from, not
 * just Prism's front page. Each of the four walks that show progress has its own place to land.
 */
class ProgressNotificationLinksTest {
    private val main = File("src/main/java/com/prism/music")

    private fun source(path: String) = File(main, path).readText()

    @Test fun measuringSongsOpensItsSettingsOption() {
        val s = source("playback/MeasureService.kt")
        assertTrue("the walk's tap must use a deep link", s.contains("Notifications.openIntent(this, measureLink(), 0)"))
        assertTrue(
            "the option lives in Sound & quality's Sound group, as the player's Equalizer button opens it",
            s.contains("Notifications.settingsLink(SettingsPageId.SOUND_QUALITY.key, tr(R.string.sound))"),
        )
    }

    @Test fun theDuplicateScanOpensTheDuplicateSongsSheet() {
        val s = source("playback/DedupScanService.kt")
        assertTrue("the scan was started from the Duplicate songs sheet", s.contains("Notifications.openIntent(this, Notifications.OPEN_DEDUP, 0)"))
    }

    @Test fun aBatchOpensTheLyricsEnhancementSheet() {
        val s = source("data/enhance/EnhanceService.kt")
        assertTrue("the batch was started from the Lyrics enhancement sheet", s.contains("Notifications.openIntent(this, Notifications.OPEN_ENHANCE, 0)"))
    }

    @Test fun downloadingOpensTheDownloadsMenu() {
        val s = source("download/Downloads.kt")
        assertTrue("downloads have their own menu", s.contains("Notifications.openIntent(this, Notifications.OPEN_DOWNLOADS, 1)"))
    }

    @Test fun eachLinkHasAMenuBehindIt() {
        val activity = source("MainActivity.kt")
        for (branch in listOf(
            "uri.host == \"dedup\" -> nav.go(Routes.DEDUP)",
            "uri.host == \"enhance\" -> nav.go(Routes.ENHANCE)",
            "uri.host == \"settings\" && path.isNotEmpty() ->",
            "nav.go(Routes.settings(path[0], uri.getQueryParameter(\"find\")))",
        )) assertTrue("$branch: no deep link for it", activity.contains(branch))
        for (destination in listOf("composable(Routes.DEDUP)", "composable(Routes.ENHANCE)")) {
            assertTrue("$destination: the sheet has no destination", activity.contains(destination))
        }
    }

    @Test fun theLinksAreNamedInOnePlace() {
        val notifications = source("data/Notifications.kt")
        for (link in listOf("prism://dedup", "prism://enhance", "prism://downloads", "prism://settings/")) {
            assertTrue("$link: no such link", notifications.contains(link))
        }
    }
}
