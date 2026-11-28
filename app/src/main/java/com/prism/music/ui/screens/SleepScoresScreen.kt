package com.prism.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.analysis.SleepResult
import com.prism.music.data.model.Song
import com.prism.music.data.ScoreMark
import com.prism.music.data.songsByIds
import com.prism.music.tr
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.components.SoundProfileSheet
import com.prism.music.ui.components.SubPage
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalUi
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A song's score as this screen lists it, with what it's made of (the same figures the Sound profile explains). */
private class Scored(val song: Song, val score: Int, val result: SleepResult)

/**
 * Settings → Sound analysis → "Sleep scores": every song analysed, calmest first, each with its score out of 100 and
 * the factor that helps it most (or holds it back). A tap opens the song's Sound profile, where the whole score, its
 * factors, the figures it was read from and the listener's own vote are. Scores follow the listener's weights (vocals
 * count or not), their votes and what they skip while the sleep timer runs, so they're worked out here, not read from a
 * saved column.
 */
@Composable
fun SleepScoresScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // A preview of its own: the queue isn't touched, the main player steps aside while it plays.
    val preview = rememberSongPreview()
    // The listener's own verdict while tuning: "High"/"Low" per row, exported with the scores, never used by them.
    val marks by c.scoreMarks.marks.collectAsState()
    val changes by c.analysis.changes.collectAsState()
    var shown by remember { mutableStateOf<Song?>(null) }
    // Every analysed song as CSV, for tuning the score on a real library: scores, each factor and its weight, and the
    // figures behind them. Shared, never uploaded.
    fun export() = scope.launch {
        runCatching {
            val text = withContext(Dispatchers.IO) { com.prism.music.analysis.SleepScoresExport.csv(c) }
            val dir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
            val file = java.io.File(dir, "sleep-scores.csv").apply { writeText(text) }
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.share", file)
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/csv")
                .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(android.content.Intent.createChooser(send, tr(R.string.analysis_export)))
        }.onFailure {
            android.widget.Toast.makeText(context, tr(R.string.analysis_export_failed), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val rows by produceState(emptyList<Scored>(), changes) {
        value = withContext(Dispatchers.IO) {
            c.analysis.load()
            val ids = c.analysis.all().map { it.id }.distinct()
            val named = if (ids.isEmpty()) emptyMap() else runCatching { c.songsByIds(ids) }.getOrDefault(emptyMap())
            ids.mapNotNull { id ->
                val song = named[id] ?: return@mapNotNull null
                val result = c.analysis.sleepResult(id) ?: return@mapNotNull null
                Scored(song, result.score, result)
            }.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.song.title.lowercase() })
        }
    }
    Box(Modifier.fillMaxSize()) {
        SubPage(
            // Room under the list for the preview bar, so the last row can still be reached while it's up.
            tr(R.string.sleep_scores), bottomPadding + if (preview.song != null) 150.dp else 0.dp,
            subtitle = tr(R.string.sleep_scores_info),
            actions = { PrismTextButton(onClick = { export() }) { Text(tr(R.string.analysis_export)) } },
        ) {
            if (rows.isEmpty()) item("none") {
                Text(
                    tr(R.string.sleep_scores_none), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            items(rows, key = { it.song.id }) { r ->
                ScoredRow(
                    r, onPreview = { preview.open(r.song) }, onClick = { shown = r.song },
                    mark = marks[r.song.id], onMark = { c.scoreMarks.toggle(r.song.id, it) },
                )
            }
        }
        // The preview sits over the list's end: score and sound side by side, its own controls under it.
        if (preview.song != null) SongPreviewBar(
            preview, score = rows.firstOrNull { it.song.id == preview.song?.id }?.score,
            // Above the dock, the navigation bar and the mini player the page already leaves room for.
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding),
        )
    }
    shown?.let { song -> SoundProfileSheet(song) { shown = null } }
}

@Composable
private fun ScoredRow(
    r: Scored,
    onPreview: () -> Unit,
    onClick: () -> Unit,
    mark: ScoreMark? = null,
    onMark: (ScoreMark) -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The app's own cover lookup: a file's cover from the phone's scan (the songs table's row may have none), or one
        // picked for the song or its album — not just the song's own thumbnail, which a scanned file often doesn't carry.
        Artwork(LocalContainer.current.covers.art(r.song), Modifier.size(44.dp), LocalUi.current.songArt, placeholderIcon = PrismIcons.Song)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // What moves the score most: the factor it's lowest on, or its best one when nothing holds it back.
            val against = r.result.against(1)
            val line = against.firstOrNull()?.let { p -> tr(R.string.sleep_holds_back) + ": " + tr(p.factor.label) + " " + p.detail }
                ?: r.result.forIt(1).firstOrNull()?.let { p -> tr(R.string.sleep_helps) + ": " + tr(p.factor.label) + " " + p.detail }
            Text(
                listOfNotNull(r.song.artistText.ifBlank { null }, line).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            // Tuning aids: say the score is wrong in one direction. They only reach the export.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MarkPill(tr(R.string.analysis_score_high), mark == ScoreMark.HIGH) { onMark(ScoreMark.HIGH) }
                MarkPill(tr(R.string.analysis_score_low), mark == ScoreMark.LOW) { onMark(ScoreMark.LOW) }
            }
        }
        // Hear it without leaving the list: the queue is untouched, and a tap on the row still opens the score's reasons.
        IconButton(onPreview) {
            Icon(PrismIcons.Play, tr(R.string.analysis_preview), Modifier.size(22.dp))
        }
        Spacer(Modifier.width(4.dp))
        // The score, with the same bar the Sound profile draws.
        Column(horizontalAlignment = Alignment.End) {
            Text("${r.score}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(4.dp))
            ScoreBar(r.score)
        }
    }
}

/** A small pill for a tuning mark ("High" / "Low"), filled when it's the one set. Debug only: it reaches the export. */
@Composable
private fun MarkPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = fg, modifier = Modifier.padding(vertical = 2.dp), maxLines = 1)
    }
}

@Composable
private fun ScoreBar(score: Int) {
    Box(Modifier.width(56.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth((score / 100f).coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary))
    }
}
