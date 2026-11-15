package com.prism.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.data.SnoozeDuration
import com.prism.music.data.SnoozedTrack
import com.prism.music.data.shown
import com.prism.music.tr
import com.prism.music.trp
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.components.EmptyState
import com.prism.music.ui.components.PrismSheet
import com.prism.music.ui.components.PrismTextField
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.data.model.Song

/**
 * The snoozed tracks' list (1.8.5): one place for the listener to see what's kept out of automatic queues, how many days
 * each has left, to change a number or let a track back in, and to open it where it lives.
 *
 * Opened from Settings → Player (the gear beside "Buttons in the row") and by holding the player's Snooze button. Held
 * from the player, it opens [highlightSongId]'s line (when that song is snoozed): scrolled to and marked.
 */
@Composable
fun SnoozeSheet(highlightSongId: String? = null, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val tracks by c.snoozes.tracks.collectAsState()
    val defaultDays by c.snoozes.defaultDays.collectAsState()
    // A snooze that runs out while the list is open goes: the list is asked again every half minute.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    LaunchedEffect(Unit) { c.snoozes.prune() }
    // A snooze that runs out while the list is open goes, and its record with it.
    LaunchedEffect(tracks, now) { if (tracks.values.any { it.until <= now }) c.snoozes.prune() }
    val records = remember(tracks, now) {
        tracks.values.filter { it.until > now }.sortedByDescending { it.snoozedAt }
    }
    SnoozeSheetContent(
        records = records,
        now = now,
        highlightSongId = highlightSongId,
        defaultDays = defaultDays,
        onDefaultDays = { c.snoozes.setDefaultDays(it) },
        onDays = { id, days -> c.snoozes.setDays(id, days) },
        onUnsnooze = { c.snoozes.unsnooze(it) },
        onOpen = { song ->
            snoozeTarget(song)?.let { route ->
                onDismiss()
                nav.go(route)
            }
        },
        onDismiss = onDismiss,
    )
}

/** Where a snoozed track opens: its album, or its artist when the song has no album (Prism's own pages; no song page). */
internal fun snoozeTarget(song: Song): String? =
    song.album?.id?.takeIf { it.isNotBlank() }?.let { Routes.album(it) }
        ?: song.artists.firstOrNull()?.id?.takeIf { it.isNotBlank() }?.let { Routes.artist(it) }

/** The sheet's body apart from the store: [records] (newest first), [now], and what each control does. */
@Composable
internal fun SnoozeSheetContent(
    records: List<SnoozedTrack>,
    now: Long,
    highlightSongId: String?,
    defaultDays: Int,
    onDefaultDays: (Int) -> Unit,
    onDays: (String, Int) -> Unit,
    onUnsnooze: (String) -> Unit,
    onOpen: (Song) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    PrismSheet(
        onDismiss, containerColor = scheme.surfaceContainerLow, contentColor = scheme.onSurface, dragHandle = null,
    ) { _ ->
        Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(40.dp, 4.dp).background(scheme.onSurface.copy(alpha = 0.3f), CircleShape))
        }
        Column(
            Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.85f).testTag("snoozeSheet"),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(PrismIcons.Timer, null, Modifier.size(22.dp), tint = scheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Text(tr(R.string.snoozed_tracks), style = MaterialTheme.typography.titleLarge)
            }
            SnoozeDefaultDays(defaultDays, onDefaultDays)
            SnoozeList(
                records = records, now = now, highlightSongId = highlightSongId,
                onDays = onDays, onUnsnooze = onUnsnooze, onOpen = onOpen,
            )
        }
    }
}

/** The days a new snooze gets: the list's own number, with "Invalid number" and a reset to 30 for anything else. */
@Composable
internal fun SnoozeDefaultDays(days: Int, onSave: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var text by remember(days) { mutableStateOf(days.toString()) }
    var committed by remember(days) { mutableStateOf(days.toString()) }
    var invalid by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val safe = SnoozeDuration.DEFAULT_DAYS
    fun commit() {
        if (text == committed) return
        val parsed = SnoozeDuration.parse(text)
        if (parsed == null) {
            invalid = true
            text = safe.toString()
            committed = text
            onSave(safe)
        } else {
            invalid = false
            text = parsed.toString()
            committed = text
            if (parsed != days) onSave(parsed)
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tr(R.string.snooze_for), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
            PrismTextField(
                text,
                { text = it.take(5); invalid = it.isNotBlank() && SnoozeDuration.parse(it) == null },
                Modifier.width(76.dp).onFocusChanged {
                    if (!it.isFocused && focused) commit()
                    focused = it.isFocused
                }.testTag("snoozeDefaultDays"),
                singleLine = true, isError = invalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit(); focus.clearFocus() }),
            )
            Text(
                trp(R.plurals.days_word, days), Modifier.padding(start = 6.dp),
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
        }
        Text(
            if (invalid) tr(R.string.invalid_number) else tr(R.string.snooze_for_info),
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (invalid) scheme.error else scheme.onSurfaceVariant,
        )
    }
}

