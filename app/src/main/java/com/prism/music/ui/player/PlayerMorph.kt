package com.prism.music.ui.player

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.lerp as lerpStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import androidx.compose.ui.unit.sp
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.PrismTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The one transition the player opens and closes with: a gesture-driven morph between the mini player and the full
 * player. [progress] runs 0 (the mini player, alone) to 1 (the full player, alone) and is written straight from the
 * finger while a swipe is in flight, so the whole change follows the hand, can turn back mid-way, and is only
 * finished (or undone) when it lifts. Taps and buttons play the same transition from wherever it is.
 *
 * The elements themselves — the cover, the title, the artist, the play control — are drawn by [PlayerMorphLayer]
 * between the two places their real counterparts report ([miniTitle] and [playerTitle], and the rest), so the change is
 * a morph rather than one player sliding over another; the surfaces around them (the player's layer in MainActivity
 * and [playerMorphMini]) follow the same number.
 */
object PlayerMorph {
    /** How far the finger travels, as a share of the window's height, for a full morph. */
    const val TRAVEL = 0.40f
    /** Past this share of the window's height a swipe finishes the transition instead of falling back. */
    const val THRESHOLD = 0.25f

    /** The point in [progress] the [THRESHOLD] corresponds to, opening. */
    const val COMPLETE_AT = THRESHOLD / TRAVEL

    /** 0 at the mini player, 1 at the full player; written by the finger while it moves. */
    var progress by mutableFloatStateOf(0f)
        private set

    /** A finger is writing [progress] right now (nothing settles until it lifts). */
    var dragging by mutableStateOf(false)
        private set

    /** The way the last transition is going: true toward the full player. */
    var opening by mutableStateOf(true)
        private set

    /** Reduce motion (Performance mode or the listener's own): the surface cross-fades, and no element morphs. */
    var animationsOff by mutableStateOf(false)

    /** A page asked for as the player opens (queue, lyrics), taken once by the player. */
    var requestedPage by mutableStateOf<PlayerPage?>(null)
        private set

    private var settleJob: Job? = null

    val isMorphing: Boolean get() = progress > 0.001f && progress < 0.999f

    /** Whether the mini player's own cover, title, artist and play button stand down for the moving copies. */
    val hidesTitle: Boolean get() = !animationsOff && progress > 0.045f && progress < 0.94f && miniTitle != null && playerTitle != null
    val hidesArtist: Boolean get() = !animationsOff && progress > 0.045f && progress < 0.94f && miniArtist != null && playerArtist != null
    val hidesPlay: Boolean get() = !animationsOff && progress > 0.045f && progress < 0.94f && miniPlay != null && playerPlay != null

    /** How much of the real player's title, artist and play button to show (they take over at the very end). */
    val elementReveal: Float get() =
        if (animationsOff || playerTitle == null) 1f else ((progress - 0.86f) / 0.14f).coerceIn(0f, 1f)

    val playReveal: Float get() =
        if (animationsOff || playerPlay == null || miniPlay == null) 1f else ((progress - 0.86f) / 0.14f).coerceIn(0f, 1f)

    /**
     * How much of the player's controls to show while it opens: the moving title crosses the place they sit, so they
     * come in as it lands rather than being read through it. They are all there at rest (and over a panel).
     */
    val deckReveal: Float get() =
        if (animationsOff) 1f else ((progress - 0.62f) / 0.38f).coerceIn(0f, 1f)

    fun request(page: PlayerPage?) {
        requestedPage = page
    }

    fun takeRequestedPage(): PlayerPage? = requestedPage.also { requestedPage = null }

    /** The page a mini player / cover gesture opens, if it opens one at all. */
    fun pageOf(gesture: com.prism.music.data.prefs.PlayerGesture): PlayerPage? = when (gesture) {
        com.prism.music.data.prefs.PlayerGesture.OPEN -> PlayerPage.COVER
        com.prism.music.data.prefs.PlayerGesture.QUEUE -> PlayerPage.QUEUE
        com.prism.music.data.prefs.PlayerGesture.LYRICS -> PlayerPage.LYRICS
        else -> null
    }

    /** Where [progress] is after the finger has travelled [distance] across [span], one way or the other. */
    fun progressForTravel(distance: Float, span: Float, towardPlayer: Boolean): Float {
        if (span <= 0f) return if (towardPlayer) 1f else 0f
        val f = (distance / span).coerceIn(0f, 1f)
        return if (towardPlayer) f else 1f - f
    }

    /** Whether letting go at [progress] finishes the way it was going: a quarter of the window's travel. */
    fun completes(progress: Float, towardPlayer: Boolean): Boolean =
        if (towardPlayer) progress >= COMPLETE_AT else progress <= 1f - COMPLETE_AT

    /** Whether [gesture] takes the player up (rather than acting on the song where it is). */
    fun opensPlayer(gesture: com.prism.music.data.prefs.PlayerGesture): Boolean =
        pageOf(gesture) != null || gesture == com.prism.music.data.prefs.PlayerGesture.VIDEO ||
            gesture == com.prism.music.data.prefs.PlayerGesture.SING

