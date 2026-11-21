package com.prism.music.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.data.LibrarySearch
import com.prism.music.data.PlaylistCovers
import com.prism.music.data.model.Song
import com.prism.music.tr
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.PrismSheet
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.components.SearchField
import com.prism.music.ui.components.SheetAction
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons

/**
 * The track the sleep timer plays once it has finished, picked from what's already on the phone: downloaded songs and
 * local files. Search narrows both by title, artist or album, and "Off" leaves the timer stopping at silence.
 */
@Composable
fun SleepAfterTrackPicker(chosenId: String?, onDismiss: () -> Unit, onPick: (Song?) -> Unit) {
    val c = LocalContainer.current
    val downloads by c.downloads.downloads.collectAsState()
    val files by c.localMusic.tracks.collectAsState()
    var query by remember { mutableStateOf("") }
    val downloadedSongs = remember(downloads) { c.downloads.completedSongs() }
    val fileSongs = remember(files) { files.map { it.song } }
    val all = remember(downloadedSongs, fileSongs) { (downloadedSongs + fileSongs).distinctBy { it.id } }
    val downloadedIds = remember(downloadedSongs) { downloadedSongs.mapTo(HashSet()) { it.id } }
    val words = remember(query) { LibrarySearch.norm(query).split(' ').filter { it.isNotBlank() } }
    val shown = remember(all, words) {
        if (words.isEmpty()) all else all.filter { s ->
            val hay = LibrarySearch.norm("${s.title} ${s.artistText} ${s.album?.title.orEmpty()}")
            words.all { it in hay }
        }
    }
    val cap = LocalConfiguration.current.screenHeightDp.dp * 0.78f
    PrismSheet(onDismiss, dragHandle = null) { close ->
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tr(R.string.choose_a_track), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            PrismTextButton(onClick = { close {} }) { Text(tr(R.string.cancel)) }
        }
        SearchField(query, tr(R.string.search), { query = it }, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = cap)) {
            item("off") {
                SheetAction(PrismIcons.Close, tr(R.string.off)) { onPick(null); close {} }
            }
            if (shown.isEmpty()) item("empty") {
                Text(
                    tr(R.string.nothing_found), Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(shown, key = { it.id }) { song ->
                PickerSongRow(
                    song = song,
                    chosen = song.id == chosenId,
                    source = if (song.id in downloadedIds) tr(R.string.downloaded) else tr(R.string.on_this_phone),
                ) { onPick(song); close {} }
            }
        }
        Spacer(Modifier.size(8.dp))
    }
}

/** One song in [SleepAfterTrackPicker]: what plays, its artist, where it is, and a ✓ on the one already chosen. */
@Composable
private fun PickerSongRow(song: Song, chosen: Boolean, source: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(PlaylistCovers.artOf(song), Modifier.size(46.dp), size = 120, placeholderIcon = PrismIcons.Song)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title, style = MaterialTheme.typography.bodyLarge,
                color = if (chosen) scheme.primary else scheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(song.artistText.takeIf { it.isNotBlank() }, source).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (chosen) Icon(
            PrismIcons.Check, null, Modifier.padding(start = 8.dp).size(20.dp), tint = scheme.onSurface,
        )
    }
}
