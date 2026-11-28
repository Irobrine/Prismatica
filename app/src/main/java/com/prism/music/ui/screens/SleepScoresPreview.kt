package com.prism.music.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.prism.music.AppContainer
import com.prism.music.R
import com.prism.music.data.model.Song
import com.prism.music.playback.toMediaItem
import com.prism.music.tr
import com.prism.music.ui.theme.PrismIcons
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay
import kotlin.math.pow

/**
 * The points a "skim" plays: [WINDOW_MS] of the song at four places (near the start, a third in, two thirds in and near
 * the end), so a score can be checked against what the song actually does across its length — a quiet intro, a chorus, a
 * break. Short songs get shorter windows (never overlapping more than the song allows) and the same song's points are
 * never past its end.
 */
object SkimPlan {
    const val WINDOW_MS = 8_000L
    private val FRACTIONS = listOf(0.06f, 0.35f, 0.62f, 0.9f)

    /** Where a preview starts: a third in, usually past an intro. */
    fun start(durationMs: Long): Long = if (durationMs <= 0) 0L else (durationMs * 0.35f).toLong()

    fun window(durationMs: Long): Long = if (durationMs <= 0) WINDOW_MS else minOf(WINDOW_MS, (durationMs / 5).coerceAtLeast(2_000L))

    /** The skim's starting points, in order, all at least a window before the end. */
    fun points(durationMs: Long, windowMs: Long = window(durationMs)): List<Long> {
        if (durationMs <= 0) return emptyList()
        val last = (durationMs - windowMs).coerceAtLeast(0L)
        return FRACTIONS.map { f -> (durationMs * f).toLong().coerceIn(0L, last) }
    }
}

/**
 * A preview player of its own, for listening to a song while reading its sleep score: it doesn't touch the queue, and the
 * main player is paused (and picked up again) rather than replaced. Built on the app's own data source — local files,
 * downloads and the stream cache play as they would, and a stream is resolved like any play — at the level Normalize
 * would give the song, so what's heard is what would play at bedtime.
 */
@OptIn(UnstableApi::class)
@Stable
class SongPreviewState(private val context: Context, private val c: AppContainer) {
    var song by mutableStateOf<Song?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set

    /** Playing the skim's excerpts now (the play button pauses it, as any play). */
    var skimming by mutableStateOf(false)
        private set

