package com.prism.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.data.dedup.Relation
import com.prism.music.data.model.Song
import com.prism.music.tr
import com.prism.music.ui.components.PrismButton
import com.prism.music.ui.components.PrismSheet
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.launch

/**
 * The detail view of duplicate hiding: the groups with their alternatives and their metadata, the borderline matches
 * waiting for a decision, and the manual scan with its progress. Nothing here edits a song, a file or a playlist: the
 * buttons only write Prism's own pairwise statements and pins.
 */
@Composable
internal fun DedupSheet(onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val groups by c.dedup.groups.collectAsState()
    val suggestions by c.dedup.suggestions.collectAsState()
    val pins by c.dedup.pins.collectAsState()
    val relations by c.dedup.relations.collectAsState()
    val progress by c.dedup.scanner.progress.collectAsState()
    val running by c.dedup.scanner.running.collectAsState()
    val outdated by c.dedup.scanner.indexOutdated.collectAsState()
    // The groups keep stable ids, so a pair may name a song that is not on the current screen. Look every id up in the
    // library as well as the in-memory lists, so the menu shows titles rather than raw video/local ids.
    val wanted = remember(groups, suggestions) {
        (groups.flatMap { it.members } + suggestions.flatMap { listOf(it.a, it.b) }).distinct()
    }
    val fromLibrary by produceState(emptyMap<String, Song>(), wanted) {
        value = if (wanted.isEmpty()) emptyMap() else runCatching {
            // Chunked: older Androids' SQLite caps a statement's bound parameters at 999, and a big library's groups
            // can name more songs than that.
            wanted.chunked(500).flatMap { c.db.songs().getAll(it) }.associate { it.id to it.toSong() }
        }.getOrDefault(emptyMap())
    }
    val known = remember(fromLibrary) {
        fromLibrary + c.dedup.knownSongs() +
            c.downloadState.value.values.mapNotNull { it.song }.associateBy { it.id }
    }
    fun titleOf(id: String): String = known[id]?.title?.takeIf { it.isNotBlank() } ?: tr(R.string.unknown_song)
    var removing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var moving by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf<String?>(null) }
    var merging by remember { mutableStateOf<String?>(null) }
    fun quality(id: String): String {
        val file = c.localLossless.matched[id] ?: return ""
        return listOfNotNull(
            file.format.takeIf { it.isNotBlank() },
            file.bitrateKbps.takeIf { it > 0 }?.let { tr(R.string.dedup_kbps, it) },
            file.sampleRate.takeIf { it > 0 }?.let { tr(R.string.dedup_khz, it / 1000) },
        ).joinToString(" · ")
    }

    PrismSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(bottom = 16.dp).testTag("dedupSheet")) {
            Text(tr(R.string.dedup_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 20.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrismButton(onClick = {
                    c.dedup.scanner.start()
                    com.prism.music.playback.DedupScanService.start(c.app)
                }) { Text(tr(R.string.dedup_scan_now)) }
                if (running) PrismTextButton(onClick = { c.dedup.scanner.stop() }) { Text(tr(R.string.stop)) }
            }
            progress?.let { (done, total) -> Text(tr(R.string.dedup_scan_progress, done, total),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp)) }
            if (outdated && !running) Text(tr(R.string.dedup_index_outdated),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))

            LazyColumn(Modifier.weight(1f, fill = false)) {
                if (groups.isEmpty() && suggestions.isEmpty()) item {
                    Text(tr(R.string.no_song_is_here_twice), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(20.dp))
                }
                groups.forEach { group ->
                    item("g:${group.id}") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                            Text(tr(R.string.dedup_versions, group.members.size), style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary)
                            group.members.sortedBy { it != representativeOf(group.members, pins, known) }.forEach { id ->
                                val song = known[id]
                                val isRep = id == representativeOf(group.members, pins, known)
                                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(titleOf(id), style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isRep) FontWeight.Bold else FontWeight.Normal)
                                        val warned = relations.any { it.involves(id) && (it.changedA || it.changedB) }
                                        val meta = listOfNotNull(label(song, isRep).takeIf { it.isNotBlank() }, quality(id).takeIf { it.isNotBlank() })
                                        Text(
                                            meta.joinToString(" · ") + if (warned) " · " + tr(R.string.dedup_changed_warning) else "",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (isRep) PrismTextButton(onClick = { scope.launch { c.dedup.setPin(id, id !in pins) } }) {
                                        Text(tr(R.string.dedup_pin))
                                    } else {
                                        PrismTextButton(onClick = { removing = representativeOf(group.members, pins, known) to id }) {
                                            Text(tr(R.string.dedup_remove))
                                        }
                                        PrismTextButton(onClick = { moving = id }) { Text(tr(R.string.dedup_move)) }
                                        if (song?.isLocal == true) {
                                            val uri = runCatching { c.localMusic.uriFor(id).toString() }.getOrNull()
                                            if (uri != null) PrismTextButton(onClick = { scope.launch { c.dedup.setExclusion(uri, true) } }) {
                                                Text(tr(R.string.dedup_exclude))
                                            }
                                        }
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PrismTextButton(onClick = { adding = group.id }) { Text(tr(R.string.dedup_add)) }
                                PrismTextButton(onClick = { merging = group.id }) { Text(tr(R.string.dedup_merge)) }
                            }
                        }
                    }
                }
                suggestions.forEach { relation ->
                    item("s:${relation.pair.first}:${relation.pair.second}") {
                        SuggestionRow(relation, ::titleOf,
                            onConfirm = { scope.launch { c.dedup.confirm(it.a, it.b) } },
                            onSeparate = { scope.launch { c.dedup.separate(it.a, it.b) } },
                            onCompare = { r ->
                                val pair = listOfNotNull(known[r.a], known[r.b])
                                if (pair.size == 2) c.player.playQueue(pair, 0,
                                    com.prism.music.playback.QueueSource(com.prism.music.playback.QueueKind.SINGLE, tr(R.string.dedup_compare)))
                            })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                PrismTextButton(onClick = onDismiss) { Text(tr(R.string.close)) }
            }
        }
    }

    removing?.let { (a, b) -> AlertDialog(
        onDismissRequest = { removing = null },
        text = { Text(tr(R.string.dedup_remove)) },
        confirmButton = { PrismTextButton(onClick = { scope.launch { c.dedup.separate(a, b) }; removing = null }) { Text(tr(R.string.dedup_remove_permanently)) } },
        dismissButton = { PrismTextButton(onClick = { scope.launch { c.dedup.removeMembership(a, b) }; removing = null }) { Text(tr(R.string.dedup_remove_membership)) } },
    ) }

    moving?.let { id -> AlertDialog(
        onDismissRequest = { moving = null },
        text = {
            Column {
                Text(tr(R.string.dedup_move), style = MaterialTheme.typography.titleMedium)
                groups.filter { target -> id !in target.members }.forEach { target ->
                    PrismTextButton(onClick = {
                        val from = groups.firstOrNull { id in it.members }?.members.orEmpty()
                        scope.launch { c.dedup.move(id, from, target.members) }
                        moving = null
                    }) { Text(target.members.minOrNull()?.let(::titleOf) ?: tr(R.string.unknown_song)) }
                }
            }
        },
        confirmButton = { PrismTextButton(onClick = { moving = null }) { Text(tr(R.string.cancel)) } },
    ) }

    adding?.let { groupId ->
        val members = groups.firstOrNull { it.id == groupId }?.members.orEmpty()
        AlertDialog(
            onDismissRequest = { adding = null },
            text = {
                Column {
                    Text(tr(R.string.dedup_add), style = MaterialTheme.typography.titleMedium)
                    known.values.filter { it.id !in members }.take(40).forEach { song ->
                        val other = groups.firstOrNull { song.id in it.members }
                        Text(song.title, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp, start = 12.dp))
                        if (other == null) PrismTextButton(onClick = {
                            val rep = representativeOf(members, pins, known)
                            scope.launch { c.dedup.addManual(rep, song.id) }
                            adding = null
                        }) { Text(tr(R.string.dedup_add)) }
                        else {
                            // Already in another group: a person chooses whether the groups merge or the track moves.
                            PrismTextButton(onClick = {
                                scope.launch { c.dedup.mergeGroups(members, other.members) }
                                adding = null
                            }) { Text(tr(R.string.dedup_merge)) }
                            PrismTextButton(onClick = {
                                scope.launch { c.dedup.move(song.id, other.members, members) }
                                adding = null
                            }) { Text(tr(R.string.dedup_move)) }
                        }
                    }
                }
            },
            confirmButton = { PrismTextButton(onClick = { adding = null }) { Text(tr(R.string.cancel)) } },
        )
    }

    merging?.let { groupId ->
        AlertDialog(
            onDismissRequest = { merging = null },
            text = {
                Column {
                    Text(tr(R.string.dedup_merge), style = MaterialTheme.typography.titleMedium)
                    groups.filter { it.id != groupId }.forEach { target ->
                        PrismTextButton(onClick = {
                            val mine = groups.firstOrNull { it.id == groupId }?.members.orEmpty()
                            scope.launch { c.dedup.mergeGroups(mine, target.members) }
                            merging = null
                        }) { Text(target.members.minOrNull()?.let(::titleOf) ?: tr(R.string.unknown_song)) }
                    }
                }
            },
            confirmButton = { PrismTextButton(onClick = { merging = null }) { Text(tr(R.string.cancel)) } },
        )
    }
}

/** Best available version, using the same priority the player uses (a pin first, then audio, then quality). */
private fun representativeOf(members: Set<String>, pins: Set<String>, known: Map<String, Song>): String =
    members.firstOrNull { it in pins }
        ?: members.firstOrNull { known[it]?.isVideo == false } ?: members.minOrNull() ?: ""

@Composable
private fun SuggestionRow(
    relation: Relation,
    titleOf: (String) -> String,
    onConfirm: (Relation) -> Unit,
    onSeparate: (Relation) -> Unit,
    onCompare: (Relation) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(listOf(titleOf(relation.a), titleOf(relation.b)).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium)
            Text(tr(R.string.dedup_suggested), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PrismTextButton(onClick = { onCompare(relation) }) { Text(tr(R.string.dedup_compare)) }
        PrismTextButton(onClick = { onSeparate(relation) }) { Text(tr(R.string.dedup_separate)) }
        PrismButton(onClick = { onConfirm(relation) }) { Text(tr(R.string.dedup_confirm)) }
    }
}

private fun label(song: Song?, representative: Boolean): String = when {
    song == null -> ""
    representative -> tr(R.string.dedup_representative)
    song.isVideo -> tr(R.string.dedup_group)
    else -> song.artistText
}