    fun beginDrag(towardPlayer: Boolean) {
        settleJob?.cancel()
        settleJob = null
        dragging = true
        opening = towardPlayer
    }

    fun dragTo(value: Float) {
        progress = value.coerceIn(0f, 1f)
    }

    /** The finger lifted: finish the way it was going, or fall back. */
    fun endDrag(towardPlayer: Boolean, complete: Boolean, scope: CoroutineScope, reduceMotion: Boolean, then: (() -> Unit)? = null) {
        dragging = false
        opening = towardPlayer
        val to = if (complete) (if (towardPlayer) 1f else 0f) else (if (towardPlayer) 0f else 1f)
        animateTo(scope, to, reduceMotion, then)
    }

    /** Opened by a tap, a button or an action: the same morph, finished on its own. */
    fun open(scope: CoroutineScope, reduceMotion: Boolean, then: (() -> Unit)? = null) {
        dragging = false
        opening = true
        animateTo(scope, 1f, reduceMotion, then)
    }

    /** Closed by Back, a button or a swipe that went far enough: the same morph, reversed. */
    fun close(scope: CoroutineScope, reduceMotion: Boolean, then: (() -> Unit)? = null) {
        dragging = false
        opening = false
        animateTo(scope, 0f, reduceMotion, then)
    }

    private fun animateTo(scope: CoroutineScope, to: Float, reduceMotion: Boolean, then: (() -> Unit)?) {
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(
                progress, to,
                animationSpec = tween(if (reduceMotion) 140 else if (to > progress) 340 else 300, easing = PrismTokens.Motion.Ease),
            ) { v, _ -> progress = v }
            then?.invoke()
        }
    }

    /** No animation: for a player restored already open. */
    fun snap(value: Float) {
        settleJob?.cancel()
        settleJob = null
        dragging = false
        progress = value.coerceIn(0f, 1f)
    }

    // ---------------------------------------------------------------- the two ends of the morph, in the window

    /** The mini player's cover (kept by [CoverFlight.mini] as well, since that is where the cover started). */
    var miniTitle by mutableStateOf<Rect?>(null)
    var miniArtist by mutableStateOf<Rect?>(null)
    var miniPlay by mutableStateOf<Rect?>(null)

    /** The full player's title, artist and play button, kept while the player is laid out. */
    var playerTitle by mutableStateOf<Rect?>(null)
    var playerArtist by mutableStateOf<Rect?>(null)
    var playerPlay by mutableStateOf<Rect?>(null)

    internal fun note(which: Int, r: Rect) {
        when (which) {
            0 -> if (progress <= 0.001f) miniTitle = if (near(miniTitle, r)) miniTitle else r
            1 -> if (progress <= 0.001f) miniArtist = if (near(miniArtist, r)) miniArtist else r
            2 -> if (progress <= 0.001f) miniPlay = if (near(miniPlay, r)) miniPlay else r
            3 -> if (progress > 0.001f) playerTitle = if (near(playerTitle, r)) playerTitle else r
            4 -> if (progress > 0.001f) playerArtist = if (near(playerArtist, r)) playerArtist else r
            5 -> if (progress > 0.001f) playerPlay = if (near(playerPlay, r)) playerPlay else r
        }
    }

    private fun near(a: Rect?, b: Rect): Boolean =
        a != null && kotlin.math.abs(a.left - b.left) < 0.5f && kotlin.math.abs(a.top - b.top) < 0.5f &&
            kotlin.math.abs(a.width - b.width) < 0.5f && kotlin.math.abs(a.height - b.height) < 0.5f

    /** True where the moving copies should be drawn: during a real transition, with both ends known. */
    fun drawsOverlay(): Boolean = !animationsOff && isMorphing
}

/** The mini player reports its parts only at rest: while it fades they don't move, and chasing them would fight it. */
fun Modifier.miniTitleAnchor(): Modifier = onGloballyPositioned { PlayerMorph.note(0, it.boundsInRoot()) }

fun Modifier.miniArtistAnchor(): Modifier = onGloballyPositioned { PlayerMorph.note(1, it.boundsInRoot()) }

fun Modifier.miniPlayAnchor(): Modifier = onGloballyPositioned { PlayerMorph.note(2, it.boundsInRoot()) }

/** The player's parts keep reporting while they move: the copies land exactly where they are when the player settles. */
fun Modifier.playerTitleAnchor(): Modifier = onGloballyPositioned { PlayerMorph.note(3, it.boundsInRoot()) }

fun Modifier.playerArtistAnchor(): Modifier = onGloballyPositioned { PlayerMorph.note(4, it.boundsInRoot()) }

fun Modifier.playerPlayAnchor(): Modifier = onGloballyPositioned { PlayerMorph.note(5, it.boundsInRoot()) }

/** The mini player as it opens: it stays where it is (so the moving copies start exactly on it) and fades away. */
fun Modifier.playerMorphMini(): Modifier = graphicsLayer {
    // Gone a little past half-way: the player is all but opaque by then, and the copies carry the rest.
    alpha = 1f - (PlayerMorph.progress * 2.6f).coerceIn(0f, 1f)
}