    private var player: ExoPlayer? = null
    private var skimPoints: List<Long> = emptyList()
    private var skimAt = 0
    private var skimWindow = SkimPlan.WINDOW_MS
    /** The listener's music was playing when this opened: it picks up again when the preview closes. */
    private var resumeMain = false
    private var wasPlaying = false

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
        }

        override fun onPlayerError(error: PlaybackException) {
            isPlaying = false
            skimming = false
            android.widget.Toast.makeText(context, tr(R.string.playback_failed), android.widget.Toast.LENGTH_SHORT).show()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val p = player ?: return
            if (playbackState == Player.STATE_READY && p.duration > 0 && p.duration != C.TIME_UNSET) durationMs = p.duration
            if (playbackState == Player.STATE_ENDED) {
                isPlaying = false
                skimming = false
            }
        }
    }

    /** Opens [song]'s preview, replacing any other. */
    fun open(song: Song) {
        if (this.song?.id == song.id) { toggle(); return }
        release()
        this.song = song
        skimming = false
        skimPoints = emptyList()
        positionMs = 0L
        durationMs = song.durationSec * 1000L
        // The listener's music steps aside for the preview, and comes back when it closes.
        wasPlaying = c.player.isPlaying.value
        resumeMain = wasPlaying
        if (wasPlaying) c.player.setPlaying(false)
        val p = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(c.playbackDataSourceFactory()))
            .build()
            .also { player = it }
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            // The listener's music is paused for this; don't also take audio focus from it (or from anything else).
            false,
        )
        p.addListener(listener)
        p.volume = playbackLevel(song)
        val file = runCatching { c.localFileFor(song) }.getOrNull()
        p.setMediaItem(if (file != null) song.toMediaItem(localFile = file, localReady = true) else song.toMediaItem())
        p.prepare()
        p.seekTo(SkimPlan.start(durationMs))
        p.play()
    }

    /**
     * The volume the song would play at: Normalize's gain for its measured loudness (a cut for a loud master, a lift for a
     * quiet one), as a linear level. 1 when Normalize is off.
     */
    private fun playbackLevel(song: Song): Float {
        val s = c.settings.current
        if (!s.normalize) return 1f
        val gainDb = runCatching { c.loudnessIndex.figure(song.id, null, s.sound.normalizeTarget.toFloat()).gainDb }.getOrDefault(0f)
        return 10f.pow(gainDb / 20f).coerceIn(0f, 1f)
    }

    fun toggle() {
        val p = player ?: return
        if (p.isPlaying) { p.pause(); skimming = false } else p.play()
    }

    fun seek(ms: Long) {
        val p = player ?: return
        skimming = false
        val to = ms.coerceIn(0L, durationMs.coerceAtLeast(0L))
        positionMs = to
        p.seekTo(to)
    }

    /** Plays a window at each of [SkimPlan.points]: a few seconds of each part of the song, in turn. */
    fun skim() {
        val p = player ?: return
        if (skimming) { skimming = false; p.pause(); return }
        skimPoints = SkimPlan.points(durationMs)
        if (skimPoints.isEmpty()) return
        skimAt = 0
        skimWindow = SkimPlan.window(durationMs)
        skimming = true
        p.seekTo(skimPoints[0])
        p.play()
    }

    /** Advances the position display and the skim's next excerpt; called a few times a second while open. */
    fun tick() {
        val p = player ?: return
        positionMs = p.currentPosition.coerceAtLeast(0L)
        if (p.duration > 0 && p.duration != C.TIME_UNSET) durationMs = p.duration
        if (skimming) {
            val from = skimPoints.getOrNull(skimAt) ?: return
            val pastWindow = positionMs >= from + skimWindow
            val jumped = positionMs < from - 500
            if (pastWindow || jumped) {
                skimAt++
                val next = skimPoints.getOrNull(skimAt)
                if (next == null) { skimming = false; p.pause() } else p.seekTo(next).also { positionMs = next }
            }
        }
    }

    /** Closes the preview and picks the listener's music up again if it was playing. */
    fun close() {
        val resume = resumeMain
        release()
        if (resume) c.player.setPlaying(true)
        resumeMain = false
    }

    private fun release() {
        skimming = false
        isPlaying = false
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        song = null
    }
}

@Composable
fun rememberSongPreview(): SongPreviewState {
    val c = com.prism.music.ui.theme.LocalContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val state = remember(c) { SongPreviewState(context.applicationContext, c) }
    DisposableEffect(state) { onDispose { state.close() } }
    LaunchedEffect(state.song) {
        while (state.song != null) {
            state.tick()
            delay(200)
        }
    }
    return state
}

/**
 * The preview bar: what's playing, its score, play/pause, a slider over the whole song, and Skim. It sits over the bottom
 * of the Sleep scores list, so a row's score and the sound can be read side by side.
 */
@Composable
fun SongPreviewBar(state: SongPreviewState, score: Int?, modifier: Modifier = Modifier) {
    val song = state.song ?: return
    // While the slider is held, the thumb follows the finger and only the release seeks: a drag would otherwise send a
    // seek for every pixel, which stutters the sound being judged.
    var dragging by remember(song.id) { mutableStateOf<Float?>(null) }
    Surface(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(song.artistText.ifBlank { null }, score?.let { tr(R.string.sleep_score_out_of, it) })
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    )
                }
                IconButton({ state.close() }) { Icon(PrismIcons.Close, tr(R.string.close)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ state.toggle() }) {
                    Icon(
                        if (state.isPlaying) PrismIcons.Pause else PrismIcons.Play,
                        tr(if (state.isPlaying) R.string.widget_pause else R.string.widget_play),
                    )
                }
                val duration = state.durationMs.coerceAtLeast(1L)
                Slider(
                    value = (dragging ?: state.positionMs.coerceIn(0L, duration).toFloat()).coerceIn(0f, duration.toFloat()),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { state.seek(it.toLong()) }
                        dragging = null
                    },
                    valueRange = 0f..duration.toFloat(),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    previewClock((dragging ?: state.positionMs.toFloat()).toLong()),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    previewClock(state.durationMs), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.TextButton(onClick = { state.skim() }) {
                    Text(tr(R.string.analysis_skim))
                }
            }
        }
    }
}

/** "3:07" for a position in ms. */
internal fun previewClock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
