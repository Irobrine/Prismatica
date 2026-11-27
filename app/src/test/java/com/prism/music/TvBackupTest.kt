package com.prism.music

import com.prism.music.data.BackupPart
import com.prism.music.ui.tv.TvBackup
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
    fun aTvBackupNeverCarriesTheSignIn() {
        assertFalse(BackupPart.ACCOUNTS in TvBackup.PLAIN)
        assertFalse(BackupPart.ACCOUNTS in TvBackup.WITH_DOWNLOADS)
        assertEquals(setOf(BackupPart.SETTINGS, BackupPart.LIBRARY, BackupPart.HISTORY), TvBackup.PLAIN)
        assertEquals(TvBackup.PLAIN + BackupPart.DOWNLOADS, TvBackup.WITH_DOWNLOADS)
    }

    @Test
    fun itsFileIsNamedAsAPhonesBackup() {
        val name = TvBackup.fileName()
        assertTrue(name, name.startsWith(tr(R.string.prism_backup)) && name.endsWith(".zip"))
    }
}
