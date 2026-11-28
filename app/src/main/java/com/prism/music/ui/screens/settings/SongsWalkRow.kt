package com.prism.music.ui.screens.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prism.music.AppContainer
import com.prism.music.R
import com.prism.music.data.MeasureSongs
import com.prism.music.data.model.Song
import com.prism.music.data.model.songCount
import com.prism.music.playback.AnalysisService
import com.prism.music.playback.MeasureService
import com.prism.music.tr
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.launch

/**
 * Which walk a [SongsWalkRow] runs: measuring songs' loudness for Normalize (1.1.3), or analysing their sound for the
 * sleep score, tags and similar songs (1.9). Both are the same walk ([MeasureSongs]) under their own name, their own
 * foreground service and their own words.
 */
enum class SongsWalk {
    MEASURE,
    ANALYSE,
    ;

    val title: Int get() = if (this == MEASURE) R.string.measure_songs_now else R.string.analysis_now
    val info: Int get() = if (this == MEASURE) R.string.measure_songs_now_info else R.string.analysis_now_info
    val running: Int get() = if (this == MEASURE) R.string.measuring_n_of_n else R.string.analysing_n_of_n
    val stopped: Int get() = if (this == MEASURE) R.string.measure_songs_stopped_at else R.string.analysis_stopped_at
    val couldnt: Int get() = if (this == MEASURE) R.string.measure_songs_couldnt_measure else R.string.analysis_couldnt_some
    val dont: Int get() = if (this == MEASURE) R.string.measure_songs_dont_scan else R.string.analysis_dont
    val again: Int get() = if (this == MEASURE) R.string.measure_songs_scan_again else R.string.analysis_again
    val notDone: Int get() = if (this == MEASURE) R.string.measure_songs_not_scanned else R.string.analysis_not_done
    val onWifi: Int get() = if (this == MEASURE) R.string.on_wi_fi_songs_not_on_the_phone_are_measured_too else R.string.analysis_on_wi_fi

    fun work(c: AppContainer): MeasureSongs = if (this == MEASURE) c.measureSongs else c.analyseSongs

    /** The foreground service that keeps the walk going with Prism out of sight. */
    fun start(context: Context) = if (this == MEASURE) MeasureService.start(context) else AnalysisService.start(context)
}

/**
 * "Measure songs now" and "Analyse now": the walk at the listener's own word, on a set of songs of their choosing, one
 * song at a time ([MeasureSongs]): songs already done are left out, so a walk is only the work left.
 *
 * A tap opens the sheet of the four sets, each with how many of its songs are left (worked out as it opens: the three
 * on hand at once, the Library's once the database has answered). A pick starts the walk and the sheet goes; while it
 * runs the row says how far it is, and a tap stops it — the count is kept, and the next tap carries on from it rather
 * than starting the set over (the sheet is gone by then, so the row is the one place for both).
 *
 * A song a walk walked but couldn't do (a stream that can't play, say) is said rather than finishing as though it had
 * been: the row names it, and the sheet lists it with "Try again" and "Don't scan". A kept song leaves the count until
 * the sheet's "Scan again" row puts it back.
 */
