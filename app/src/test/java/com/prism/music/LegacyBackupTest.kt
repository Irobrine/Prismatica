package com.prism.music

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import com.prism.music.data.Backup
import com.prism.music.data.BackupPart
import com.prism.music.data.db.PrismDatabase
import com.prism.music.data.innertube.InnerTube
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A backup made by 1.8.5-archaic (the Prismatica branch this line was refactored from) restores into 1.1.1 and keeps
 * what it holds. The file is built as that version exported it: the same manifest (format 2, no integrity list), the
 * old library file, its settings as tagged values, its preferences, and a database at schema 11 (the version that
 * branch exported). The historical build is no longer runnable here, so this is a fixture built from its schemas and
 * export code rather than a listener's own file (documented in docs/release-1.1.1.md).
 *
 * It also covers what 1.7.8-1.8.6 backups carried that this version keeps off a phone: the settings store's own file
 * (which holds the sign-in), crash and diagnostic logs, and the migration marks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LegacyBackupTest {
    private val app = RuntimeEnvironment.getApplication()
    private val files = app.filesDir
    private val data = files.parentFile!!
    private val schemaDir = listOf("schemas", "app/schemas").map { File(it, "com.prism.music.data.db.PrismDatabase") }.first { it.isDirectory }

    private fun write(path: String, text: String) = File(data, path).apply { parentFile?.mkdirs(); writeText(text) }
    private fun read(path: String) = File(data, path).takeIf { it.exists() }?.readText()

    /** What this phone already holds: a library, history, its own settings store and its own logs. */
    private fun phone() {
        File(data, "databases").mkdirs()
        File(data, "shared_prefs").mkdirs()
        write("shared_prefs/icon_style.xml", "old settings")
        write("shared_prefs/library_order.xml", "old songs-from")
        write("shared_prefs/lastfm.xml", "this phone's Last.fm session")
        write("shared_prefs/crashlog.xml", "this phone's last crash")
        write("files/library/prism.json", "old library")
        write("files/datastore/prism_settings.preferences_pb", "this phone's store and sign-in")
        write("files/diagnostics/audio.log", "this phone's diagnostics")
        write("files/crashes/last.txt", "this phone's crash")
        write("files/migrations/marks", "this install's one-time moves")
        write("files/translations/old.json", "this phone's cache")
        createDbAt(File(data, "databases/prism.db"), 11, songId = "phone-song", songTitle = "This Phone's Song")
    }

    /** A container to stage a backup with; it must let go of the database before [Backup.applyPending] moves it. */
    private fun container(): AppContainer = AppContainer(app)
    private fun close(c: AppContainer) = lazyOf(c).closeAfterTest()

    // ------------------------------------------------------------ the 1.8.5-archaic file

    private fun legacyBackup(parts: List<String>, settings: Map<String, String> = mapOf("theme_mode" to "s:DARK", "pure_black" to "b:true")): File {
        val db = File(app.cacheDir, "legacy-185.db").apply { parentFile?.mkdirs(); delete() }
        createDbAt(db, 11, songId = "legacy-song", songTitle = "Legacy Song")
        val file = File(app.cacheDir, "Prism Backups/Prism Backup 2026-11-01.zip").apply { parentFile?.mkdirs(); delete() }
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            fun put(name: String, text: String) = put(name, text.toByteArray())
            // 1.8.5-archaic's manifest: format 2, the parts it holds, and no integrity list (that began in 1.1.0).
            put(
                "prism-backup.json",
                """{"format":2,"app":"${app.packageName}","versionCode":63,"versionName":"1.8.5","createdAt":1762000000000,""" +
                    """"withDownloads":false,"downloads":[],"parts":[${parts.joinToString(",") { "\"$it\"" }}]}""",
            )
            if (BackupPart.SETTINGS.name in parts || BackupPart.ACCOUNTS.name in parts) {
                put("settings.json", InnerTube.json.encodeToString(MapSerializer(String.serializer(), String.serializer()), settings))
            }
            if (BackupPart.LIBRARY.name in parts) {
                put(
                    "files/library/prism.json",
                    """{"lists":[{"id":"prism-road","title":"Road trip","songs":[{"id":"legacy-song","title":"Legacy Song",""" +
                        """"artists":[{"name":"Old Artist","id":"UCold"}],"durationSec":200}],"createdAt":1,"updatedAt":2}],""" +
                        """"albums":[],"playlists":[],"folders":[]}""",
                )
                put("files/library/tags.json", """{"tags":[{"tag":"gym","songs":[{"song":{"id":"legacy-song","title":"Legacy Song"},"at":1}]}]}""")
                put("files/library/prism-road.jpg", "a cover")
                put("files/covers/legacy-song.jpg", "a picked cover")
            }
            if (BackupPart.HISTORY.name in parts) {
                put("databases/prism.db", db.readBytes())
                // 1.8.5's playback queue, as that branch wrote it.
                put("files/queue.json", """{"songs":[],"index":0,"positionMs":0,"title":"","shuffle":false,"repeat":0}""")
            }
            if (BackupPart.SETTINGS.name in parts) {
                writePrefs(zip, "icon_style", "new settings")
                // The settings store's own file went with Settings from 1.7.8 to 1.8.6: it also holds the sign-in.
                put("files/datastore/prism_settings.preferences_pb", "the backup maker's store and sign-in")
                put("files/diagnostics/audio.log", "the backup maker's diagnostics")
                put("files/crashes/last.txt", "the backup maker's crash")
                put("files/migrations/marks", "the backup maker's one-time moves")
                put("files/translations/legacy.json", "the backup maker's cache")
            }
            if (BackupPart.LIBRARY.name in parts || BackupPart.HISTORY.name in parts) {
                writePrefs(zip, "library_order", "new songs-from")
            }
            if (BackupPart.ACCOUNTS.name in parts) {
                // 1.8.5 put Last.fm's session with Accounts; this version reconnects instead (the key is sealed here).
                writePrefs(zip, "lastfm", "the backup maker's Last.fm session")
            }
            writePrefs(zip, "crashlog", "the backup maker's last crash")
        }
        db.delete()
        return file
    }

    private fun writePrefs(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry("shared_prefs/$name.xml"))
        zip.write(("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n  <string name=\"value\">$text</string>\n</map>").toByteArray())
        zip.closeEntry()
    }

    /** A database exactly as schema [version] left it, with a song, a like, and two plays. */
    private fun createDbAt(file: File, version: Int, songId: String, songTitle: String) {
        val schema = Json.parseToJsonElement(File(schemaDir, "$version.json").readText()).jsonObject["database"]!!.jsonObject
        file.parentFile?.mkdirs()
        file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        for (entity in schema["entities"]!!.jsonArray) {
            val e = entity.jsonObject
            val table = e["tableName"]!!.jsonPrimitive.content
            db.execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            e["indices"]?.jsonArray?.forEach { db.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
        }
        schema["views"]?.jsonArray?.forEach { v ->
            val view = v.jsonObject
            db.execSQL(view["createSql"]!!.jsonPrimitive.content.replace("\${VIEW_NAME}", view["viewName"]!!.jsonPrimitive.content))
        }
        schema["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
        db.version = schema["version"]!!.jsonPrimitive.int
        db.insert("songs", null, ContentValues().apply {
            put("id", songId); put("title", songTitle); put("artistName", "Old Artist"); put("artistId", "UCold")
            put("durationSec", 200); put("isVideo", 0); put("liked", 1); put("likedAt", 5L); put("lastPlayed", 10L); put("smartDownload", 0)
            put("primaryArtist", "Old Artist"); put("artistsJson", """[{"name":"Old Artist","id":"UCold"}]""")
        })
        for (t in listOf(1_000L, 2_000L)) db.insert("play_events", null, ContentValues().apply {
            put("songId", songId); put("timestamp", t); put("playedMs", 100_000L)
        })
        db.close()
    }

    // ------------------------------------------------------------ tests

    @Test fun a185ArchaicBackupRestoresItsLibraryHistorySettingsAndPreferences() = runBlocking {
        phone()
        val file = legacyBackup(listOf("SETTINGS", "LIBRARY", "HISTORY", "ACCOUNTS"))
        val c = container()
        assertEquals("every part it names", setOf(BackupPart.SETTINGS, BackupPart.LIBRARY, BackupPart.HISTORY, BackupPart.ACCOUNTS), c.backup.partsOf(Uri.fromFile(file)))
        c.backup.stage(Uri.fromFile(file))
        close(c)

        // Staged, but not yet put in place: the settings store, logs and caches of the backup maker aren't among it.
        val staging = File(files, "restore_pending")
        assertFalse("the settings store's own file isn't staged", File(staging, "files/datastore/prism_settings.preferences_pb").exists())
        assertFalse("nor the backup maker's logs", File(staging, "files/diagnostics/audio.log").exists())
        assertFalse("nor the backup maker's Last.fm session", File(staging, "shared_prefs/lastfm.xml").exists())
        assertFalse("nor the backup maker's crash log", File(staging, "shared_prefs/crashlog.xml").exists())
        assertTrue("the library is", File(staging, "files/library/prism.json").isFile)
        assertTrue("the history is", File(staging, "databases/prism.db").isFile)

        Backup.applyPending(app)
        assertFalse("the restore is over", staging.exists())

        // The library and its tags, the preferences and the settings came back.
        assertTrue(read("files/library/prism.json")!!.contains("prism-road"))
        assertTrue(read("files/library/tags.json")!!.contains("gym"))
        assertEquals("a picked cover", read("files/covers/legacy-song.jpg"))
        assertTrue("new settings", read("shared_prefs/icon_style.xml")!!.contains("new settings"))
        assertTrue("new songs-from", read("shared_prefs/library_order.xml")!!.contains("new songs-from"))
        assertTrue(read("files/restore_settings.json")!!.contains("DARK"))
        // A restore of everything still doesn't bring the backup maker's sign-in as the store file.
        assertEquals("this phone's store and sign-in", read("files/datastore/prism_settings.preferences_pb"))
        assertEquals("this phone's diagnostics", read("files/diagnostics/audio.log"))
        assertEquals("this phone's crash", read("files/crashes/last.txt"))
        assertEquals("this install's one-time moves", read("files/migrations/marks"))
        assertEquals("this phone's cache", read("files/translations/old.json"))
        assertTrue("this phone's Last.fm session", read("shared_prefs/lastfm.xml")!!.contains("this phone's Last.fm session"))
        assertTrue("this phone's last crash", read("shared_prefs/crashlog.xml")!!.contains("this phone's last crash"))

        // The database is the backup's, opened with this version's migrations, and its history survived 11 → 12 → 13.
        val db = Room.databaseBuilder(app, PrismDatabase::class.java, "prism.db")
            .addMigrations(*PrismDatabase.MIGRATIONS).allowMainThreadQueries().build()
        assertEquals(13, db.openHelper.readableDatabase.version)
        val song = db.songs().get("legacy-song")!!
        assertEquals("Legacy Song", song.title)
        assertTrue("the like kept", song.liked)
        assertEquals("the plays kept", 2, db.plays().count(0, Long.MAX_VALUE))
        assertEquals(200_000L, db.plays().totalMs(0, Long.MAX_VALUE))
        assertEquals("the phone's own song is gone with its database", null, db.songs().get("phone-song"))
        db.close()
    }

    /** Library alone replaces the library and leaves the history, settings and this phone's store alone. */
    @Test fun a185LibraryOnlyBackupLeavesEverythingElse() = runBlocking {
        phone()
        val file = legacyBackup(listOf("LIBRARY"))
        container().let { c -> c.backup.stage(Uri.fromFile(file)); close(c) }
        Backup.applyPending(app)
        assertTrue(read("files/library/prism.json")!!.contains("prism-road"))
        assertTrue("old settings", read("shared_prefs/icon_style.xml")!!.contains("old settings"))
        assertTrue("the library's own preferences", read("shared_prefs/library_order.xml")!!.contains("new songs-from"))
        val db = Room.databaseBuilder(app, PrismDatabase::class.java, "prism.db")
            .addMigrations(*PrismDatabase.MIGRATIONS).allowMainThreadQueries().build()
        assertEquals("This Phone's Song", db.songs().get("phone-song")!!.title)
        db.close()
    }

    /** Settings alone brings its values and preferences, never the backup maker's store (or sign-in). */
    @Test fun a185SettingsOnlyBackupKeepsThisPhonesSignIn() = runBlocking {
        phone()
        val file = legacyBackup(listOf("SETTINGS"), mapOf("theme_mode" to "s:DARK", "cookie" to "s:SID=theirs"))
        container().let { c -> c.backup.stage(Uri.fromFile(file)); close(c) }
        Backup.applyPending(app)
        assertTrue("new settings", read("shared_prefs/icon_style.xml")!!.contains("new settings"))
        assertEquals("this phone's store and sign-in", read("files/datastore/prism_settings.preferences_pb"))
        assertEquals("old library", read("files/library/prism.json"))
        assertTrue(read("files/restore_settings.json")!!.contains("DARK"))
    }

    /**
     * A backup from before 1.7.8 (format 1, no parts): it replaces everything but the downloads. Files this version
     * never carries (its logs, Last.fm's sealed session, the crash log) are skipped instead of refusing the restore,
     * and the settings store it brought (the only form settings had then) is put in place.
     */
    @Test fun anOlderWholeBackupRestoresAndSkipsWhatNeverTravels() = runBlocking {
        phone()
        File(data, "datastore").mkdirs()
        write("datastore/prism_settings.preferences_pb", "this phone's root store")
        val db = File(app.cacheDir, "whole-16.db").apply { parentFile?.mkdirs(); delete() }
        createDbAt(db, 10, songId = "whole-song", songTitle = "Whole Song")
        val file = File(app.cacheDir, "whole.zip").apply { parentFile?.mkdirs(); delete() }
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            fun put(name: String, text: String) = put(name, text.toByteArray())
            put("prism-backup.json", """{"format":1,"app":"${app.packageName}","versionName":"1.6.9","withDownloads":false}""")
            put("shared_prefs/icon_style.xml", "<map><string name=\"value\">new settings</string></map>")
            put("shared_prefs/library_order.xml", "<map><string name=\"value\">new songs-from</string></map>")
            put("shared_prefs/lastfm.xml", "<map><string name=\"value\">the backup maker's Last.fm session</string></map>")
            put("shared_prefs/crashlog.xml", "<map><string name=\"value\">the backup maker's crash</string></map>")
            put("datastore/prism_settings.preferences_pb", "the backup maker's store")
            put("files/datastore/prism_settings.preferences_pb", "the backup maker's store, under files")
            put("files/library/prism.json", """{"lists":[],"albums":[],"playlists":[],"folders":[]}""")
            put("files/diagnostics/audio.log", "the backup maker's diagnostics")
            put("files/crashes/last.txt", "the backup maker's crash")
            put("databases/prism.db", db.readBytes())
        }
        db.delete()

        container().let { c -> c.backup.stage(Uri.fromFile(file)); close(c) }
        Backup.applyPending(app)
        assertTrue("the library", read("files/library/prism.json")!!.contains("lists"))
        assertTrue("new settings", read("shared_prefs/icon_style.xml")!!.contains("new settings"))
        assertTrue("new songs-from", read("shared_prefs/library_order.xml")!!.contains("new songs-from"))
        assertEquals("the whole backup's store", "the backup maker's store", read("datastore/prism_settings.preferences_pb"))
        assertEquals("and the one under files/", "the backup maker's store, under files", read("files/datastore/prism_settings.preferences_pb"))
        assertEquals("this phone's diagnostics", read("files/diagnostics/audio.log"))
        assertEquals("this phone's crash", read("files/crashes/last.txt"))
        assertTrue("this phone's Last.fm session", read("shared_prefs/lastfm.xml")!!.contains("this phone's Last.fm session"))
        assertTrue("this phone's last crash", read("shared_prefs/crashlog.xml")!!.contains("this phone's last crash"))
        val room = Room.databaseBuilder(app, PrismDatabase::class.java, "prism.db")
            .addMigrations(*PrismDatabase.MIGRATIONS).allowMainThreadQueries().build()
        assertEquals(13, room.openHelper.readableDatabase.version)
        assertEquals("Whole Song", room.songs().get("whole-song")!!.title)
        room.close()
    }

    /**
     * A staging left by a version before this one (or an update between the restore and the next launch): the settings
     * store, Last.fm's sealed session and the crash log in it are skipped, and the rest still goes in. Before 1.1.1 one
     * of those entries made the whole restore be dropped.
     */
    @Test fun aStagedRestoreWithOlderNeverTravelFilesStillApplies() {
        phone()
        val staging = File(files, "restore_pending").apply { mkdirs() }
        File(staging, "shared_prefs").mkdirs()
        File(staging, "files/datastore").mkdirs()
        File(staging, "prism-backup.json").writeText("""{"format":2,"app":"${app.packageName}","parts":["SETTINGS"]}""")
        File(staging, "settings.json").writeText("""{"theme_mode":"s:DARK"}""")
        File(staging, "shared_prefs/icon_style.xml").writeText("<map><string name=\"value\">new settings</string></map>")
        File(staging, "shared_prefs/lastfm.xml").writeText("<map><string name=\"value\">theirs</string></map>")
        File(staging, "shared_prefs/crashlog.xml").writeText("<map><string name=\"value\">theirs</string></map>")
        File(staging, "files/datastore/prism_settings.preferences_pb").writeText("theirs, signed in")
        Backup.applyPending(app)
        assertTrue("the rest went in", read("shared_prefs/icon_style.xml")!!.contains("new settings"))
        assertEquals("this phone's store and sign-in", read("files/datastore/prism_settings.preferences_pb"))
        assertTrue("this phone's Last.fm session", read("shared_prefs/lastfm.xml")!!.contains("this phone's Last.fm session"))
        assertTrue("this phone's last crash", read("shared_prefs/crashlog.xml")!!.contains("this phone's last crash"))
        assertFalse("the restore is over", staging.exists())
    }

    /** A cut-short or foreign file is refused whole, and this phone keeps everything it had. */
    @Test fun aMalformedOrIncompleteLegacyBackupChangesNothing() = runBlocking {
        phone()
        val good = legacyBackup(listOf("SETTINGS", "LIBRARY", "HISTORY"))
        val cut = File(app.cacheDir, "cut.zip").apply { writeBytes(good.readBytes().copyOf(good.length().toInt() / 2)) }
        val notOne = File(app.cacheDir, "not-a-backup.zip").apply { writeText("hello") }
        val c = container()
        for (file in listOf(cut, notOne)) {
            val failed = runCatching { c.backup.stage(Uri.fromFile(file)) }
            assertTrue("$file: refused", failed.isFailure)
            assertFalse("$file: nothing left staged", File(files, "restore_pending").exists())
            assertEquals("old library", read("files/library/prism.json"))
            assertTrue("old settings", read("shared_prefs/icon_style.xml")!!.contains("old settings"))
            assertEquals("this phone's store and sign-in", read("files/datastore/prism_settings.preferences_pb"))
        }
        close(c)
    }

    /** A backup of this version still restores: settings as values, the library, and the database's integrity list. */
    @Test fun aCurrentVersionBackupStillRestores() = runBlocking {
        phone()
        val file = File(app.cacheDir, "current.zip")
        val c = container()
        c.backup.export(Uri.fromFile(file), setOf(BackupPart.SETTINGS, BackupPart.LIBRARY, BackupPart.HISTORY))
        close(c)
        // The phone moves on: a different library and settings, and its own history.
        write("files/library/prism.json", "changed library")
        write("shared_prefs/icon_style.xml", "changed settings")
        container().let { c2 -> c2.backup.stage(Uri.fromFile(file)); close(c2) }
        Backup.applyPending(app)
        assertTrue("the exported library came back", read("files/library/prism.json")!!.contains("old library"))
        assertTrue("the exported settings came back", read("shared_prefs/icon_style.xml")!!.contains("old settings"))
    }

    @org.junit.After fun after() {
        File(files, "restore_pending").deleteRecursively()
        File(files, "restore_settings.json").delete()
        File(data, "restore_old").deleteRecursively()
        File(data, "restore_journal.json").delete()
        File(data, "restore_applied").delete()
        app.deleteDatabase("prism.db")
    }
}
