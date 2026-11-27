package com.prism.music

import com.prism.music.data.BackupPart
import com.prism.music.ui.tv.TvBackup
import com.prism.music.ui.tv.TvPasswordStep
import com.prism.music.ui.tv.tvPasswordStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TV Settings → Backup: a TV can't type a password with ease, and a backup with the sign-in must have one, so neither
 * of the TV's backups carries the sign-in; the second adds the downloaded audio. Its file is named as a phone's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TvBackupTest {
    @Test
    fun onlyABackupWithTheSignInIsSealed() {
        assertEquals(setOf(BackupPart.SETTINGS, BackupPart.LIBRARY, BackupPart.HISTORY), TvBackup.PLAIN)
        assertEquals(TvBackup.PLAIN + BackupPart.DOWNLOADS, TvBackup.WITH_DOWNLOADS)
        assertEquals(TvBackup.PLAIN + BackupPart.ACCOUNTS, TvBackup.WITH_SIGN_IN)
        assertEquals(BackupPart.entries.toSet(), TvBackup.EVERYTHING)
        assertFalse(TvBackup.needsPassword(TvBackup.PLAIN))
        assertFalse(TvBackup.needsPassword(TvBackup.WITH_DOWNLOADS))
        assertTrue(TvBackup.needsPassword(TvBackup.WITH_SIGN_IN))
        assertTrue(TvBackup.needsPassword(TvBackup.EVERYTHING))
    }

    /** A new password is typed twice (as on the phone); opening a backup asks once; nothing empty is taken. */
    @Test
    fun thePasswordIsTypedTwiceForANewBackup() {
        assertEquals(TvPasswordStep.EMPTY, tvPasswordStep(confirm = true, first = "", again = null))
        assertEquals(TvPasswordStep.AGAIN, tvPasswordStep(confirm = true, first = "secret", again = null))
        assertEquals(TvPasswordStep.MISMATCH, tvPasswordStep(confirm = true, first = "secret", again = "secrte"))
        assertEquals(TvPasswordStep.DONE, tvPasswordStep(confirm = true, first = "secret", again = "secret"))
        assertEquals(TvPasswordStep.DONE, tvPasswordStep(confirm = false, first = "secret", again = null))
        assertEquals(TvPasswordStep.EMPTY, tvPasswordStep(confirm = false, first = "", again = null))
    }

    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createComposeRule()

    /** While a backup or restore runs, the TV says so with a spinner, as the phone's working dialog, and keeps focus. */
    @Test
    fun aBackupUnderWayIsShown() {
        compose.setContent {
            com.prism.music.ui.tv.TvTheme(com.prism.music.data.prefs.AppSettings()) {
                com.prism.music.ui.tv.TvWorking(tr(R.string.backing_up))
            }
        }
        compose.waitForIdle()
        compose.onNode(androidx.compose.ui.test.hasText(tr(R.string.backing_up))).assertExists()
        assertTrue(
            "it holds focus",
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag(com.prism.music.ui.tv.TAG_WORKING) and androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun itsFileIsNamedAsAPhonesBackup() {
        val name = TvBackup.fileName()
        assertTrue(name, name.startsWith(tr(R.string.prism_backup)) && name.endsWith(".zip"))
    }
}
