package com.prism.music.ui.player

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.prism.music.AppContainer
import com.prism.music.R
import com.prism.music.data.MoreFrom
import com.prism.music.data.model.Song
import com.prism.music.data.model.songCount
import com.prism.music.data.moreFromSongs
import com.prism.music.tr
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A "More from this artist/album" tap (1.8.6): picks a shuffled handful of the artist's songs (or the album's) and puts
 * them right after the one playing, then says how many went in. Offline, the handful comes from Downloads and Local
 * files only; when there aren't as many as were asked for, all there are go in.
 *
 * The work runs on the app's own scope, not the screen's: tapping it in the ⋯ menu closes that menu, and a scope tied to
 * the menu would be cancelled before the songs arrived.
 */
fun addMoreFrom(c: AppContainer, song: Song, from: MoreFrom) {
    val count = c.settings.current.view.parts.moreFromCount
    c.scope.launch(Dispatchers.Main) {
        val picked = try {
            c.moreFromSongs(song, from, count)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val songs = picked?.songs.orEmpty()
        if (songs.isNotEmpty()) c.player.playNext(songs, null)
        val message = when {
            songs.isNotEmpty() -> tr(R.string.more_from_added, songCount(songs.size))
            picked?.offline == true && from == MoreFrom.ARTIST && song.primaryArtist.isNotBlank() ->
                tr(R.string.artist_nothing_on_phone, song.primaryArtist)
            picked?.offline == true -> tr(R.string.more_from_nothing_offline)
            else -> tr(R.string.more_from_nothing)
        }
        Toast.makeText(c.app, message, Toast.LENGTH_SHORT).show()
    }
}

/**
 * How many songs [from]'s button adds to Next (1.8.6), opened by holding it in the player's row or in the ⋯ menu. The
 * number is kept as it changes, and every later tap uses it.
 */
@Composable
fun MoreFromCountDialog(from: MoreFrom, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val settings by c.settings.flow.collectAsState()
    val count = settings.view.parts.moreFromCount
    val (min, max) = MoreFrom.COUNT_RANGE.first to MoreFrom.COUNT_RANGE.last
    // Each tap lands on the number shown (two quick taps add two), and the kept setting follows.
    var value by remember(count) { mutableIntStateOf(count) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (from.artist) tr(R.string.more_from_this_artist) else tr(R.string.more_from_this_album)) },
        text = {
            Column {
                Text(
                    tr(R.string.more_from_how_many_info),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    tr(R.string.more_from_how_many),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { value = (value - 1).coerceAtLeast(min); c.settings.setMoreFromCount(value) },
                        enabled = value > min,
                    ) { Icon(PrismIcons.Remove, tr(R.string.fewer)) }
                    Text(
                        "$value",
                        Modifier.width(64.dp).testTag("moreFromCount"),
                        style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
                    )
                    IconButton(
                        onClick = { value = (value + 1).coerceAtMost(max); c.settings.setMoreFromCount(value) },
                        enabled = value < max,
                    ) { Icon(PrismIcons.Add, tr(R.string.add)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.done)) } },
    )
}
