package com.prism.music.data

import com.prism.music.R
import com.prism.music.tr
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.prism.music.AppContainer
import com.prism.music.BuildConfig
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.db.VideoSongEntity
import com.prism.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** The parts a backup can hold (1.7.8, #149: settings can be shared without your listening or your sign-in). */
enum class BackupPart(val label: String, val detail: String) {
    SETTINGS(tr(R.string.settings_looks), tr(R.string.everything_you_ve_set_your_looks)),
    LIBRARY(tr(R.string.library), tr(R.string.prism_playlists_folders_and_pins_starred)),
    HISTORY(tr(R.string.listening_history_replay), tr(R.string.what_you_ve_played_likes_replay)),
    ACCOUNTS(tr(R.string.accounts), tr(R.string.your_youtube_music_sign_in_and)),
    DOWNLOADS(tr(R.string.downloaded_songs), tr(R.string.the_audio_itself_otherwise_they_re)),
}

/** What's written first in every backup. */
@Serializable
private data class BackupManifest(
    val format: Int = 1,
    val app: String = "",
    val versionCode: Int = 0,
    val versionName: String = "",
    val createdAt: Long = 0,
    val withDownloads: Boolean = false,
    /** Downloaded songs, so they can be fetched again when the audio itself isn't in the backup. */
    val downloads: List<Song> = emptyList(),
    /** The parts it holds ([BackupPart] names); null in backups made before 1.7.8, which hold everything. */
    val parts: List<String>? = null,
    /** "Bring your Prism" (1.8.0): a phone's listening for a TV, merged in there, never restored as a backup. */
    val bring: Boolean = false,
    /**
     * 1 when [INTEGRITY] lists a SHA-256 for every entry (and the manifest). 0 in backups made before it, which
     * restore without it. Only tells corruption and a cut-short file apart from a whole one: a backup is not signed,
     * so it can't prove where it came from.
     */
    val integrity: Int = 0,
)

/** The trailing entry that says every other entry is whole: [entries] by name, and the manifest's own hash. */
@Serializable
private data class BackupIntegrity(
    val version: Int = 1,
    val entries: Map<String, String> = emptyMap(),
    val manifest: String = "",
)

/** What an apply is moving, written before the first move so an interrupted one can be put back. */
@Serializable
private data class RestoreJournal(
    /** Paths (under the app's data folder) the backup replaces, each kept in [RESTORE_OLD] until it's all in. */
    val old: List<String> = emptyList(),
    /** Paths the backup puts there; a failed apply takes these out again. */
    val new: List<String> = emptyList(),
)

/**
 * Backs Prism up to one file the listener keeps (a zip: the library and Replay database, settings
 * and sign-in, saved preferences, custom covers and backgrounds; optionally the downloaded audio),
 * and restores one. A restore is staged and put in place on the next launch, before anything has
 * opened those files, then Prism restarts. Nothing leaves the phone except to where the listener saves it.
 * Since 1.7.8 the listener picks the [BackupPart]s; a restore replaces only those and leaves the rest.
 */
class Backup(private val c: AppContainer) {
    private val ctx: Context get() = c.app
    private val data: File get() = ctx.filesDir.parentFile!!

    /**
     * A backup any Prism made, whatever its app id: the test copy's, a copy renamed to show in a phone's own media
     * island (#204), the real one. Its manifest says it's Prism's, and a restore only ever takes Prism's own folders.
     */
    private fun fromPrism(m: BackupManifest) = m.app.isNotBlank()

    /** Writes a backup of [parts] to [uri]; [password] seals the whole file, names included. */
    suspend fun export(uri: Uri, parts: Set<BackupPart>, password: CharArray? = null) = withContext(Dispatchers.IO) {
        // A backup that carries the sign-in is never left in the clear, whatever the caller passed.
        if (BackupPart.ACCOUNTS in parts && (password == null || password.isEmpty())) error(tr(R.string.backup_accounts_need_password))
        // Fold Room's write-ahead log into the main file so the copy is complete.
        runCatching { c.db.query(androidx.sqlite.db.SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)")).close() }
        val withDownloads = BackupPart.DOWNLOADS in parts
        val manifest = BackupManifest(
            format = FORMAT, app = ctx.packageName, versionCode = BuildConfig.VERSION_CODE, versionName = BuildConfig.VERSION_NAME,
            createdAt = System.currentTimeMillis(), withDownloads = withDownloads,
            downloads = if (BackupPart.LIBRARY in parts || withDownloads) c.downloads.completedSongs() else emptyList(),
            parts = parts.map { it.name },
            integrity = 1,
        )
        val settings = c.settings.forBackup(settings = BackupPart.SETTINGS in parts, accounts = BackupPart.ACCOUNTS in parts)
        val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: error(tr(R.string.couldn_t_open_that_file))
        val manifestBytes = InnerTube.json.encodeToString(BackupManifest.serializer(), manifest).toByteArray()
        // Every entry written after the manifest, so a restore can tell a whole file from a cut-short or altered one.
        val sums = LinkedHashMap<String, String>()
        val sink = if (password != null && password.isNotEmpty()) BackupCrypto.seal(out, password) else out.buffered()
        // The key is made; the password itself isn't needed any more.
        password?.fill('\u0000')
        ZipOutputStream(sink).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifestBytes)
            zip.closeEntry()
            // Settings as values, not the store's own file: it also holds the sign-in, which only "Accounts" brings.
            if (settings.isNotEmpty()) {
                val bytes = InnerTube.json.encodeToString(SETTINGS_MAP, settings).toByteArray()
                putEntry(zip, SETTINGS_ENTRY, bytes)
                sums[SETTINGS_ENTRY] = sha256(bytes)
            }
            // Tags (1.8.3) live in the database (History) but belong to the library: they go as a file of their own.
            if (BackupPart.LIBRARY in parts) {
                val bytes = c.tags.exportJson(c.tags.export()).toByteArray()
                putEntry(zip, TAGS_ENTRY, bytes)
                sums[TAGS_ENTRY] = sha256(bytes)
                // Which song each music video is of (1.8.5): the library's too, so a restore without the history keeps
                // the matches (and a video's own sound, for one of none) rather than looking them all up again.
                val matches = InnerTube.json.encodeToString(VIDEO_SONG_LIST, c.db.videoSongs().all()).toByteArray()
                putEntry(zip, VIDEO_SONGS_ENTRY, matches)
                sums[VIDEO_SONGS_ENTRY] = sha256(matches)
            }
            filesToBackUp(parts).forEach { f ->
                val name = f.relativeTo(data).invariantSeparatorsPath
                val digest = MessageDigest.getInstance("SHA-256")
                zip.putNextEntry(ZipEntry(name))
                f.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        zip.write(buf, 0, n)
                    }
                }
                zip.closeEntry()
                sums[name] = hex(digest.digest())
            }
            val integrity = BackupIntegrity(entries = sums, manifest = sha256(manifestBytes))
            putEntry(zip, INTEGRITY, InnerTube.json.encodeToString(BackupIntegrity.serializer(), integrity).toByteArray())
        }
    }

    private fun putEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    /** About how big a backup of [parts] would be, in bytes. */
    suspend fun estimate(parts: Set<BackupPart>): Long = withContext(Dispatchers.IO) { filesToBackUp(parts).sumOf { it.length() } }

    private fun filesToBackUp(parts: Set<BackupPart>): List<File> {
        val keep = mutableListOf<File>()
        File(data, "databases").listFiles()?.forEach { f ->
            if ((BackupPart.HISTORY in parts && f.name.startsWith(DB)) || (BackupPart.DOWNLOADS in parts && f.name.startsWith(EXO_DB))) keep += f
        }
        File(data, "shared_prefs").listFiles()?.forEach { f ->
            if (!f.name.endsWith(".xml")) return@forEach
            if (prefsPart(f.name.removeSuffix(".xml")) in parts) keep += f
        }
        ctx.filesDir.walkTopDown()
            .onEnter { dir -> dir == ctx.filesDir || filePart(dir.relativeTo(ctx.filesDir).invariantSeparatorsPath.substringBefore('/')) in parts }
            .filter { it.isFile && (it.parentFile != ctx.filesDir || filePart(it.name) in parts) }
            // A restore's tags not read in yet: the database's are written above instead.
            .filter { "files/" + it.relativeTo(ctx.filesDir).invariantSeparatorsPath != TAGS_ENTRY }
            // The restored video matches (read into the database at the next start): written above, not as they are here.
            .filter { "files/" + it.relativeTo(ctx.filesDir).invariantSeparatorsPath != VIDEO_SONGS_ENTRY }
            .forEach { keep += it }
        return keep
    }

    /** The parts the backup at [uri] holds (every one for a backup made before 1.7.8), or null when it isn't one. */
    suspend fun partsOf(uri: Uri, password: CharArray? = null): Set<BackupPart>? = withContext(Dispatchers.IO) {
        runCatching {
            val raw = ctx.contentResolver.openInputStream(uri) ?: return@runCatching null
            val opened = BackupCrypto.open(raw, password)
            ZipInputStream(opened.stream).use { zip ->
                var seen = 0
                while (true) {
                    val e = zip.nextEntry ?: break
                    // Prism writes the manifest first: a file with it deep inside isn't one worth unpacking to find.
                    if (++seen > MAX_ENTRIES_BEFORE_MANIFEST) return@runCatching null
                    if (e.name != MANIFEST || e.isDirectory) continue
                    if (e.size > MAX_MANIFEST) return@runCatching null
                    // A manifest larger than this isn't one: never read an unbounded entry into memory.
                    val bytes = zip.readLimited(MAX_MANIFEST) ?: return@runCatching null
                    val m = InnerTube.json.decodeFromString(BackupManifest.serializer(), bytes.decodeToString())
                    if (!fromPrism(m)) return@runCatching null
                    return@runCatching m.partSet()
                }
                null
            }
        }.getOrNull()
    }

    /** Whether the file at [uri] is one of Prism's password-protected backups (its first bytes say so). */
    suspend fun isEncrypted(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val input = ctx.contentResolver.openInputStream(uri) ?: return@runCatching false
            input.use {
                val head = ByteArray(BackupCrypto.MAGIC.length)
                var got = 0
                while (got < head.size) {
                    val n = it.read(head, got, head.size - got)
                    if (n < 0) break
                    got += n
                }
                BackupCrypto.isEncrypted(head.copyOf(got))
            }
        }.getOrDefault(false)
    }

    /**
     * Checks the backup at [uri] and unpacks it to be put in place at the next launch, then
     * restarts Prism. Throws with a readable message if it isn't a Prism backup, is cut short, or an entry doesn't
     * match the hash the backup recorded for it. Nothing outside this app's own folders is ever unpacked, and only
     * the parts the manifest names (never a sign-in's or a pairing's own files).
     */
    suspend fun restore(uri: Uri, password: CharArray? = null) = withContext(Dispatchers.IO) {
        val staging = File(ctx.filesDir, STAGING)
        staging.deleteRecursively()
        staging.mkdirs()
        // Nothing left by an earlier restore is applied to this one's result.
        File(ctx.filesDir, RESTORED_SETTINGS).delete()
        File(ctx.filesDir, REDOWNLOAD).delete()
        var manifest: BackupManifest? = null
        var manifestHash = ""
        var integrity: BackupIntegrity? = null
        var opened: BackupCrypto.Opened? = null
        val hashes = LinkedHashMap<String, String>()
        val seen = HashSet<String>()
        // How much a file may unpack to: never more than the space there is, and a hard ceiling for a zip bomb.
        val room = runCatching { data.usableSpace }.getOrDefault(0L)
        val cap = if (room > RESERVE) minOf(MAX_TOTAL, room - RESERVE) else MAX_TOTAL
        var total = 0L
        try {
            val input = ctx.contentResolver.openInputStream(uri) ?: error(tr(R.string.couldn_t_open_that_file))
            opened = try {
                BackupCrypto.open(input, password)
            } catch (e: BackupCrypto.NeedsPassword) {
                error(tr(R.string.backup_password_wrong))
            }
            password?.fill('\u0000')
            val zip = ZipInputStream(opened.stream)
            var entries = 0
            var beforeManifest = 0
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                if (manifest == null && ++beforeManifest > MAX_ENTRIES_BEFORE_MANIFEST) error(tr(R.string.that_file_isn_t_a_prism_2))
                if (++entries > MAX_ENTRIES) error(tr(R.string.that_file_isn_t_a_prism_2))
                val name = e.name
                if (!safeEntryName(name) || !seen.add(name)) error(tr(R.string.that_file_isn_t_a_prism_2))
                when (name) {
                    MANIFEST, INTEGRITY -> {
                        val bytes = zip.readLimited(MAX_MANIFEST) ?: error(tr(R.string.that_file_isn_t_a_prism_2))
                        if (name == MANIFEST) {
                            manifestHash = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
                            manifest = runCatching { InnerTube.json.decodeFromString(BackupManifest.serializer(), bytes.decodeToString()) }.getOrNull()
                        } else {
                            integrity = runCatching { InnerTube.json.decodeFromString(BackupIntegrity.serializer(), bytes.decodeToString()) }.getOrNull()
                        }
                    }
                    else -> {
                        // Only Prism's own folders, settings and tags; anything else is never read.
                        if (!entryCouldBeRestored(name)) continue
                        val target = File(staging, name)
                        if (!inside(staging, target)) continue
                        val limit = entryLimit(name)
                        if (e.size > limit) error(tr(R.string.that_file_isn_t_a_prism_2))
                        target.parentFile?.mkdirs()
                        val digest = MessageDigest.getInstance("SHA-256")
                        var entry = 0L
                        target.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = zip.read(buf)
                                if (n < 0) break
                                entry += n
                                total += n
                                if (entry > limit || total > cap) error(tr(R.string.that_file_isn_t_a_prism_2))
                                digest.update(buf, 0, n)
                                out.write(buf, 0, n)
                            }
                        }
                        hashes[name] = hex(digest.digest())
                    }
                }
            }
            // A protected file's tag is at its end: reading on is what tells a right password from a changed file.
            opened.drain()
            val m = manifest ?: error(tr(R.string.that_file_isn_t_a_prism_2))
            if (!fromPrism(m) || m.bring) error(tr(R.string.that_file_isn_t_a_prism_2))
            if (m.format > FORMAT) error(tr(R.string.that_backup_is_from_a_newer))
            // Only what this backup says it holds: a file of another part (or one that never travels) isn't taken.
            val whole = m.parts == null
            val parts = m.partSet()
            hashes.keys.toList().forEach { name ->
                val part = entryPart(name)
                val keep = when {
                    name == SETTINGS_ENTRY -> whole || BackupPart.SETTINGS in parts || BackupPart.ACCOUNTS in parts
                    name == TAGS_ENTRY -> whole || BackupPart.LIBRARY in parts
                    // The store's own file also holds the sign-in: only a whole backup from before 1.7.8 brings it.
                    name.startsWith("datastore/") -> whole
                    else -> part != null && part in parts
                }
                if (!keep) {
                    File(staging, name).delete()
                    hashes.remove(name)
                }
            }
            if (m.integrity > 1) error(tr(R.string.that_file_isn_t_a_prism_2))
            if (m.integrity == 1) {
                // The whole list, and every hash, has to match: a cut-short or altered backup is refused here.
                val i = integrity ?: error(tr(R.string.that_file_isn_t_a_prism_2))
                if (i.version > 1 || !i.manifest.equals(manifestHash, true) || i.entries.keys != hashes.keys) {
                    error(tr(R.string.that_file_isn_t_a_prism_2))
                }
                for ((name, hash) in i.entries) if (!hash.equals(hashes[name], true)) error(tr(R.string.that_file_isn_t_a_prism_2))
                File(staging, INTEGRITY).writeText(InnerTube.json.encodeToString(BackupIntegrity.serializer(), i))
            }
            File(staging, MANIFEST).writeText(InnerTube.json.encodeToString(BackupManifest.serializer(), m))
            // Only a restore unpacked and checked all the way through is applied at the next start.
            File(staging, COMPLETE).writeText(m.integrity.toString())
        } catch (e: Throwable) {
            staging.deleteRecursively()
            // A protected file that won't open is either the wrong password or one that was changed/cut short.
            if (opened?.encrypted == true && (e is BackupCrypto.NeedsPassword || e is java.io.IOException)) {
                error(tr(R.string.backup_password_wrong))
            }
            throw e
        } finally {
            runCatching { opened?.stream?.close() }
        }
        restart()
    }

    private fun restart() {
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
        ctx.startActivity(Intent.makeRestartActivityTask(launch.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Runtime.getRuntime().exit(0)
    }

    /** After a restore: puts the backup's settings in place (a backup since 1.7.8 holds them as values; see [export]). */
    suspend fun applyRestoredSettings() {
        val s = File(ctx.filesDir, RESTORED_SETTINGS)
        if (!s.exists()) return
        runCatching {
            val r = InnerTube.json.decodeFromString(RestoredSettings.serializer(), s.readText())
            c.settings.fromBackup(r.values, settings = BackupPart.SETTINGS.name in r.parts, accounts = BackupPart.ACCOUNTS.name in r.parts)
        }
        s.delete()
    }

    /** After a restore: fetches the downloads again when their audio wasn't in the backup. */
    fun resumeDownloads() {
        val f = File(ctx.filesDir, REDOWNLOAD)
        if (!f.exists()) return
        val songs = runCatching { InnerTube.json.decodeFromString(BackupManifest.serializer(), f.readText()).downloads }
            .getOrDefault(emptyList()).take(MAX_REDOWNLOAD)
        f.delete()
        c.downloads.downloadAll(songs)
    }

    // ------------------------------------------------------------ Bring your Prism (1.8.0)

    private fun prefsOf(name: String) = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)

    /**
     * What makes this Prism the listener's, for their TV, written to [out] in the backup's format (a zip with
     * prism-backup.json): listening history and likes, Prism playlists, folders and saved albums, starred artists, song
     * versions, lyric timings, best parts and exclusions ([BringOver]); [look]: their Look's code, when they chose it.
     * Never the sign-in, downloads' audio or the phone's settings.
     */
    suspend fun bringBundle(out: File, look: String?) = withContext(Dispatchers.IO) {
        runCatching { c.db.query(androidx.sqlite.db.SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)")).close() }
        val content = BringOver.collect(c.db, ::prefsOf, c.prismLibrary.forBringing(), look)
        out.outputStream().buffered().use { writeBring(it, content, ctx.packageName) }
    }

    /**
     * A phone's bundle ([bringBundle]) merged into this Prism (a TV's): added to what's here, nothing here wiped
     * ([BringOver.merge]); then what keeps those choices in memory reads them again. Throws when it isn't one.
     */
    suspend fun mergeBrought(file: File): BringMerged = withContext(Dispatchers.IO) {
        val content = file.inputStream().buffered().use { readBring(it) } ?: error(tr(R.string.bring_didn_t_check_out))
        val merged = BringOver.merge(content, c.db, ::prefsOf, c.prismLibrary)
        c.artistPrefs.reload()
        c.versions.reload()
        c.exclusions.reload()
        c.library.dislikedIds.value = prefsOf("dislikes").getStringSet("ids", emptySet()).orEmpty().toSet()
        content.look?.let { code -> c.looks.decode(code)?.let { c.looks.apply(it) } }
        merged
    }

    @Serializable
    private data class RestoredSettings(val parts: List<String>, val values: Map<String, String>)

    companion object {
        /** 2 (1.7.8): settings as values and a list of parts; Prism before 1.7.8 says "update first" to these. */
        private const val FORMAT = 2
        private const val MANIFEST = "prism-backup.json"
        private const val SETTINGS_ENTRY = "settings.json"
        private const val STAGING = "restore_pending"
        private const val REDOWNLOAD = "restore_downloads.json"
        /** A restored backup's settings, waiting for the settings store to open after the restart. */
        private const val RESTORED_SETTINGS = "restore_settings.json"
        private const val DB = "prism.db"
        private const val EXO_DB = "exoplayer_internal.db"
        private val ALLOWED = listOf("databases/", "shared_prefs/", "datastore/", "files/")
        private val SETTINGS_MAP = MapSerializer(String.serializer(), String.serializer())
        private val VIDEO_SONG_LIST = ListSerializer(VideoSongEntity.serializer())

        /** The trailing list of hashes that tells a whole backup from a cut-short or altered one ([BackupManifest.integrity]). */
        private const val INTEGRITY = "prism-integrity.json"
        /** Written only once every entry of a restore has been unpacked and checked: an unfinished one is never applied. */
        private const val COMPLETE = ".complete"
        /** What an apply moves, and where the replaced files wait until it's through (both under the app's data folder). */
        private const val JOURNAL = "restore_journal.json"
        private const val OLD = "restore_old"
        private const val APPLIED = "restore_applied"
        /** Never more than this from one backup, whatever it says: a zip that unpacks to more isn't one. */
        private const val MAX_MANIFEST = 32L * 1024 * 1024
        private const val MAX_ENTRIES = 100_000
        /** A manifest after this many other entries isn't one Prism wrote; don't unpack the rest looking for it. */
        private const val MAX_ENTRIES_BEFORE_MANIFEST = 64
        private const val MAX_ENTRY = 4L * 1024 * 1024 * 1024
        private const val MAX_TOTAL = 128L * 1024 * 1024 * 1024
        /** Settings are values (a small JSON): never read a whole gigabyte of them, or tags, into memory. */
        private const val MAX_SETTINGS = 16L * 1024 * 1024
        private const val MAX_TAGS = 64L * 1024 * 1024
        /** Video matches: one line each, but a library's worth. */
        private const val MAX_VIDEO_MATCHES = 64L * 1024 * 1024
        /** Small files are checked again at start-up (settings, tags); audio-sized ones were checked when unpacked. */
        private const val VERIFY_AT_START = 16L * 1024 * 1024
        /** A restored phone re-fetches at most this many downloads at once (a backup can't ask for endless ones). */
        private const val MAX_REDOWNLOAD = 20_000
        /** Kept free while a restore unpacks beside the files it will replace. */
        private const val RESERVE = 512L * 1024 * 1024
        private const val MAX_NAME = 1024

        private const val BRING_HISTORY = "bring/history.json"
        private const val BRING_PREFS = "bring/prefs.json"
        /** Prism's playlists in the library file's own format, at the library file's own place in a backup. */
        private const val BRING_LIBRARY = "files/library/prism.json"
        private const val BRING_LOOK = "bring/look.txt"
        /** The library's tags (1.8.3), read in at the next start ([TagStore.readPending]). */
        private const val TAGS_ENTRY = "files/library/" + TagStore.PENDING
        /**
         * The songs music videos were found to be of (1.8.5), a library entry read into the database at the next start
         * ([readPendingVideoSongs]): so a restore that brings the library without the history keeps the matches.
         */
        internal const val VIDEO_SONGS_ENTRY = "files/library/video_songs.json"
        /** All a bundle may unpack to (its own size is held to [com.prism.music.connect.BringTransfer.MAX_BYTES]). */
        private const val BRING_UNPACKED_MAX = 128L * 1024 * 1024

        /** A "Bring your Prism" bundle ([bringBundle]): the backup's manifest, marked as one, and its entries. */
        fun writeBring(out: java.io.OutputStream, content: BringContent, app: String) {
            val manifest = BackupManifest(
                format = FORMAT, app = app, versionCode = BuildConfig.VERSION_CODE, versionName = BuildConfig.VERSION_NAME,
                createdAt = System.currentTimeMillis(), parts = listOf(BackupPart.HISTORY.name, BackupPart.LIBRARY.name), bring = true,
            )
            ZipOutputStream(out).use { zip ->
                fun put(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
                put(MANIFEST, InnerTube.json.encodeToString(BackupManifest.serializer(), manifest))
                put(BRING_HISTORY, BringOver.historyJson(content.history))
                put(BRING_PREFS, BringOver.prefsJson(content.prefs))
                content.library?.let { put(BRING_LIBRARY, it) }
                content.look?.let { put(BRING_LOOK, it) }
            }
        }

        /**
         * A bundle read back, or null when it isn't one: no manifest of Prism's marked as a bundle, a newer format, an
         * entry that won't read, or more than [BRING_UNPACKED_MAX] unpacked. Entries it doesn't know are skipped.
         */
        fun readBring(input: java.io.InputStream): BringContent? = runCatching {
            var manifest: BackupManifest? = null
            var history: BringHistory? = null
            var prefs: Map<String, Map<String, String>>? = null
            var library: String? = null
            var look: String? = null
            var total = 0L
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory || e.name !in setOf(MANIFEST, BRING_HISTORY, BRING_PREFS, BRING_LIBRARY, BRING_LOOK)) continue
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        val n = zip.read(chunk)
                        if (n < 0) break
                        total += n
                        if (total > BRING_UNPACKED_MAX) return@runCatching null
                        buf.write(chunk, 0, n)
                    }
                    val text = buf.toString(Charsets.UTF_8.name())
                    when (e.name) {
                        MANIFEST -> manifest = InnerTube.json.decodeFromString(BackupManifest.serializer(), text)
                        BRING_HISTORY -> history = BringOver.history(text) ?: return@runCatching null
                        BRING_PREFS -> prefs = BringOver.prefs(text) ?: return@runCatching null
                        BRING_LIBRARY -> library = text
                        BRING_LOOK -> look = text.take(64_000)
                    }
                }
            }
            val m = manifest ?: return@runCatching null
            if (m.app.isBlank() || !m.bring || m.format > FORMAT) return@runCatching null
            BringContent(history ?: BringHistory(), prefs.orEmpty(), library, look)
        }.getOrNull()

        /**
         * A restore's video matches, read in once at the next start: the rows the backup held (null when there are none
         * waiting), and the file goes whether or not it reads.
         */
        internal fun readPendingVideoSongs(files: File): List<VideoSongEntity>? {
            val f = File(files, VIDEO_SONGS_ENTRY.removePrefix("files/"))
            if (!f.isFile) return null
            val rows = runCatching { InnerTube.json.decodeFromString(VIDEO_SONG_LIST, f.readText()) }.getOrNull()
            f.delete()
            return rows
        }

        /** Saved preferences by part; anything not named here counts as Settings. Some never go in a backup (null). */
        private val PREFS_PARTS: Map<String, BackupPart?> = buildMap {
            // playlist_covers (with files/covers below) holds songs' and albums' own covers too (#305, 1.8.1).
            listOf(
                "artist_prefs", "artist_thumbs", "playlist_covers", "library_order", "blend", "liked_playlist", "recent_collections",
                "song_versions", "song_metadata",
            ).forEach { put(it, BackupPart.LIBRARY) }
            listOf(
                "dislikes", "exclusions", "for_you", "for_you_feedback", com.prism.music.data.foryou.Discover.PREFS, "release_radar", "search", "episode_progress", "episode_played", "best_part_mine",
                "mix_tempo", "song_loudness", "song_loudness_version", "song_peak", "song_key", "dj_quick", "loudness", "lyric_offsets", "lyrics_choice", "lyrics_search_as",
                "romanize_songs", "video_sync", "local_album_tags", "local_lengths", "local_no_art", "lyrics_word_shift",
                // 1.8.1: Bring your history's choices (what Replay counts, what shapes the mixes).
                "imports",
                // 1.8.1: YouTube Music (live): its switch, its kill switch and the History page as last read.
                com.prism.music.data.imports.YouTubeLive.PREFS,
            ).forEach { put(it, BackupPart.HISTORY) }
            listOf(
                "canvas_saved", "download_lyrics", "download_failures", "download_auto_retry", "download_formats", "smart_downloads", "download_standins",
                "lossless_links", "lossless_misses", "lossless_hidden", "lossless_alac",
                // 1.8.2: songs skipped in lists' downloads (#221), when kept playlists were last read (#298).
                "download_skips", "kept_playlists",
            ).forEach { put(it, BackupPart.DOWNLOADS) }
            // The Last.fm session key is sealed to this phone (a key only it has): a restored phone reconnects instead.
            put("lastfm", null)
            // 1.8.1: the Spotify connection's Client ID and switches go with the accounts (a Client ID is public in
            // PKCE); its tokens never travel, as Discord's sign-in doesn't (sealed by a key only this phone has anyway).
            put(com.prism.music.data.spotify.SpotifyStore.PREFS, BackupPart.ACCOUNTS)
            put(com.prism.music.data.spotify.SpotifyStore.AUTH_PREFS, null)
            // The Discord sign-in is a key to the whole Discord account; Prism Connect's pairings belong to this phone.
            listOf("discord", "connect_remote", "connect_handoff").forEach { put(it, null) }
            // The song cache's formats describe this phone's cache, which a backup doesn't carry.
            put("cache_formats", null)
            // What this sitting's mixes have played (1.8.2): over within hours, nothing a restored phone would want.
            put("session_heard", null)
            // When the library's music videos were last got ready (1.8.5): a restored phone does its own run.
            put("video_prepare", null)
            // Which install this is (RestoredInstall): restoring one's own backup elsewhere mustn't look like Android's.
            put(RestoredInstall.PREFS, null)
        }

        /** Folders (and files) right under files/ by part; anything else counts as Settings. */
        private val FILE_PARTS: Map<String, BackupPart?> = buildMap {
            listOf("library", "blends", "covers", "offline", "artwork").forEach { put(it, BackupPart.LIBRARY) }
            // 1.8.1: imports/ holds the library a data download is still bringing over (its plays are in the database).
            listOf("rewind", "waves", "queue.json", "imports").forEach { put(it, BackupPart.HISTORY) }
            put("account", BackupPart.ACCOUNTS)
            // What the Spotify connection read (which Prism playlists show which Spotify ones, the likes' sync state).
            put(com.prism.music.data.spotify.Spotify.DIR, BackupPart.ACCOUNTS)
            listOf("downloads", "canvas_saved").forEach { put(it, BackupPart.DOWNLOADS) }
            // Downloaded data files (models/) can always be downloaded again; stems are made again; staging is a restore's.
            listOf(STAGING, "models", "stems", REDOWNLOAD, RESTORED_SETTINGS).forEach { put(it, null) }
            // The settings store's own file holds the sign-in too: settings travel as values (settings.json), never as
            // this file, or a "Looks only" backup would carry the YouTube session. Android's backup may bring it, and
            // RestoredInstall signs a restored copy out.
            put("datastore", null)
            // Diagnostics and crash logs are this phone's, and have no place in a file shared with anyone.
            listOf("crashes", "diagnostics", "lyrics-tags").forEach { put(it, null) }
            // ...and the crash log keeps its last details in a prefs file too (1.8.5).
            put("crashlog", null)
            // Which one-time moves into the database this install has done: another phone's database hasn't had them.
            put("migrations", null)
            // Lyric translations (1.7.8) are a cache of Google's answers, asked for again when missing.
            put("translations", null)
            // Songs' "From" whole playlists (1.8.4, #473): read again from YouTube when missing, nothing to carry over.
            put("songs_from", null)
        }

        internal fun prefsPart(name: String): BackupPart? = if (name in PREFS_PARTS) PREFS_PARTS[name] else BackupPart.SETTINGS
        internal fun filePart(top: String): BackupPart? = if (top in FILE_PARTS) FILE_PARTS[top] else BackupPart.SETTINGS

        /** How large a particular entry may be: settings and tags are read whole, so they have a tighter cap. */
        private fun entryLimit(name: String): Long = when (name) {
            SETTINGS_ENTRY -> MAX_SETTINGS
            TAGS_ENTRY -> MAX_TAGS
            VIDEO_SONGS_ENTRY -> MAX_VIDEO_MATCHES
            else -> MAX_ENTRY
        }

        /** The part an entry belongs to, or null for one that never travels or isn't Prism's at all. */
        private fun entryPart(name: String): BackupPart? = when {
            name == SETTINGS_ENTRY -> BackupPart.SETTINGS
            name == TAGS_ENTRY -> BackupPart.LIBRARY
            name == VIDEO_SONGS_ENTRY -> BackupPart.LIBRARY
            name.startsWith("databases/") -> {
                val f = name.removePrefix("databases/")
                when {
                    f.contains('/') -> null
                    f.startsWith(DB) -> BackupPart.HISTORY
                    f.startsWith(EXO_DB) -> BackupPart.DOWNLOADS
                    else -> null
                }
            }
            name.startsWith("shared_prefs/") -> {
                val f = name.removePrefix("shared_prefs/")
                if (f.contains('/') || !f.endsWith(".xml")) null else prefsPart(f.removeSuffix(".xml"))
            }
            name.startsWith("datastore/") -> BackupPart.SETTINGS
            name.startsWith("files/") -> {
                val top = name.removePrefix("files/").substringBefore('/')
                if (top.isBlank()) null else filePart(top)
            }
            else -> null
        }

        /** Whether [name] is a file this backup's [parts] may bring (and not one that never travels). */
        private fun entryAllowed(name: String, whole: Boolean, parts: Set<BackupPart>): Boolean {
            if (name == SETTINGS_ENTRY) return whole || BackupPart.SETTINGS in parts || BackupPart.ACCOUNTS in parts
            if (name == TAGS_ENTRY) return whole || BackupPart.LIBRARY in parts
            if (name == VIDEO_SONGS_ENTRY) return whole || BackupPart.LIBRARY in parts
            // A store's own file (an old backup) also holds the sign-in: only a whole old backup may bring it.
            if (name.startsWith("datastore/")) return whole
            val part = entryPart(name) ?: return false
            return part in parts
        }

        /** An entry whose name is one a restore might take (its part is checked once the manifest is read). */
        private fun entryCouldBeRestored(name: String): Boolean =
            name == SETTINGS_ENTRY || name == TAGS_ENTRY || name == VIDEO_SONGS_ENTRY || ALLOWED.any { name.startsWith(it) }

        /** No climbing out, no absolute paths, no odd separators or control characters, and no ".." anywhere. */
        private fun safeEntryName(name: String): Boolean =
            name.isNotEmpty() && name.length <= MAX_NAME &&
                !name.startsWith("/") && !name.startsWith("\\") && !name.contains('\\') && !name.contains("..") &&
                name.none { it.code < 0x20 } &&
                name.split('/').all { it.isNotEmpty() && it != "." }

        private fun inside(root: File, target: File): Boolean =
            runCatching { target.canonicalPath.startsWith(root.canonicalPath + File.separator) }.getOrDefault(false)

        private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

        private fun sha256(file: File): String = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
            hex(digest.digest())
        }

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        /** The entry's bytes, or null when it's longer than [max]: never read an unbounded entry into memory. */
        private fun InputStream.readLimited(max: Long): ByteArray? {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            var total = 0L
            while (true) {
                val n = read(buf)
                if (n < 0) return out.toByteArray()
                total += n
                if (total > max) return null
                out.write(buf, 0, n)
            }
        }

        private fun BackupManifest.partSet(): Set<BackupPart> =
            parts?.mapNotNull { n -> BackupPart.entries.firstOrNull { it.name == n } }?.toSet()
                ?: (BackupPart.entries.toSet() - if (withDownloads) emptySet() else setOf(BackupPart.DOWNLOADS))

        /**
         * Puts a staged restore in place. Runs first thing at launch, before Room, DataStore or any
         * preferences are opened, so nothing holds the old files. Only the parts the backup holds are replaced.
         *
         * The files it replaces are moved aside first and a journal is written before the first move: an apply cut
         * short (or one whose files don't check out) puts this phone's own data back instead of leaving half of each.
         */
        fun applyPending(context: Context) {
            val files = context.filesDir
            val data = files.parentFile!!
            val staging = File(files, STAGING)
            // An apply that stopped partway (or one Android killed) is settled before anything else is read.
            settleInterrupted(data, files)
            val manifestFile = File(staging, MANIFEST)
            if (!manifestFile.exists()) { staging.deleteRecursively(); return }
            runCatching {
                val m = InnerTube.json.decodeFromString(BackupManifest.serializer(), manifestFile.readText())
                if (m.app.isBlank() || m.bring || m.format > FORMAT) error(tr(R.string.that_file_isn_t_a_prism_2))
                if (m.integrity == 1 && !File(staging, COMPLETE).exists()) error(tr(R.string.that_file_isn_t_a_prism_2))
                val whole = m.parts == null
                val parts = m.partSet()
                // Every staged file is checked again against the backup's hashes before a single thing is replaced.
                val staged = stagedFiles(staging, m, whole, parts) ?: error(tr(R.string.that_file_isn_t_a_prism_2))
                // Tags are the library's, kept in the database: a restore of the history alone keeps this phone's.
                if (BackupPart.HISTORY in parts && BackupPart.LIBRARY !in parts) TagStore.readFrom(File(data, "databases/$DB"))?.let { mine ->
                    File(files, "library").mkdirs()
                    File(files, "library/" + TagStore.PENDING).writeText(InnerTube.json.encodeToString(TagsFile.serializer(), mine))
                }
                // The settings wait for the store to open ([applyRestoredSettings]); "Settings" or "Accounts" without
                // any values still clears what they replace.
                val settingsFile = File(staging, SETTINGS_ENTRY)
                val settingsText = if (!whole && (BackupPart.SETTINGS in parts || BackupPart.ACCOUNTS in parts)) {
                    if (settingsFile.length() > MAX_SETTINGS) error(tr(R.string.that_file_isn_t_a_prism_2))
                    val values = if (settingsFile.exists()) InnerTube.json.decodeFromString(SETTINGS_MAP, settingsFile.readText()) else emptyMap()
                    InnerTube.json.encodeToString(RestoredSettings.serializer(), RestoredSettings(m.parts.orEmpty(), values))
                } else null
                val redownload = !m.withDownloads && m.downloads.isNotEmpty()

                val old = oldPaths(data, files, m, whole, parts)
                val new = staged.toMutableList().apply {
                    if (settingsText != null) add(RESTORED_SETTINGS)
                    if (redownload) add(REDOWNLOAD)
                }
                val journal = File(data, JOURNAL)
                writeAtomically(journal, InnerTube.json.encodeToString(RestoreJournal.serializer(), RestoreJournal(old, new)))
                try {
                    moveOldAside(data, old)
                    moveIn(data, staging, staged)
                    settingsText?.let { File(files, RESTORED_SETTINGS).writeText(it) }
                    if (redownload) manifestFile.copyTo(File(files, REDOWNLOAD), overwrite = true)
                    // Everything is in place: from here a restart keeps it rather than rolling it back.
                    File(data, APPLIED).createNewFile()
                } catch (e: Throwable) {
                    rollBack(data)
                    throw e
                }
                journal.delete()
                File(data, OLD).deleteRecursively()
                File(data, APPLIED).delete()
                staging.deleteRecursively()
            }.onFailure {
                // Nothing is half-applied: whatever was, is put back; the staged restore is dropped.
                runCatching { if (File(data, JOURNAL).exists()) rollBack(data) }
                staging.deleteRecursively()
            }
        }

        /** Every whole file in [staging], as paths under the app's data folder; null if one isn't the backup's own, or a small one's hash doesn't match. */
        private fun stagedFiles(staging: File, m: BackupManifest, whole: Boolean, parts: Set<BackupPart>): List<String>? {
            val integrity = if (m.integrity == 1) {
                runCatching { InnerTube.json.decodeFromString(BackupIntegrity.serializer(), File(staging, INTEGRITY).readText()) }.getOrNull()
                    ?: return null
            } else null
            val names = LinkedHashSet<String>()
            val rels = mutableListOf<String>()
            staging.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(staging).invariantSeparatorsPath
                if (rel == MANIFEST || rel == INTEGRITY || rel == COMPLETE) return@forEach
                if (!entryAllowed(rel, whole, parts)) return null
                if (f.length() > entryLimit(rel)) return null
                names += rel
                // Settings are values read above, not a file put among this phone's own (it's a transfer file).
                if (rel != SETTINGS_ENTRY) rels += rel
            }
            if (integrity != null) {
                if (integrity.version > 1 || integrity.entries.keys != names) return null
                // The whole list is checked here; a file's own bytes were hashed when the backup was unpacked. Hashing
                // gigabytes again at start-up would hold the phone's launch up for nothing (it is the app's own folder).
                for ((name, hash) in integrity.entries) {
                    val file = File(staging, name)
                    if (file.length() <= VERIFY_AT_START) {
                        if (!hash.equals(sha256(file), true)) return null
                    }
                }
            }
            return rels
        }

        /** What the backup replaces: this phone's own copies, kept in [OLD] until everything is in. */
        private fun oldPaths(data: File, files: File, m: BackupManifest, whole: Boolean, parts: Set<BackupPart>): List<String> {
            val old = mutableListOf<String>()
            File(data, "databases").listFiles()?.forEach {
                if ((BackupPart.HISTORY in parts && it.name.startsWith(DB)) || (BackupPart.DOWNLOADS in parts && it.name.startsWith(EXO_DB))) old += "databases/${it.name}"
            }
            File(data, "shared_prefs").listFiles()?.forEach { if (whole || prefsPart(it.name.removeSuffix(".xml")) in parts) old += "shared_prefs/${it.name}" }
            // A backup from before 1.7.8 brings the settings store's own file; a newer one, its values.
            if (whole && File(data, "datastore").exists()) old += "datastore"
            files.listFiles()?.forEach { f ->
                if (f.name == STAGING) return@forEach
                if (whole) { if (m.withDownloads || filePart(f.name) != BackupPart.DOWNLOADS) old += "files/${f.name}" }
                else if (filePart(f.name) in parts) old += "files/${f.name}"
            }
            return old
        }

        private fun moveOldAside(data: File, old: List<String>) {
            for (rel in old) {
                val src = File(data, rel)
                if (!src.exists()) continue
                val dst = File(data, "$OLD/$rel")
                dst.parentFile?.mkdirs()
                dst.deleteRecursively()
                // Same filesystem, so this is a rename; a copy would leave half a file if it were cut short, and the
                // rollback would then put that half in place of the whole.
                if (!src.renameTo(dst)) error(tr(R.string.that_file_isn_t_a_prism_2))
            }
        }

        private fun moveIn(data: File, staging: File, staged: List<String>) {
            for (rel in staged) {
                val src = File(staging, rel)
                if (!src.isFile) continue
                val dst = File(data, rel)
                dst.parentFile?.mkdirs()
                if (dst.exists()) dst.deleteRecursively()
                if (!src.renameTo(dst)) { src.copyTo(dst, overwrite = true); src.delete() }
            }
        }

        /** Puts back what [JOURNAL] says was moved, then forgets it. Safe to run again after being cut short itself. */
        private fun rollBack(data: File) {
            val journal = runCatching { InnerTube.json.decodeFromString(RestoreJournal.serializer(), File(data, JOURNAL).readText()) }.getOrNull()
            if (journal != null) {
                // Out with anything the backup put in, then this phone's own back. A file that was never moved aside
                // (the rename failed) is still this phone's, so it isn't taken away.
                for (rel in journal.new.asReversed()) {
                    if (rel in journal.old && !File(data, "$OLD/$rel").exists()) continue
                    File(data, rel).deleteRecursively()
                }
                for (rel in journal.old.asReversed()) restoreSaved(data, rel)
            } else {
                // The journal itself is unreadable: put every saved file back, without taking anything away.
                File(data, OLD).walkTopDown().filter { it.isFile }.forEach { f ->
                    restoreSaved(data, f.relativeTo(File(data, OLD)).invariantSeparatorsPath)
                }
            }
            File(data, OLD).deleteRecursively()
            File(data, JOURNAL).delete()
            File(data, APPLIED).delete()
        }

        private fun restoreSaved(data: File, rel: String) {
            val saved = File(data, "$OLD/$rel")
            if (!saved.exists()) return
            val target = File(data, rel)
            if (saved.renameTo(target)) return
            // A copy is made beside the target first: the old file is only taken away once the whole copy is there.
            val temp = File(target.parentFile, ".${target.name}.restoring")
            temp.deleteRecursively()
            saved.copyRecursively(temp, overwrite = true)
            target.deleteRecursively()
            if (!temp.renameTo(target)) { temp.copyTo(target, overwrite = true); temp.deleteRecursively() }
            saved.deleteRecursively()
        }

        /** After a restart: finishes an apply that committed but was cut short, or puts back one that wasn't. */
        private fun settleInterrupted(data: File, files: File) {
            val journal = File(data, JOURNAL)
            if (!journal.exists()) {
                File(data, OLD).deleteRecursively()
                File(data, APPLIED).delete()
                return
            }
            if (!File(data, APPLIED).exists()) rollBack(data) else {
                File(data, OLD).deleteRecursively()
                journal.delete()
                File(data, APPLIED).delete()
            }
            File(files, STAGING).deleteRecursively()
        }

        private fun writeAtomically(target: File, text: String) {
            val temp = File(target.parentFile, "${target.name}.tmp")
            temp.writeText(text)
            // A journal that made it to disk before the first move is what makes the moves safe.
            runCatching { java.io.FileOutputStream(temp, true).use { it.fd.sync() } }
            if (!temp.renameTo(target)) { temp.copyTo(target, overwrite = true); temp.delete() }
        }
    }
}
