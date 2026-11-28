package com.prism.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.analysis.FactorPart
import com.prism.music.analysis.SleepResult
import com.prism.music.analysis.TagCategory
import com.prism.music.analysis.TagStatus
import com.prism.music.analysis.TagVocabulary
import com.prism.music.data.db.SongAutoTagEntity
import com.prism.music.data.model.Song
import com.prism.music.data.songsByIds
import com.prism.music.tr
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** A machine tag as the listener reads it: Prism's genre names translated, styles as the world writes them. */
fun tagLabel(key: String): String {
    if (key == TagVocabulary.INSTRUMENTAL) return tr(R.string.tag_instrumental)
    if (key == TagVocabulary.ACOUSTIC) return tr(R.string.tag_acoustic)
    val d = TagVocabulary.def(key) ?: return key.substringAfter(':')
    return when {
        d.label != null -> tr(d.label)
        d.category == TagCategory.GENRE -> com.prism.music.data.meta.Genres.shown(d.english)
        else -> d.english
    }
}

/**
 * A song's sound analysis (from its ⋯ menu): its sleep score and the factors behind it, the listener's say on it ("Good
 * for sleep" / "Not for sleep"), its machine tags by kind (uncertain ones to accept or reject; any to add to their own
 * tags), its measured figures, and how and when it was analysed. A song not analysed yet can be analysed from here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SoundProfileSheet(song: Song, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val changes by c.analysis.changes.collectAsState()
    val formId = remember(song.id) { runCatching { c.loudnessIndex.formId(song.id, null) }.getOrDefault(song.id) }
    // The analysis is filed under the recording heard (its form) and the song it played for.
    val id = remember(changes) { listOf(song.id, formId).firstOrNull { c.analysis.has(it) } }
    var analysing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    // A scan of the recording heard, for the analysis: the same decode the player's figure comes from.
    fun analyseNow() {
        analysing = true
        failed = false
        c.scope.launch {
            runCatching {
                c.mixTempo.measure(
                    formId, network = c.measureSongs.network(false), edges = false,
                    sourceSongId = song.id.takeIf { it != formId }, analyse = true,
                )
            }
            analysing = false
            failed = !c.analysis.has(formId) && !c.analysis.has(song.id)
        }
    }
    PrismSheet(onDismiss) { _ ->
        val result: SleepResult? = remember(id, changes) { id?.let { c.analysis.sleepResult(it) } }
        // Analysed by older descriptors or an older tagger: what's shown is from before, and a scan redos it.
        val outOfDate = remember(id, changes) { id != null && c.analysis.stale(id) }
        val provenance by produceState<com.prism.music.data.db.SongAnalysisEntity?>(null, id, changes) {
            value = id?.let { withContext(Dispatchers.IO) { runCatching { c.analysis.provenance(it) }.getOrNull() } }
        }
        val tags by remember(id) { id?.let { c.db.analysis().tagsFlow(it) } ?: flowOf(emptyList()) }.collectAsState(emptyList())
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text(tr(R.string.sound_profile), style = MaterialTheme.typography.titleLarge)
                Text(listOf(song.title, song.artistText).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (id == null || result == null) {
                Text(
                    when {
                        analysing -> tr(R.string.analysis_analysing)
                        failed -> tr(R.string.analysis_couldnt)
                        else -> tr(R.string.analysis_not_yet)
                    },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (analysing) PrismSpinner(Modifier.size(22.dp), stroke = 2.dp)
                else PrismFilledTonalButton(onClick = ::analyseNow) { Text(tr(R.string.analysis_now)) }
                return@Column
            }
            SleepScoreBlock(result)
            if (outOfDate) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr(R.string.analysis_out_of_date), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                if (analysing) PrismSpinner(Modifier.size(18.dp), stroke = 2.dp)
                else PrismTextButton(onClick = ::analyseNow) { Text(tr(R.string.analysis_reanalyse)) }
            }
            if (outOfDate && failed) Text(
                tr(R.string.analysis_couldnt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
            val vote = remember(changes) { c.analysis.vote(id) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrismChip(vote > 0, { scope.launch { c.analysis.setVote(id, if (vote > 0) 0 else 1) } }, tr(R.string.sleep_good_for_sleep), icon = PrismIcons.Moon)
                PrismChip(vote < 0, { scope.launch { c.analysis.setVote(id, if (vote < 0) 0 else -1) } }, tr(R.string.sleep_not_for_sleep), icon = PrismIcons.ThumbDown)
            }
            // Its measured figures, in units (the same in every language).
            c.analysis.entry(id)?.let { e ->
                val facts = com.prism.music.analysis.SoundFacts.line(e)
                if (facts.isNotBlank()) Text(facts, style = MaterialTheme.typography.bodyMedium)
            }
            TagsBlock(tags, onStatus = { tag, status -> scope.launch { c.analysis.setTagStatus(id, tag, status) } }) { tag ->
                scope.launch {
                    val n = c.tags.add(tagLabel(tag), listOf(song))
                    if (n > 0) android.widget.Toast.makeText(context, tr(R.string.analysis_added_to_tags), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            if (provenance?.taggerId == null) {
                Text(tr(R.string.analysis_tags_need_model), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            provenance?.let { p ->
                val at = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(p.analyzedAt))
                val tagger = p.taggerId?.let { com.prism.music.analysis.SoundFacts.tagger(it, p.taggerVersion) } ?: tr(R.string.analysis_no_tagger)
                Text(tr(R.string.analysis_provenance, at, p.algorithmVersion, tagger), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SleepScoreBlock(r: SleepResult) {
    var all by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow(tr(R.string.sleep_score))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${r.score}", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(tr(R.string.sleep_score_out_of, r.score), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }
        Bar(r.score / 100f)
        if (r.capped) Text(tr(R.string.sleep_capped), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        val shift = r.personal + r.vote
        if (shift != 0) Text(tr(R.string.sleep_your_feedback, (if (shift > 0) "+" else "") + shift), style = MaterialTheme.typography.bodyMedium)
        if (r.completeness < 0.9f) Text(tr(R.string.analysis_partial), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // With Normalize on, loudness's pull is cut (the player brings every song to one level): said, so the score's
        // figures and its weights don't look like they disagree.
        if (r.parts.any { it.factor == com.prism.music.analysis.SleepFactor.ENERGY && it.weight < com.prism.music.analysis.SleepFactor.ENERGY.weight }) {
            Text(tr(R.string.sleep_normalized_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val helps = r.forIt()
        if (helps.isNotEmpty()) {
            Eyebrow(tr(R.string.sleep_helps))
            helps.forEach { FactorLine(it) }
        }
        val against = r.against()
        if (against.isNotEmpty()) {
            Eyebrow(tr(R.string.sleep_holds_back))
            against.forEach { FactorLine(it) }
        }
        PrismTextButton(onClick = { all = !all }) { Text(tr(R.string.sleep_all_factors)) }
        if (all) r.parts.filter { it.weight > 0f }.forEach { FactorLine(it, bar = true) }
    }
}

@Composable
private fun FactorLine(p: FactorPart, bar: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr(p.factor.label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(p.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (bar) Bar(p.calm)
    }
}

@Composable
private fun Bar(fraction: Float) {
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary))
    }
}

/**
 * The song's machine tags by kind. Sure ones are the song's (a ✕ rejects one); suggested ones ask for a ✓ or ✕; an
 * accepted or sure one can be added to the listener's own tags (+). Rejected ones aren't shown again.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsBlock(tags: List<SongAutoTagEntity>, onStatus: (String, TagStatus) -> Unit, onAddToMine: (String) -> Unit) {
    val shown = tags.filter { it.status != TagStatus.REJECTED.ordinal }
    if (shown.isEmpty()) return
    val byCategory = shown.groupBy { runCatching { TagCategory.valueOf(it.category) }.getOrNull() }.filterKeys { it != null }
    for (cat in TagCategory.entries) {
        val list = byCategory[cat]?.sortedByDescending { it.confidence } ?: continue
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Eyebrow(tr(cat.label))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                list.forEach { t ->
                    val status = TagStatus.entries.getOrElse(t.status) { TagStatus.AUTO }
                    val suggested = status == TagStatus.REVIEW
                    Row(
                        Modifier.clip(RoundedCornerShape(16.dp)).background(
                            if (suggested) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.secondaryContainer,
                        ).padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            tagLabel(t.tag) + " · ${(t.confidence * 100).roundToInt()}%" + if (suggested) " · " + tr(R.string.analysis_suggested) else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (suggested) IconButton({ onStatus(t.tag, TagStatus.ACCEPTED) }, Modifier.size(32.dp)) {
                            Icon(PrismIcons.Check, tr(R.string.analysis_accept), Modifier.size(16.dp))
                        } else IconButton({ onAddToMine(t.tag) }, Modifier.size(32.dp)) {
                            Icon(PrismIcons.Add, tr(R.string.analysis_add_to_my_tags), Modifier.size(16.dp))
                        }
                        IconButton({ onStatus(t.tag, TagStatus.REJECTED) }, Modifier.size(32.dp)) {
                            Icon(PrismIcons.Close, tr(R.string.analysis_reject), Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Every suggested tag across the library, most confident first, to accept or reject one by one (Settings → Sound
 * analysis → Review suggested tags).
 */
