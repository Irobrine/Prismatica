package com.prism.music.ui.tv

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.prism.music.AppContainer
import com.prism.music.R
import com.prism.music.data.BackupPart
import com.prism.music.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Backups on a TV (Settings → Backup). A TV often has no file picker (Android's own is left off many TVs), so a TV
 * backup goes straight to its Download/Prism folder; restoring lists the backups found there, with "Choose a file" for
 * the picker where there is one. A backup with the sign-in is sealed with a password typed on the TV's keyboard
 * ([TvPasswordPanel]), as the phone's always is. The backup itself is the phone's ([com.prism.music.data.Backup]): a
 * TV's restores on a phone too, and the other way round.
 */
object TvBackup {
    /** The backups a TV offers: everything but the sign-in, then with the downloaded audio, the sign-in, or both. */
    val PLAIN: Set<BackupPart> = BackupPart.entries.toSet() - setOf(BackupPart.ACCOUNTS, BackupPart.DOWNLOADS)
    val WITH_DOWNLOADS: Set<BackupPart> = PLAIN + BackupPart.DOWNLOADS
    val WITH_SIGN_IN: Set<BackupPart> = PLAIN + BackupPart.ACCOUNTS
    val EVERYTHING: Set<BackupPart> = BackupPart.entries.toSet()

    /** Whether a backup of [parts] needs a password (the sign-in is never left unsealed). */
    fun needsPassword(parts: Set<BackupPart>): Boolean = BackupPart.ACCOUNTS in parts

    private const val FOLDER = "Prism"

    /** One backup found on this TV: its name (as it's listed) and where it is. */
    data class Found(val name: String, val uri: Uri, val modified: Long)

    /** The file name, as the phone's backups are named ("Prism backup 2026-11-27.zip"). */
    fun fileName(): String =
        tr(R.string.prism_backup) + java.text.SimpleDateFormat(tr(R.string.yyyy_mm_dd), java.util.Locale.US).format(java.util.Date()) + ".zip"

    /** Writes a backup of [parts] ([password] seals it) to the TV's Download/Prism; where it went. Throws when it couldn't. */
    suspend fun backUp(c: AppContainer, parts: Set<BackupPart>, password: CharArray? = null): String = withContext(Dispatchers.IO) {
        val name = fileName()
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = c.app.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error(tr(R.string.couldn_t_open_that_file))
            try {
                c.backup.export(uri, parts, password)
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (t: Throwable) {
                // A half-written file is no backup: it goes, so it's never offered for a restore.
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
            Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/" + name
        } else {
            val dir = File(c.app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: c.app.filesDir, FOLDER).apply { mkdirs() }
            val file = File(dir, name)
            try { c.backup.export(Uri.fromFile(file), parts, password) } catch (t: Throwable) { file.delete(); throw t }
            file.absolutePath
        }
    }

    /** The backups this TV can see in its Downloads/Prism (the ones it made, or any it may read), newest first. */
    suspend fun found(c: AppContainer): List<Found> = withContext(Dispatchers.IO) {
        val out = ArrayList<Found>()
        if (Build.VERSION.SDK_INT >= 29) {
            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_MODIFIED)
            runCatching {
                c.app.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection,
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                    arrayOf(Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "%", "%.zip"), null,
                )?.use { cur ->
                    while (cur.moveToNext()) {
                        val id = cur.getLong(0)
                        out += Found(cur.getString(1), android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), cur.getLong(2) * 1000)
                    }
                }
            }
        }
        c.app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, FOLDER) }?.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".zip") }
            ?.forEach { out += Found(it.name, Uri.fromFile(it), it.lastModified()) }
        out.sortedByDescending { it.modified }
    }
}