/**
 * A panel's (lyrics, the queue, the stats) morph: 0 with the player's cover up, 1 with the panel over the collapsed
 * header. A finger writes it while the deck or the header is dragged; otherwise it eases to follow the panel's flag.
 */
internal class PanelShift(start: Float = 0f) {
    var value by mutableFloatStateOf(start)
        private set
    var dragging by mutableStateOf(false)
        private set
    private var job: Job? = null

    fun begin() {
        job?.cancel()
        job = null
        dragging = true
    }

    fun follow(v: Float) {
        job?.cancel()
        job = null
        value = v.coerceIn(0f, 1f)
    }

    fun end() {
        dragging = false
    }

    fun animateTo(scope: CoroutineScope, to: Float, reduceMotion: Boolean, then: (() -> Unit)? = null) {
        job?.cancel()
        job = scope.launch {
            animate(value, to, animationSpec = tween(if (reduceMotion) 150 else 320, easing = PrismTokens.Motion.Ease)) { v, _ -> value = v }
            then?.invoke()
        }
    }
}

/**
 * The cover, title, artist and play control moving between the mini player's places and the full player's. Drawn only
 * while a morph is in flight (and never with Reduce motion), over both players, with no input of its own: it's a
 * picture of the hand-over, not another thing to touch.
 */
@Composable
fun PlayerMorphLayer(title: String, artist: String, isPlaying: Boolean) {
    val t = PlayerMorph.progress
    if (!PlayerMorph.drawsOverlay()) return
    val settings = LocalAppSettings.current
    val typography = MaterialTheme.typography
    val scheme = MaterialTheme.colorScheme
    val fromTitle = PlayerMorph.miniTitle
    val toTitle = PlayerMorph.playerTitle
    val fromArtist = PlayerMorph.miniArtist
    val toArtist = PlayerMorph.playerArtist
    val fromPlay = PlayerMorph.miniPlay
    val toPlay = PlayerMorph.playerPlay
    val fade = ((t - 0.02f) / 0.055f).coerceIn(0f, 1f) * (1f - ((t - 0.945f) / 0.055f).coerceIn(0f, 1f))
    if (fade <= 0.001f) return
    val titleStyle = lerpStyle(typography.titleSmall, playerTitleStyle(typography, settings), t)
    val artistStyle = lerpStyle(typography.bodySmall, playerArtistStyle(typography, settings), t)
    // The words turn white sooner than they grow: over the player's dark picture they must stay readable all the way.
    val ink = (t * 1.6f).coerceAtMost(1f)
    val titleColor = lerp(scheme.onSurface, Color.White, ink)
    val artistColor = lerp(scheme.onSurfaceVariant, Color.White.copy(alpha = 0.6f), ink)
    if (fromTitle != null && toTitle != null) {
        MorphLine(title, fromTitle, toTitle, t, titleStyle, titleColor, fade, "playerMorphTitle")
    }
    if (fromArtist != null && toArtist != null && artist.isNotBlank()) {
        MorphLine(artist, fromArtist, toArtist, t, artistStyle, artistColor, fade, "playerMorphArtist")
    }
    if (fromPlay != null && toPlay != null) {
        val r = lerpRect(fromPlay, toPlay, t)
        Box(
            Modifier
                .clearAndSetSemantics { testTag = "playerMorphPlay" }
                .layout { m, c ->
                    val w = r.width.roundToInt().coerceAtLeast(1)
                    val h = r.height.roundToInt().coerceAtLeast(1)
                    val p = m.measure(Constraints.fixed(w, h))
                    layout(c.maxWidth, c.maxHeight) { p.place(r.left.roundToInt(), r.top.roundToInt()) }
                }
                .graphicsLayer { alpha = fade },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isPlaying) PrismIcons.Pause else PrismIcons.Play, null,
                Modifier.size(lerpDp(24.dp, 66.dp, t)),
                tint = Color.White,
            )
        }
    }
}

/** One line of the hand-over: the words, at the size and place between [from] and [to] the finger has reached. */
@Composable
private fun MorphLine(text: String, from: Rect, to: Rect, t: Float, style: TextStyle, color: Color, alpha: Float, tag: String) {
    val r = lerpRect(from, to, t)
    Text(
        text,
        style = style,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clearAndSetSemantics { testTag = tag }
            .layout { m, c ->
                val w = r.width.roundToInt().coerceAtLeast(1)
                val p = m.measure(Constraints(maxWidth = w))
                layout(c.maxWidth, c.maxHeight) {
                    p.place(r.left.roundToInt(), (r.top + (r.height - p.height) / 2f).roundToInt())
                }
            }
            .graphicsLayer { this.alpha = alpha },
    )
}

/** The rectangle between two places, the way every moving part of the morph is placed. */
internal fun lerpRect(a: Rect, b: Rect, t: Float): Rect = Rect(
    a.left + (b.left - a.left) * t,
    a.top + (b.top - a.top) * t,
    a.right + (b.right - a.right) * t,
    a.bottom + (b.bottom - a.bottom) * t,
)
