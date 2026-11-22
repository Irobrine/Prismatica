package com.prism.music.data.dedup

import android.net.Uri
import com.prism.music.AppContainer
import com.prism.music.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The app-facing coordinator: owns the persistent store and the (manual-only) scanner, keeps the current groups and
 * decisions in memory for the screens, and builds the filtered views for each scope.
 *
 * It never changes a song, a file, a playlist or the library. Everything it does is a view over those, plus its own
 * small store of fingerprints and decisions.
 */
class DedupManager(
    private val container: AppContainer,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val db = DedupDatabase.build(container.app)
    val store = DedupStore(db.dao())
    private val scratch = File(container.app.filesDir, "dedup")

    val relations: StateFlow<List<Relation>> get() = _relations
    private val _relations = MutableStateFlow<List<Relation>>(emptyList())
    val groups: StateFlow<List<Grouping.Group>> get() = _groups
    private val _groups = MutableStateFlow<List<Grouping.Group>>(emptyList())
    val suggestions: StateFlow<List<Relation>> get() = _suggestions
    private val _suggestions = MutableStateFlow<List<Relation>>(emptyList())
    val pins: StateFlow<Set<String>> get() = _pins
    private val _pins = MutableStateFlow<Set<String>>(emptySet())

    val scanner = DedupScanner(
        scope,
        store,
        ::files,
        ::fingerprint,
        onAutomatic = { fresh ->
            val set = store.relationSet()
            set.replaceAuto(fresh)
            store.saveRelations(set)
            refresh()
        },
        onFileChanged = { ref ->
            // Manual decisions are kept, but flagged: a person should re-listen to a pair whose file changed.
            val set = store.relationSet()
            set.markChanged(ref.songId, System.currentTimeMillis())
            store.saveRelations(set)
        },
    )

    init {
        scope.launch(Dispatchers.IO) { refresh() }
        // If the version that was playing becomes hidden (a scan grouped it, a page turned hiding on, or a pin moved),
        // play the group's representative in its place, keeping the queue and the position in the song.
        scope.launch {
            combine(container.player.currentSong, _groups, settings.flow) { current, groups, s -> Triple(current, groups, s) }
                .distinctUntilChanged()
                .collectLatest { (current, groups, s) ->
                    val playing = current ?: return@collectLatest
                    val anyOn = DedupScope.entries.any {
                        DedupSettingsLogic.enabled(it, s.behavior.dedup.enabled, DedupSettingsLogic.parse(s.behavior.dedup.overrides))
                    }
                    if (!anyOn || groups.none { playing.id in it.members }) return@collectLatest
                    val group = groups.first { playing.id in it.members }
                    val rep = Representative.replacement(playing.id, groups, candidatesFor(group.members))
                    if (rep != null && rep != playing.id) {
                        val version = knownSongs()[rep] ?: playing.copy(id = rep)
                        withContext(Dispatchers.Main) { container.player.swapCurrentWith(version) }
                    }
                }
        }
    }

    /** Reloads the persisted decisions and recomputes groups. Called after a scan and after every manual edit. */
    suspend fun refresh(): Unit = withContext(Dispatchers.IO) {
        // Also refreshes the "new files since the last scan" hint, without fingerprinting anything.
        try { scanner.pending() } catch (_: Exception) { Unit }
        val set = store.relationSet()
        _relations.value = set.all()
        _pins.value = store.pins()
        // "Play a different version" is an existing, manually chosen alternative-version relationship. It overrides
        // the fingerprint thresholds: a person who picked B for A has already said they belong together.
        val picked = container.versions.picks.value.mapNotNull { (songId, pick) ->
            if (songId == pick.id || set.hasPair(songId, pick.id)) null
            else Relation(songId, pick.id, RelationSource.MANUAL, at = 0L)
        }
        val statements = set.all() + picked
        val ids = (statements.flatMap { listOf(it.a, it.b) } + files().map { it.songId }).toSet()
        val result = Grouping.build(ids, statements)
        _groups.value = result.groups
        _suggestions.value = result.suggestions
    }

    /** The files of every dedup scope that can be fingerprinted: local files and finished downloads. */
    private suspend fun files(): List<DedupScanner.FileRef> = withContext(Dispatchers.IO) {
        val out = ArrayList<DedupScanner.FileRef>()
        container.localMusic.tracks.value.forEach { track ->
            val uri = runCatching { container.localMusic.uriFor(track.song.id) }.getOrNull() ?: return@forEach
            out += DedupScanner.FileRef(
                key = uri.toString(), songId = track.song.id, uri = uri.toString(),
                sizeBytes = track.size, modifiedMs = track.addedAt, durationMs = track.song.durationSec * 1000L,
            )
        }
        container.downloadState.value.forEach { (id, info) ->
            if (info.state != androidx.media3.exoplayer.offline.Download.STATE_COMPLETED || info.song == null) return@forEach
            out += DedupScanner.FileRef(
                key = "download:$id", songId = id, uri = "prismdownload:$id",
                sizeBytes = info.bytes, modifiedMs = info.addedAt, durationMs = info.song.durationSec * 1000L,
            )
        }
        out
    }

    private suspend fun fingerprint(ref: DedupScanner.FileRef): AudioFingerprint = withContext(Dispatchers.IO) {
        val input: Uri = if (ref.uri.startsWith("prismdownload:")) {
            val id = ref.uri.removePrefix("prismdownload:")
            val cached = File(scratch, "download_${id.hashCode()}.audio")
            container.audioOnPhone(id, id, cached) ?: error("the downloaded audio is not on the phone")
        } else Uri.parse(ref.uri)
        DedupFingerprinter.of(container.app, input, ref.durationMs) { scanner.running.value.not() }
    }

    /** Whether a scope is currently deduplicated: its override, or the global default when it inherits. */
    fun enabled(scope: DedupScope): Boolean {
        val s = settings.current
        return DedupSettingsLogic.enabled(scope, s.behavior.dedup.enabled, DedupSettingsLogic.parse(s.behavior.dedup.overrides))
    }

    /** The filtered view of a collection, built from its current groups and the songs it displays. */
    fun views(scope: DedupScope, candidates: List<Candidate>): DedupViews =
        DedupViews(_groups.value, candidates, enabled(scope))

    /** Candidate metadata for one list of songs, with the listener's pinned and picked versions marked. */
    fun candidates(songs: List<com.prism.music.data.model.Song>): List<Candidate> {
        val pinned = _pins.value
        return songs.map { s ->
            val pick = container.versions.of(s.id)
            Candidate(
                id = s.id,
                isVideo = s.isVideo,
                quality = qualityOf(s.id, s.isLocal, pick),
                pinned = s.id in pinned,
                manuallyPreferred = pick != null,
            )
        }
    }

    /** Songs currently known to the app, for labels and for choosing between a group's versions. */
    internal fun knownSongs(): Map<String, com.prism.music.data.model.Song> =
        (container.player.queue.value.map { it.song } + container.library.liked.value +
            container.localMusic.tracks.value.map { it.song }).associateBy { it.id }

    /** Candidates for every version of a group, including ones the current screen doesn't display. */
    private fun candidatesFor(ids: Collection<String>): List<Candidate> {
        val known = knownSongs()
        return ids.map { id ->
            val song = known[id]
            val pick = container.versions.of(id)
            Candidate(
                id = id,
                isVideo = song?.isVideo ?: false,
                quality = qualityOf(id, song?.isLocal ?: false, pick),
                pinned = id in _pins.value,
                manuallyPreferred = pick != null,
            )
        }
    }

    /** Quality of the version that actually plays: the matched file when there is one, otherwise unknown. */
    private fun qualityOf(id: String, local: Boolean, pick: String?): Quality {
        val file = container.localLossless.matched[pick?.takeIf { it.isNotBlank() } ?: id]
            ?: container.localLossless.matched[id]
            ?: return if (local) Quality.of("MP3", 0, 0, 0) else Quality()
        return Quality.of(file.format, file.bitrateKbps, file.sampleRate, 0)
    }

    // ---- Manual edits: every one persists as an individual pairwise statement or pin. ----

    private suspend fun edit(block: (RelationSet) -> Unit) = withContext(Dispatchers.IO) {
        val set = store.relationSet()
        block(set)
        store.saveRelations(set)
        refresh()
    }

    suspend fun confirm(a: String, b: String) = edit { it.confirm(a, b, Matcher.HIGH_MIN_VOTES.toDouble(), System.currentTimeMillis()) }

    suspend fun addManual(a: String, b: String) = edit { it.addManual(a, b, System.currentTimeMillis()) }

    /** Permanently separates two songs; no scan or manual merge may put them together again. */
    suspend fun separate(a: String, b: String) = edit { it.separate(a, b, System.currentTimeMillis()) }

    suspend fun undoSeparation(a: String, b: String) = edit { it.undoSeparation(a, b) }

    /** Removes one person's membership but leaves automatic relationships alone. */
    suspend fun removeMembership(a: String, b: String) = edit { it.removeManualMembership(a, b) }

    suspend fun mergeGroups(a: Collection<String>, b: Collection<String>) = edit { it.mergeGroups(a, b, System.currentTimeMillis()) }

    suspend fun move(id: String, from: Collection<String>, to: Collection<String>) = edit { it.move(id, from, to, System.currentTimeMillis()) }

    suspend fun setPin(songId: String, on: Boolean) = withContext(Dispatchers.IO) {
        store.setPin(songId, on, System.currentTimeMillis())
        refresh()
    }

    suspend fun setExclusion(key: String, on: Boolean) = withContext(Dispatchers.IO) {
        store.setExclusion(key, on, "listener's choice", System.currentTimeMillis())
        refresh()
    }
}