@Composable
fun TagReviewSheet(onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val changes by c.analysis.changes.collectAsState()
    PrismSheet(onDismiss) { _ ->
        val rows by produceState(emptyList<com.prism.music.data.db.TagReview>(), changes) {
            value = withContext(Dispatchers.IO) { runCatching { c.db.analysis().toReview(200) }.getOrDefault(emptyList()) }
        }
        // A file on the phone that was only ever analysed has no `songs` row, so the query's join has no name for it: the
        // phone's own list names it instead of its id ("local:1234").
        val named by produceState(emptyMap<String, com.prism.music.data.model.Song>(), rows) {
            value = withContext(Dispatchers.IO) {
                val missing = rows.filter { it.title == null }.map { it.songId }.distinct()
                if (missing.isEmpty()) emptyMap() else runCatching { c.songsByIds(missing) }.getOrDefault(emptyMap())
            }
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(tr(R.string.analysis_review_tags), style = MaterialTheme.typography.titleLarge)
            if (rows.isEmpty()) Text(tr(R.string.analysis_review_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            rows.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${tagLabel(r.tag)} · ${(r.confidence * 100).roundToInt()}%", style = MaterialTheme.typography.bodyLarge)
                        val song = named[r.songId]
                        val name = listOfNotNull(r.title ?: song?.title, r.artistName ?: song?.artistText).joinToString(" · ")
                        Text(name.ifBlank { r.songId }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    IconButton({ scope.launch { c.analysis.setTagStatus(r.songId, r.tag, TagStatus.ACCEPTED) } }) {
                        Icon(PrismIcons.Check, tr(R.string.analysis_accept))
                    }
                    IconButton({ scope.launch { c.analysis.setTagStatus(r.songId, r.tag, TagStatus.REJECTED) } }) {
                        Icon(PrismIcons.Close, tr(R.string.analysis_reject))
                    }
                }
            }
        }
    }
}
