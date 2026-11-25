package com.prism.music.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.ArtistPage
import com.prism.music.data.model.SongItem
import com.prism.music.data.openArtist
import com.prism.music.tr
import com.prism.music.ui.Load
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.EmptyState
import com.prism.music.ui.components.ErrorState
import com.prism.music.ui.components.LoadingState
import com.prism.music.ui.components.SectionHeader
import com.prism.music.ui.components.SubPage
import com.prism.music.ui.rememberLoad
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** An artist and the artists around them, for the connections map. */
private data class Connections(val artist: ArtistPage, val others: List<ArtistItem>)

/**
 * One artist's connections: the artists YouTube puts beside them, as a small map with the artist in the middle. A tap
 * on one opens their page, where their own connections are a tap further. The map is decoration on top of a real
 * list below it, so a crowded phone still shows every name and every artist is reachable without aiming.
 */
@Composable
fun ArtistConnectionsScreen(id: String, bottomPadding: Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val load = rememberLoad("artist-connections:$id") {
        val opened = c.openArtist(id)
        val page = opened.page
        // The page's own "Similar artists" shelf, by kind (all artists), not by its title, which is in YouTube's language.
        var others = page.shelves
            .firstOrNull { s -> s.items.isNotEmpty() && s.items.all { it is ArtistItem } }
            ?.items.orEmpty().filterIsInstance<ArtistItem>()
        // A kept page may have none (it was saved before a similar shelf existed): a song's Related tab can still name some.
        if (others.isEmpty()) {
            val seed = page.shelves.asSequence().flatMap { it.items.asSequence() }.filterIsInstance<SongItem>()
                .map { it.song }.firstOrNull { !it.isVideo }
                ?: runCatching { c.ytm.artistTopSongs(id).firstOrNull() }.getOrNull()
            if (seed != null) others = runCatching { c.ytm.related(seed.id, seed.title).artists }.getOrDefault(emptyList())
        }
        val seen = HashSet<String>()
        Connections(page, others.filter { it.id != id && it.id != page.id && seen.add(it.id) }.take(8))
    }
    val title = (load.state as? Load.Ok)?.value?.artist?.name?.takeIf { it.isNotBlank() } ?: tr(R.string.artist_connections)
    SubPage(title, bottomPadding, Modifier.testTag("artistConnections"), subtitle = tr(R.string.artist_connections_strapline)) {
        when (val s = load.state) {
            Load.Loading -> item { LoadingState() }
            is Load.Err -> item { ErrorState(s.message) { load.reload(false) } }
            is Load.Ok -> {
                val conn = s.value
                if (conn.others.isEmpty()) {
                    item { EmptyState(PrismIcons.Groups, tr(R.string.artist_connections), tr(R.string.no_connections_found)) }
                } else {
                    item("map") { ConnectionMap(conn, onOpen = { item -> nav.go(Routes.artist(item.id)) }) }
                    item("all") { SectionHeader(tr(R.string.similar_artists)) }
                    items(conn.others.size, key = { "c" + conn.others[it].id }) { i ->
                        val a = conn.others[i]
                        androidx.compose.foundation.layout.Row(
                            Modifier.fillMaxWidth().clickable { nav.go(Routes.artist(a.id)) }
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(a.thumbnail, Modifier.size(48.dp).clip(CircleShape), shape = CircleShape, size = 226, placeholderIcon = PrismIcons.Person)
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text(a.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (a.subtitle.isNotBlank()) Text(
                                    a.subtitle, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            androidx.compose.material3.Icon(
                                PrismIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The map itself: the artist in the middle, their related artists around them, joined by thin lines. They settle out
 * from the middle once when the page opens (or are simply there with Reduce motion on). Each is a tap target of its
 * own; the list below the map repeats them for good measure.
 */
@Composable
private fun ConnectionMap(conn: Connections, onOpen: (ArtistItem) -> Unit) {
    val settings = LocalAppSettings.current
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val appear = remember(conn.artist.id) { Animatable(if (settings.reduceMotion) 1f else 0f) }
    LaunchedEffect(conn.artist.id) {
        if (!settings.reduceMotion && appear.value < 1f) appear.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
    }
    BoxWithConstraints(
        // Top-start placement: each node is offset from the box's own corner, and its artwork is centred on that point.
        // (With the box centring its children, an offset moved a node away from the centre again, which pushed every
        // artist but the middle one out of place.)
        Modifier.fillMaxWidth().height(420.dp).padding(top = 6.dp),
    ) {
        val width = maxWidth
        val height = maxHeight
        val cx = width / 2
        val cy = height / 2
        val radius = (minOf(width, 420.dp) / 2 - 56.dp) * appear.value
        val nodes = conn.others.mapIndexed { i, a ->
            val angle = (2 * PI * i / conn.others.size) - PI / 2
            Triple(a, cx + (radius.value * cos(angle)).dp, cy + (radius.value * sin(angle)).dp)
        }
        // Lines first, under the covers, from one artwork's centre to another's.
        Canvas(Modifier.matchParentSize()) {
            for ((_, x, y) in nodes) {
                drawLine(
                    color = ink.copy(alpha = 0.22f * appear.value),
                    start = Offset(cx.toPx(), cy.toPx()),
                    end = Offset(x.toPx(), y.toPx()),
                    strokeWidth = 1.2.dp.toPx(),
                )
            }
        }
        // The artist in the middle: its artwork centred on the box's centre, its name under it.
        Column(
            Modifier.offset(x = cx - 54.dp, y = cy - 38.dp).width(108.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Artwork(
                conn.artist.thumbnail, Modifier.size(76.dp).clip(CircleShape), shape = CircleShape, size = 320,
                placeholderIcon = PrismIcons.Person,
            )
            Text(
                conn.artist.name, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
            )
        }
        for ((a, x, y) in nodes) {
            // y is the artwork's centre: the column starts half a cover above it, and the name runs on below.
            Column(
                Modifier.offset(x = x - 43.dp, y = y - 27.dp).width(86.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .clickable { onOpen(a) }.padding(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Artwork(a.thumbnail, Modifier.size(54.dp).clip(CircleShape), shape = CircleShape, size = 226, placeholderIcon = PrismIcons.Person)
                Text(
                    a.title, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}
