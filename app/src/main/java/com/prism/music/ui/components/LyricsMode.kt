package com.prism.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.data.lyrics.SavedLyricsSearch
import com.prism.music.tr
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons

/**
 * A search's lyrics mode: the songs whose saved lyrics have what's typed are found too, beside the usual matches.
 * The toggle shows only while the field is being typed in, starts off, and goes off again once the search is made
 * (the field let go of, or searched from the keyboard). The search made with it on keeps its lyric matches until
 * the text is changed, so the results don't change under the listener the moment the keyboard goes away.
 */
@Stable
class LyricsMode {
    /** The toggle, while the field is being typed in. */
    var on by mutableStateOf(false)
        private set
    /** The text searched with the toggle on, whose results keep their lyric matches. */
    private var searched by mutableStateOf<String?>(null)

    /** Whether [query]'s results include the lyrics. */
    fun active(query: String): Boolean = query.isNotBlank() && (on || searched == query.trim())

    fun toggle(query: String) {
        on = !on
        // Turned off by hand: the results now are the usual ones only.
        if (!on && searched == query.trim()) searched = null
    }

    /** The field let go of (or the search made): the toggle goes off, the search made with it stays a lyrics search. */
    fun left(query: String) {
        if (on) searched = query.trim().takeIf { it.isNotEmpty() }
        else if (searched != query.trim()) searched = null
        on = false
    }
}

@Composable
fun rememberLyricsMode(vararg keys: Any?): LyricsMode = remember(*keys) { LyricsMode() }

/** The line each song was found by in lyrics mode, by song id: a [SongRow] in a list that provides it shows its line. */
val LocalLyricMatches = androidx.compose.runtime.compositionLocalOf<Map<String, String>> { emptyMap() }

/**
 * Lyrics mode's matches for [query] among [among] (song ids; null: every saved lyrics), best first; empty while the
 * mode is off. Looked for again as more lyrics in other scripts are spelled in Latin letters.
 */
@Composable
fun rememberLyricMatches(query: String, mode: LyricsMode, among: Collection<String>?): List<SavedLyricsSearch.Match> {
    val c = LocalContainer.current
    val active = mode.active(query)
    val version by c.savedLyricsSearch.version.collectAsState()
    val matches by produceState(emptyList<SavedLyricsSearch.Match>(), query, active, among, version) {
        if (!active) { value = emptyList(); return@produceState }
        kotlinx.coroutines.delay(120) // typing on: one search per pause
        value = runCatching { c.savedLyricsSearch.find(query, among) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrDefault(emptyList())
    }
    return if (active) matches else emptyList()
}

/**
 * The lyric matches that aren't among the songs found by name ([byName]), best first, each with its line: what a list
 * adds after its usual results, and what [LocalLyricMatches] shows under them.
 */
fun lyricExtras(byName: List<com.prism.music.data.model.Song>, matches: List<SavedLyricsSearch.Match>): Map<String, String> {
    if (matches.isEmpty()) return emptyMap()
    val have = byName.mapTo(HashSet()) { it.id }
    return matches.filter { it.songId !in have }.associateTo(LinkedHashMap()) { it.songId to it.line }
}

/** The toggle in a search field, while it's being typed in. */
@Composable
fun LyricsModeToggle(mode: LyricsMode, query: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val on = mode.on
    val description = tr(R.string.search_lyrics_mode_description)
    Row(
        modifier.height(32.dp).clip(CircleShape)
            .background(if (on) scheme.primary else scheme.onSurface.copy(alpha = 0.06f))
            .then(if (on) Modifier else Modifier.border(1.dp, scheme.outlineVariant, CircleShape))
            .toggleable(on, role = Role.Switch) { mode.toggle(query) }
            .semantics { contentDescription = description }
            .testTag("lyricsMode")
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (on) scheme.onPrimary else scheme.onSurfaceVariant
        Icon(PrismIcons.Lyrics, null, Modifier.size(16.dp), tint = ink)
        Spacer(Modifier.width(5.dp))
        Text(tr(R.string.search_lyrics_mode), style = MaterialTheme.typography.labelMedium, color = ink, maxLines = 1)
    }
}