/** The snoozed tracks themselves (no sheet around them): empty state, or a line each. */
@Composable
internal fun SnoozeList(
    records: List<SnoozedTrack>,
    now: Long,
    highlightSongId: String?,
    onDays: (String, Int) -> Unit,
    onUnsnooze: (String) -> Unit,
    onOpen: (Song) -> Unit,
) {
    val list = rememberLazyListState()
    // Held from the player: its song's line is brought into view once (and marked while the list is open).
    val focusIndex = remember(records, highlightSongId) { records.indexOfFirst { it.song.id == highlightSongId } }
    var scrolled by remember(highlightSongId) { mutableStateOf(false) }
    LaunchedEffect(focusIndex) {
        if (focusIndex >= 0 && !scrolled) {
            list.scrollToItem((focusIndex - 1).coerceAtLeast(0))
            scrolled = true
        }
    }
    if (records.isEmpty()) {
        EmptyState(PrismIcons.Timer, tr(R.string.nothing_snoozed), tr(R.string.nothing_snoozed_body))
    } else {
        LazyColumn(Modifier.fillMaxWidth(), state = list) {
            items(records, key = { it.song.id }) { record ->
                SnoozeRow(
                    record = record,
                    now = now,
                    highlighted = record.song.id == highlightSongId,
                    onDays = { days -> onDays(record.song.id, days) },
                    onUnsnooze = { onUnsnooze(record.song.id) },
                    onOpen = { onOpen(record.song) },
                )
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

/** One snoozed track: what it is, the days it has left (to edit), and Unsnooze. */
@Composable
private fun SnoozeRow(
    record: SnoozedTrack,
    now: Long,
    highlighted: Boolean,
    onDays: (Int) -> Unit,
    onUnsnooze: () -> Unit,
    onOpen: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val days = SnoozeDuration.daysLeft(record.until, now)
    val song = record.song.shown
    var text by remember(record.song.id, days) { mutableStateOf(days.toString()) }
    var committed by remember(record.song.id, days) { mutableStateOf(days.toString()) }
    var invalid by remember(record.song.id) { mutableStateOf(false) }
    var focused by remember(record.song.id) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    // A number that isn't one (or is out of range) is refused: "Invalid number", and the field goes back to the default.
    fun commit() {
        if (text == committed) return
        val parsed = SnoozeDuration.parse(text)
        if (parsed == null) {
            invalid = true
            text = SnoozeDuration.DEFAULT_DAYS.toString()
            committed = text
            onDays(SnoozeDuration.DEFAULT_DAYS)
        } else {
            invalid = false
            text = parsed.toString()
            committed = text
            if (parsed != days) onDays(parsed)
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .background(if (highlighted) scheme.primary.copy(alpha = 0.14f) else Color.Transparent)
                .clickable(role = Role.Button, onClick = onOpen)
                .testTag("snoozeRow")
                .semantics { selected = highlighted }
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(song.thumbnail, Modifier.size(44.dp), placeholderIcon = PrismIcons.Song)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium, color = scheme.onSurface,
                )
                Text(
                    song.artistText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
            }
            PrismTextField(
                text,
                { text = it.take(5); invalid = it.isNotBlank() && SnoozeDuration.parse(it) == null },
                Modifier.width(76.dp).onFocusChanged {
                    if (!it.isFocused && focused) commit()
                    focused = it.isFocused
                }.testTag("snoozeDays"),
                singleLine = true, isError = invalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit(); focus.clearFocus() }),
            )
            Text(
                trp(R.plurals.days_word, days), Modifier.padding(horizontal = 6.dp),
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
            IconButton(onClick = onUnsnooze, modifier = Modifier.testTag("snoozeUnsnooze")) {
                Icon(PrismIcons.Undo, tr(R.string.unsnooze), Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
            }
        }
        if (invalid) Text(
            tr(R.string.invalid_number), Modifier.padding(start = 72.dp, bottom = 6.dp),
            style = MaterialTheme.typography.bodySmall, color = scheme.error,
        )
    }
}
