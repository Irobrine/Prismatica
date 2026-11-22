package com.prism.music.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.prism.music.data.dedup.DedupScope
import com.prism.music.data.dedup.DedupSettingsLogic
import com.prism.music.data.dedup.DedupViews
import com.prism.music.data.dedup.DisplayRow
import com.prism.music.data.dedup.Grouping
import com.prism.music.data.dedup.Relation
import com.prism.music.data.model.Song
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer

/** One row of a deduplicated list: a lone song, or a group shown as its representative plus its alternatives. */
data class DedupLine(
    val song: Song,
    val groupId: String? = null,
    val alternatives: List<Song> = emptyList(),
    val representative: Boolean = false,
    val alternative: Boolean = false,
) {
    val isGroup: Boolean get() = groupId != null
    val count: Int get() = alternatives.size + 1
}

/**
 * Everything a collection screen needs to render and play a deduplicated list. Computed once per list change; when
 * the scope is off, [lines] is the songs unchanged and [hidden] is empty, so behavior and presentation are unchanged.
 */
class DedupListState(
    val lines: List<DedupLine>,
    /** Song ids a collection queue must skip (every alternative of every group). */
    val hidden: Set<String>,
    val enabled: Boolean,
    val groups: List<Grouping.Group>,
    val suggestions: List<Relation>,
) {
    /** The songs to hand to `playQueue`: representatives and ungrouped songs, in their displayed order. */
    fun queue(songs: List<Song>): List<Song> = if (!enabled) songs else songs.filterNot { it.id in hidden }
}

/** Builds the view for one list and one scope, reading the current groups and settings. */
@Composable
fun rememberDedupList(songs: List<Song>, scope: DedupScope, expanded: Set<String> = emptySet()): DedupListState {
    val c = LocalContainer.current
    val settings = LocalAppSettings.current
    val groups by c.dedup.groups.collectAsState()
    val suggestions by c.dedup.suggestions.collectAsState()
    val enabled = DedupSettingsLogic.enabled(scope, settings.behavior.dedup.enabled, DedupSettingsLogic.parse(settings.behavior.dedup.overrides))
    return remember(songs, scope, groups, suggestions, enabled, expanded) {
        if (!enabled) {
            DedupListState(songs.map(::DedupLine), emptySet(), false, groups, suggestions)
        } else {
            val byId = songs.associateBy { it.id }
            val views = DedupViews(groups, c.dedup.candidates(songs), enabled = true)
            val rows = views.rows(songs.map { it.id })
            val lines = ArrayList<DedupLine>()
            rows.forEach { row ->
                when (row) {
                    is DisplayRow.Single -> byId[row.id]?.let { lines += DedupLine(it) }
                    is DisplayRow.Group -> {
                        val rep = byId[row.representative] ?: return@forEach
                        val alternatives = row.alternatives.mapNotNull { byId[it] }
                        lines += DedupLine(rep, row.id, alternatives, representative = true)
                        // Alternatives stay in the library and the playlist. They are only shown when the group is
                        // opened here; playing one still plays that exact version.
                        if (row.id in expanded) alternatives.forEach { lines += DedupLine(it, row.id, alternative = true) }
                    }
                }
            }
            DedupListState(lines, views.hidden(songs.map { it.id }), true, groups, suggestions)
        }
    }
}