@Composable
fun SongsWalkRow(walk: SongsWalk, /** Said after the row's own line of help (what's analysed so far, say). */ infoExtra: String? = null) {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val work = walk.work(c)
    val running by work.running.collectAsState()
    val done by work.progress.collectAsState()
    val couldnt by work.couldnt.collectAsState()
    val title = tr(walk.title)
    // The sheet's row for songs an earlier version analysed (not a settings row of its own: kept out of the index's scan).
    val olderTitle = tr(R.string.analysis_older)
    var open by rememberSaveable(title, "songs-walk") { mutableStateOf(false) }
    // How many of each set's songs are left: empty until the sheet has counted them.
    var left by remember(title) { mutableStateOf<Map<MeasureScope, Int>>(emptyMap()) }
    // How many of them the listener has kept from being scanned (the sheet's last row puts them back).
    var kept by remember(title) { mutableIntStateOf(0) }
    // Songs an earlier version of the analysis made, whose scores and tags are from before: redone by their own row.
    var older by remember(title) { mutableStateOf<List<String>>(emptyList()) }
    // The counts are taken again after a skip or a let-back, with the sheet open through both.
    var countedAt by remember(title) { mutableIntStateOf(0) }
    // The songs the last walk couldn't do, as the library names them.
    val failed by produceState(emptyMap<String, Song>(), couldnt) {
        val ids = couldnt.orEmpty().map { it.second }.distinct()
        value = if (ids.isEmpty()) emptyMap() else runCatching {
            // Chunked: a whole set walked with no network can name more songs than one statement may bind.
            ids.chunked(500).flatMap { c.db.songs().getAll(it) }.associate { it.id to it.toSong() }
        }.getOrDefault(emptyMap())
    }
    fun start(pairs: List<Pair<String, String>>) {
        val network = work.network(s.mixTempoOnCellular)
        work.start(pairs, network)
        // Kept going with Prism out of sight: a foreground service (the kind downloads use) and the budget's allowance
        // for it, rather than resting the walk until the screen comes back.
        walk.start(context)
        // Songs not on the phone wait for Wi-Fi unless mobile data is allowed for the scan: said so, as the scan's own row
        // on a smart playlist does.
        if (!network) Toast.makeText(context, tr(walk.onWifi), Toast.LENGTH_LONG).show()
    }
    val at = done
    LinkRow(
        title,
        value = when {
            at != null -> tr(if (running) walk.running else walk.stopped, at.first, at.second)
            couldnt != null -> tr(walk.couldnt, songCount(couldnt.orEmpty().size))
            else -> null
        },
        info = listOfNotNull(tr(walk.info), infoExtra).joinToString("\n"), action = true,
    ) {
        when {
            running -> work.stop()
            // A stopped walk carries on where it stopped: its count is kept, and what it counted isn't walked again.
            done != null -> work.resume()
            else -> open = true
        }
    }
    if (open) {
        LaunchedEffect(open, countedAt) {
            // Analysing counts what's analysed, which the analysis keeps in memory: read before counting.
            if (walk == SongsWalk.ANALYSE) c.analysis.load()
            left = emptyMap()
            // The sets Prism has to hand first, so the sheet fills as it opens; the Library's needs the database read.
            val sets = MeasureScope.entries.filter { it != MeasureScope.LIBRARY } + MeasureScope.LIBRARY
            val all = LinkedHashSet<String>()
            for (set in sets) {
                val ids = set.ids(c)
                all += ids
                left = left + (set to work.pending(ids).size)
            }
            kept = work.kept(all.toList())
            older = if (walk == SongsWalk.ANALYSE) runCatching {
                // Only songs the library knows: an analysis whose song is gone has no page to show it on.
                c.analysis.oldIds().chunked(500).flatMap { c.db.songs().getAll(it) }.map { it.id }
            }.getOrDefault(emptyList()) else emptyList()
        }
        RowsSheet(title, { open = false }) {
            // The last walk's songs it couldn't do: said, with a try at each again and a way to keep one from being
            // scanned at all (its stream can't play, so no figure can ever be had).
            couldnt?.takeIf { it.isNotEmpty() }?.let { missed ->
                Text(
                    tr(walk.couldnt, songCount(missed.size)),
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
                )
                missed.forEach { (form, id) ->
                    val song = failed[id]
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(song?.title ?: id, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                song?.artistText.orEmpty(), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        PrismTextButton(onClick = { open = false; start(listOf(form to id)) }) { Text(tr(R.string.try_again)) }
                        PrismTextButton(onClick = { work.skipFailed(form, id); countedAt++ }) { Text(tr(walk.dont)) }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            // The songs whose analysis an earlier version made, in one row: a version's change is redone without hunting
            // for the set each song happens to be in (liked, played, downloaded, a file).
            if (walk == SongsWalk.ANALYSE && older.isNotEmpty()) {
                LinkRow(olderTitle, value = songCount(older.size), action = true) {
                    open = false
                    scope.launch { start(work.pending(older)) }
                }
            }
            MeasureScope.entries.forEach { set ->
                val count = left[set]
                LinkRow(
                    set.title,
                    value = count?.let { songCount(it) },
                    // A set with nothing left says so (0 songs) and does nothing.
                    enabled = count != 0,
                    action = true,
                ) {
                    open = false
                    scope.launch { start(work.pending(set.ids(c))) }
                }
            }
            // Songs kept from being scanned aren't work, so the sets count them out; this puts them back.
            if (kept > 0) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 2.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr(walk.notDone, songCount(kept)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    PrismTextButton(onClick = { work.scanAgain(); countedAt++ }) { Text(tr(walk.again)) }
                }
            }
        }
    }
}

/**
 * The sets of songs a walk runs through, as its sheet lists them, each under the name it goes by elsewhere in Prism:
 * the listener's own songs ([com.prism.music.data.SmartQuery.KNOWN]'s: liked, played, tagged), their downloads, the
 * music files on the phone, and their liked songs.
 */
enum class MeasureScope(val title: String) {
    LIBRARY(tr(R.string.library)),
    DOWNLOADED(tr(R.string.downloaded)),
    PHONE(tr(R.string.on_this_phone)),
    LIKED(tr(R.string.liked_songs));

    /** The set's songs' ids now: the Library's read from the database (off the main thread), the others as Prism has them. */
    suspend fun ids(c: AppContainer): List<String> = when (this) {
        LIBRARY -> c.smart.knownIds()
        DOWNLOADED -> c.downloads.completedSongs().map { it.id }
        PHONE -> c.localMusic.tracks.value.map { it.song.id }
        LIKED -> c.library.likedIds.value.toList()
    }
}
