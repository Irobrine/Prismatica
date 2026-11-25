package com.prism.music.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.data.friendlyMessage
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.Song
import com.prism.music.tr
import com.prism.music.ui.Load
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Playlists people made around a song (1.8.6's discovery): a search for its title and artist, kept to community
 * playlists. Opening one leaves the sheet for its page, as a search result does; [onBeforeOpen] lets the player close to
 * the mini player first, so the page is seen rather than the player it was opened over. Offline the search has nowhere
 * to go, and the sheet says so instead of spinning.
 */
@Composable
fun RelatedPlaylistsSheet(song: Song, onDismiss: () -> Unit, onBeforeOpen: (() -> Unit)? = null) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val state by produceState<Load<List<PlaylistItem>>>(Load.Loading, song.id) {
        value = if (!c.network.isOnline()) Load.Err(tr(R.string.you_re_offline))
        else withContext(Dispatchers.IO) {
            runCatching { c.ytm.relatedPlaylists(song) }
        }.fold({ Load.Ok(it) }, { Load.Err(it.friendlyMessage()) })
    }
    PrismSheet(onDismiss) { close ->
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Column(Modifier.padding(horizontal = 22.dp)) {
                Text(tr(R.string.playlists_with_this_song), style = MaterialTheme.typography.titleLarge)
                Text(
                    listOf(song.title, song.artistText).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            when (val s = state) {
                Load.Loading -> Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) {
                    PrismSpinner(Modifier.size(24.dp))
                }
                is Load.Err -> Text(
                    s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
                )
                is Load.Ok -> if (s.value.isEmpty()) Text(
                    tr(R.string.no_playlists_found), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
                ) else LazyColumn(Modifier.heightIn(max = 460.dp), contentPadding = PaddingValues(top = 10.dp, bottom = 18.dp)) {
                    items(s.value, key = { it.id }) { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable { close { onBeforeOpen?.invoke(); nav.go(Routes.playlist(p.id)) } }
                                .padding(horizontal = 16.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(p.thumbnail, Modifier.size(48.dp), shape = RoundedCornerShape(9.dp), size = 226, placeholderIcon = PrismIcons.QueueMusic)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (p.subtitle.isNotBlank()) Text(
                                    p.subtitle, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(PrismIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}
