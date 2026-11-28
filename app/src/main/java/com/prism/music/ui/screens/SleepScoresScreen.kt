package com.prism.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.analysis.SleepResult
import com.prism.music.data.model.Song
import com.prism.music.data.songsByIds
import com.prism.music.tr
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.SoundProfileSheet
import com.prism.music.ui.components.SubPage
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalUi
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.Dispatchers
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
    val changes by c.analysis.changes.collectAsState()
    var shown by remember { mutableStateOf<Song?>(null) }
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
    SubPage(tr(R.string.sleep_scores), bottomPadding, subtitle = tr(R.string.sleep_scores_info)) {
        if (rows.isEmpty()) item("none") {
            Text(
                tr(R.string.sleep_scores_none), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        items(rows, key = { it.song.id }) { r -> ScoredRow(r) { shown = r.song } }
    }
    shown?.let { song -> SoundProfileSheet(song) { shown = null } }
}

@Composable
private fun ScoredRow(r: Scored, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(r.song.thumbnail, Modifier.size(44.dp), LocalUi.current.songArt, placeholderIcon = PrismIcons.Song)
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
        }
        Spacer(Modifier.width(10.dp))
        // The score, with the same bar the Sound profile draws.
        Column(horizontalAlignment = Alignment.End) {
            Text("${r.score}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(4.dp))
            ScoreBar(r.score)
        }
    }
}

@Composable
private fun ScoreBar(score: Int) {
    Box(Modifier.width(56.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth((score / 100f).coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary))
    }
}
