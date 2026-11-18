package com.prism.music.ui.player

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.lerp as lerpStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import androidx.compose.ui.unit.sp
import com.prism.music.data.prefs.MiniPlayerStyle
import com.prism.music.data.prefs.TransportStyle
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.PrismTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A morph's number as everything downstream takes it: within 0…1, and a number at all. The arithmetic between an
 * eased fraction and its ends can land a hair outside the range (one float ULP past 1), and the numbers these
 * fractions feed take a negative or NaN size as a crash — the player card's corner radius is
 * (1 - [PlayerMorph.progress]) · 28 dp, and Compose's CornerBasedShape throws IllegalArgumentException on the very
 * frame a corner goes under zero (Prism 1.1.2, twice on one phone: corner -8.761883E-6 px = (1 - nextFloat(1)) · 28
 * dp, identical both times). One bad frame is all it takes — the frame's own apply crashes before a next frame could
 * correct it — so the writes are clamped, and the reads that feed sizes are too. [to] stands in for a value that
 * isn't a number: the settle was on its way there (a read with nowhere to go takes 1, the layer's rest).
 */
internal fun Float.sanely(to: Float): Float = if (isFinite()) coerceIn(0f, 1f) else to

/**
 * The one transition the player opens and closes with: a gesture-driven morph between the mini player and the full
 * player. [progress] runs 0 (the mini player, alone) to 1 (the full player, alone) and is written straight from the
 * finger while a swipe is in flight, so the whole change follows the hand, can turn back mid-way, and is only
 * finished (or undone) when it lifts. Taps and buttons play the same transition from wherever it is.
 *
 * The elements themselves — the cover, the title, the artist, and the play, previous and next controls — are drawn by
 * [PlayerMorphLayer] between the two places their real counterparts report ([miniTitle] and [playerTitle], and the
 * rest), so the change is a morph rather than one player sliding over another; the surfaces around them (the player's
 * layer in MainActivity
 * and [playerMorphMini]) follow the same number.
 */
object PlayerMorph {
    /** How far the finger travels, as a share of the window's height, for a full morph. */
    const val TRAVEL = 0.40f
    /** Past this share of the window's height a swipe finishes the transition instead of falling back. */
    const val THRESHOLD = 0.10f

    /**
     * A flick this fast finishes the way it was going without the distance: [progress] a second, so one full morph a
     * second. A hand crossing a phone's height in a third of a second is clearly on its way, however far it got.
     */
    const val FLICK = 1f

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

    /**
     * The speed a hand let go at, [progress] a second, waiting for the transition it asked for (see [released]): the way
     * in from the mini player and the way out of the player are finished through the same calls everything else uses, so
     * the speed travels with the number instead of through a parameter every caller would have to carry.
     */
    private var handOff = 0f

    val isMorphing: Boolean get() = progress > 0.001f && progress < 1f

    /** Whether the mini player's own cover, title, artist and controls stand down for the moving copies. */
    val hidesTitle: Boolean get() = !animationsOff && progress > 0.001f && progress < 1f && miniTitle != null && playerTitle != null
    val hidesArtist: Boolean get() = !animationsOff && progress > 0.001f && progress < 1f && miniArtist != null && playerArtist != null
    val hidesPlay: Boolean get() = !animationsOff && progress > 0.001f && progress < 1f && miniPlay != null && playerPlay != null
    val hidesPrev: Boolean get() = !animationsOff && progress > 0.001f && progress < 1f && miniPrev != null && playerPrev != null
    val hidesNext: Boolean get() = !animationsOff && progress > 0.001f && progress < 1f && miniNext != null && playerNext != null

    /**
     * How much of the real player's title, artist and play button to show. The moving copies carry them for the whole
     * transition and hand over in one step at the very end, where the two are exactly in the same place: a cross-fade
     * here would show both at once for a moment.
     */
    val elementReveal: Float get() = when {
        animationsOff -> 1f
        !hasRowMorph() || !atRestAtPlayer() -> 0f
        else -> 1f
    }

    val playReveal: Float get() = when {
        animationsOff -> 1f
        // Nothing to hand over (the mini player's play button is off, say): the player's own is the only one.
        playerPlay == null || miniPlay == null -> 1f
        !atRestAtPlayer() -> 0f
        else -> 1f
    }

    /** The player's previous and next, handed over the same way the play control is. */
    val prevReveal: Float get() = when {
        animationsOff || miniPrev == null || playerPrev == null -> 1f
        atRestAtPlayer() -> 1f
        else -> 0f
    }

    val nextReveal: Float get() = when {
        animationsOff || miniNext == null || playerNext == null -> 1f
        atRestAtPlayer() -> 1f
        else -> 0f
    }

    /** The transport is the compact one (the deck is small or tight); the moving play control lands on its size. */
    var playCompact by mutableStateOf(false)

    /** The player's previous / next are dimmed at the queue's ends; the moving copies land at the same weight. */
    var prevEnabled by mutableStateOf(true)
    var nextEnabled by mutableStateOf(true)

    /** The title or the artist are moving, so the player's own row must stand down until they land. */
    private fun hasRowMorph(): Boolean = playerTitle != null || playerArtist != null

    private fun atRestAtPlayer(): Boolean = progress >= 1f

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

    /**
     * Whether letting go at [progress] finishes the way it was going: a tenth of the window's travel, or a [velocity]
     * flick toward that end however little way it had got (and not a flick the other way).
     */
    fun completes(progress: Float, towardPlayer: Boolean, velocity: Float = 0f): Boolean {
        if (if (towardPlayer) progress >= COMPLETE_AT else progress <= 1f - COMPLETE_AT) return true
        val toward = if (towardPlayer) 1f else -1f
        return velocity * toward >= FLICK
    }

    /** Whether [gesture] takes the player up (rather than acting on the song where it is). */
    fun opensPlayer(gesture: com.prism.music.data.prefs.PlayerGesture): Boolean =
        pageOf(gesture) != null || gesture == com.prism.music.data.prefs.PlayerGesture.VIDEO ||
            gesture == com.prism.music.data.prefs.PlayerGesture.SING

    fun beginDrag(towardPlayer: Boolean) {
        settleJob?.cancel()
        settleJob = null
        handOff = 0f
        dragging = true
        opening = towardPlayer
    }

    fun dragTo(value: Float) {
        // A fraction (NaN included, which coerceIn passes through) never reaches the layer below.
        if (value.isFinite()) progress = value.coerceIn(0f, 1f)
    }

    /**
     * The hand lifted at [velocity] ([progress] a second, the way it was moving): the transition it asks for next starts
     * at that speed, so letting go carries on where the finger left off instead of stopping dead to set off again.
     */
    fun released(velocity: Float) {
        handOff = velocity
    }

    private fun takeHandOff(): Float = handOff.also { handOff = 0f }

    /** The finger lifted: finish the way it was going (at [velocity]), or fall back. */
    fun endDrag(
        towardPlayer: Boolean,
        complete: Boolean,
        scope: CoroutineScope,
        reduceMotion: Boolean,
        velocity: Float = 0f,
        then: (() -> Unit)? = null,
    ) {
        dragging = false
        opening = towardPlayer
        // A speed handed over for another way in or out isn't this one's.
        handOff = 0f
        val to = if (complete) (if (towardPlayer) 1f else 0f) else (if (towardPlayer) 0f else 1f)
        animateTo(scope, to, reduceMotion, velocity, then)
    }

    /** Opened by a tap, a button or an action: the same morph, finished on its own. */
    fun open(scope: CoroutineScope, reduceMotion: Boolean, then: (() -> Unit)? = null) {
        dragging = false
        opening = true
        animateTo(scope, 1f, reduceMotion, takeHandOff(), then)
    }

    /** Closed by Back, a button or a swipe that went far enough: the same morph, reversed. */
    fun close(scope: CoroutineScope, reduceMotion: Boolean, then: (() -> Unit)? = null) {
        dragging = false
        opening = false
        animateTo(scope, 0f, reduceMotion, takeHandOff(), then)
    }

    private fun animateTo(scope: CoroutineScope, to: Float, reduceMotion: Boolean, velocity: Float, then: (() -> Unit)?) {
        settleJob?.cancel()
        val from = progress
        if (from == to) {
            // Nothing to move: the place is already the one asked for (a tap on an open player, say).
            settleJob = null
            then?.invoke()
            return
        }
        settleJob = scope.launch {
            animate(
                from, to,
                animationSpec = MorphSettle.spec(
                    from, to, velocity,
                    ms = if (to > from) 340 else 300,
                    reduceMs = 140,
                    reduceMotion = reduceMotion,
                ),
            ) { v, _ -> progress = v.sanely(to) }
            then?.invoke()
        }
    }

    /** No animation: for a player restored already open. */
    fun snap(value: Float) {
        settleJob?.cancel()
        settleJob = null
        handOff = 0f
        dragging = false
        progress = if (value.isFinite()) value.coerceIn(0f, 1f) else 1f
    }

    // ---------------------------------------------------------------- the two ends of the morph, in the window

    /** The mini player's cover (kept by [CoverFlight.mini] as well, since that is where the cover started). */
    var miniTitle by mutableStateOf<Rect?>(null)
    var miniArtist by mutableStateOf<Rect?>(null)
    var miniPlay by mutableStateOf<Rect?>(null)
    var miniPrev by mutableStateOf<Rect?>(null)
    var miniNext by mutableStateOf<Rect?>(null)

    /** The full player's title, artist and controls, kept while the player is laid out. */
    var playerTitle by mutableStateOf<Rect?>(null)
    var playerArtist by mutableStateOf<Rect?>(null)
    var playerPlay by mutableStateOf<Rect?>(null)
    var playerPrev by mutableStateOf<Rect?>(null)
    var playerNext by mutableStateOf<Rect?>(null)

    internal fun note(which: Int, r: Rect) {
        when (which) {
            0 -> if (progress <= 0.001f) miniTitle = if (near(miniTitle, r)) miniTitle else r
            1 -> if (progress <= 0.001f) miniArtist = if (near(miniArtist, r)) miniArtist else r
            2 -> if (progress <= 0.001f) miniPlay = if (near(miniPlay, r)) miniPlay else r
            3 -> if (progress > 0.001f) playerTitle = if (near(playerTitle, r)) playerTitle else r
            4 -> if (progress > 0.001f) playerArtist = if (near(playerArtist, r)) playerArtist else r
            5 -> if (progress > 0.001f) playerPlay = if (near(playerPlay, r)) playerPlay else r
            6 -> if (progress <= 0.001f) miniPrev = if (near(miniPrev, r)) miniPrev else r
            7 -> if (progress <= 0.001f) miniNext = if (near(miniNext, r)) miniNext else r
            8 -> if (progress > 0.001f) playerPrev = if (near(playerPrev, r)) playerPrev else r
            9 -> if (progress > 0.001f) playerNext = if (near(playerNext, r)) playerNext else r
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

fun Modifier.miniPrevAnchor(): Modifier = onGloballyPositioned { PlayerMorph.note(6, it.boundsInRoot()) }

fun Modifier.miniNextAnchor(): Modifier = onGloballyPositioned { PlayerMorph.note(7, it.boundsInRoot()) }

/** The player's parts keep reporting while they move: the copies land exactly where they are when the player settles. */
fun Modifier.playerTitleAnchor(): Modifier = onGloballyPositioned { PlayerMorph.note(3, it.boundsInRoot()) }

fun Modifier.playerArtistAnchor(): Modifier = onGloballyPositioned { PlayerMorph.note(4, it.boundsInRoot()) }

fun Modifier.playerPlayAnchor(): Modifier = onGloballyPositioned { PlayerMorph.note(5, it.boundsInRoot()) }

fun Modifier.playerPrevAnchor(): Modifier = onGloballyPositioned { PlayerMorph.note(8, it.boundsInRoot()) }

fun Modifier.playerNextAnchor(): Modifier = onGloballyPositioned { PlayerMorph.note(9, it.boundsInRoot()) }

/** The mini player as it opens: it stays where it is (so the moving copies start exactly on it) and fades away. */
fun Modifier.playerMorphMini(): Modifier = graphicsLayer {
    // Gone a little past half-way: the player is all but opaque by then, and the copies carry the rest.
    alpha = 1f - (PlayerMorph.progress * 2.6f).coerceIn(0f, 1f)
}

/**
 * How a number a hand has set is finished: from where the hand left it, at the speed the hand was moving, into its
 * place. A settle used to be a fixed-length tween off a standstill, so letting go of a swipe stopped the player dead
 * and set it off again from nothing — the hitch heard as judder in opening and closing it.
 *
 * The curve starts at the release's speed and eases to a stop, arriving at the place rather than at a speed (a
 * transition that lands still moving has to stop there, which is the same hitch at the other end). The way left sets
 * the length: the last tenth doesn't take as long as the whole way, and a hard flick with a little way to go is over in
 * a moment instead of being slowed down first to be shown.
 */
internal object MorphSettle {
    /** The steepest start the cubic can have without turning back on itself (its first slope's ceiling). */
    private const val STEEPEST = 3f
    /** Under this a settle is over before it's seen: the hand's speed is eased a little instead of kept whole. */
    private const val BRIEFEST_MS = 90f

    /**
     * @param velocity the hand's speed in [from]/[to]'s units a second. Only the share toward the place counts: a hand
     *   let go the other way is finished from where it is, without winding up backwards first.
     * @param ms the whole settle's length at rest; [reduceMs] with Reduce motion, which is a plain fade as before.
     */
    fun spec(from: Float, to: Float, velocity: Float, ms: Int, reduceMs: Int, reduceMotion: Boolean): AnimationSpec<Float> {
        if (reduceMotion) return tween(reduceMs, easing = PrismTokens.Motion.Ease)
        val way = to - from
        val span = abs(way)
        if (span < 0.0001f) return tween(0)
        // Less way to go, less time: a tenth still to go shouldn't take as long as the whole way did.
        val base = ms * (0.35f + 0.65f * span.coerceAtMost(1f))
        val speed = if (way * velocity > 0f) abs(velocity) else 0f
        if (speed < 0.001f) return tween(base.roundToInt(), easing = PrismTokens.Motion.Ease)
        // The curve carries at most this many times the average speed off the mark, so a quick hand with a long way to
        // go needs less time than the way alone would ask for.
        val fitted = STEEPEST * span / speed * 1000f
        val length = fitted.coerceAtLeast(BRIEFEST_MS).coerceAtMost(base)
        val slope = (speed * length / 1000f / span).coerceIn(0f, STEEPEST)
        return tween(length.roundToInt(), easing = HandOff(slope))
    }

    /** 0 at the start and 1 at the end, off the mark at [slope] times the average speed and easing to a stop. */
    private class HandOff(private val slope: Float) : Easing {
        override fun transform(fraction: Float): Float {
            // An Easing is a fraction's curve: what it hands back is one too, whatever the float arithmetic of the
            // polynomial did at the ends. Compose's own curves are allowed to over- and under-shoot and hand an
            // out-of-range fraction straight back, so this one clamps both ends (an ULP past 1 reaches a corner
            // size — the crash by [sanely] above) and maps a value that isn't a number to the curve's start,
            // which coerceIn would otherwise let through.
            if (!fraction.isFinite()) return 0f
            val t = fraction.coerceIn(0f, 1f)
            val t2 = t * t
            return ((3f * t2 - 2f * t2 * t) + slope * (t2 * t - 2f * t2 + t)).coerceIn(0f, 1f)
        }
    }
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
        // A fraction (NaN included, which coerceIn passes through) never reaches the layer below.
        if (v.isFinite()) value = v.coerceIn(0f, 1f)
    }

    fun end() {
        dragging = false
    }

    fun animateTo(scope: CoroutineScope, to: Float, reduceMotion: Boolean, velocity: Float = 0f, then: (() -> Unit)? = null) {
        job?.cancel()
        val from = value
        if (from == to) {
            job = null
            then?.invoke()
            return
        }
        job = scope.launch {
            animate(from, to, animationSpec = MorphSettle.spec(from, to, velocity, ms = 320, reduceMs = 150, reduceMotion)) { v, _ -> value = v.sanely(to) }
            then?.invoke()
        }
    }
}

/** How far the lyrics, the queue and the stats rise as they fade in: the same 26 dp below their place. */
private const val PANEL_RISE_DP = 26f

/** A panel's rise at [fade] (0…1): the one value every panel's layer uses, so whichever opened it, it comes in alike. */
internal fun panelRise(fade: Float): Dp = PANEL_RISE_DP.dp * (1f - fade.coerceIn(0f, 1f))

/**
 * The cover, title, artist and transport controls moving between the mini player's places and the full player's. Drawn
 * only while a morph is in flight (and never with Reduce motion), over both players, with no input of its own: it's a
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
    val titleStyle = lerpStyle(typography.titleSmall, playerTitleStyle(typography, settings), t)
    val artistStyle = lerpStyle(typography.bodySmall, playerArtistStyle(typography, settings), t)
    // The words turn white sooner than they grow: over the player's dark picture they must stay readable all the way.
    val ink = (t * 1.6f).coerceAtMost(1f)
    val titleColor = lerp(scheme.onSurface, Color.White, ink)
    val artistColor = lerp(scheme.onSurfaceVariant, Color.White.copy(alpha = 0.6f), ink)
    if (fromTitle != null && toTitle != null) {
        MorphLine(title, fromTitle, toTitle, t, titleStyle, titleColor, "playerMorphTitle")
    }
    if (fromArtist != null && toArtist != null && artist.isNotBlank()) {
        MorphLine(artist, fromArtist, toArtist, t, artistStyle, artistColor, "playerMorphArtist")
    }
    if (fromPlay != null && toPlay != null) MorphPlay(fromPlay, toPlay, t, isPlaying)
    val fromPrev = PlayerMorph.miniPrev
    val toPrev = PlayerMorph.playerPrev
    if (fromPrev != null && toPrev != null) {
        MorphSkip(
            fromPrev, toPrev, t,
            miniIcon = PrismIcons.Previous,
            playerIcon = if (settings.transportStyle == TransportStyle.GLYPHS) PrismIcons.Backward else PrismIcons.Previous,
            enabled = PlayerMorph.prevEnabled,
            tag = "playerMorphPrev",
        )
    }
    val fromNext = PlayerMorph.miniNext
    val toNext = PlayerMorph.playerNext
    if (fromNext != null && toNext != null) {
        MorphSkip(
            fromNext, toNext, t,
            miniIcon = PrismIcons.Next,
            playerIcon = if (settings.transportStyle == TransportStyle.GLYPHS) PrismIcons.Forward else PrismIcons.Next,
            enabled = PlayerMorph.nextEnabled,
            tag = "playerMorphNext",
        )
    }
}

/**
 * The play control between its two homes. Its disc and glyph are the ones the full player draws: with "Play button" it
 * lands on the white disc and its black glyph, with "Rings" on the glass disc and its ring, and with "Classic" on the
 * bare oversized glyph. The moving copy used to keep one white glyph of its own, so only Classic landed without a jump.
 */
@Composable
private fun MorphPlay(from: Rect, to: Rect, t: Float, isPlaying: Boolean) {
    val settings = LocalAppSettings.current
    val scheme = MaterialTheme.colorScheme
    val style = settings.transportStyle
    val mini = settings.miniPlayerStyle
    val compact = PlayerMorph.playCompact
    val disc = when (style) {
        TransportStyle.GLYPHS -> 0.dp
        TransportStyle.BUTTON, TransportStyle.OUTLINE -> if (compact) 64.dp else 78.dp
    }
    val icon = when (style) {
        TransportStyle.GLYPHS -> if (compact) 58.dp else 74.dp
        TransportStyle.BUTTON -> disc * 0.56f
        TransportStyle.OUTLINE -> disc * 0.46f
    }
    val miniIcon = when (mini) {
        MiniPlayerStyle.CARD -> 28.dp
        MiniPlayerStyle.SLIM -> 26.dp
        MiniPlayerStyle.PILL -> 24.dp
    }
    val miniDisc = if (mini == MiniPlayerStyle.PILL) 40.dp else 0.dp
    val miniDiscColor = if (mini == MiniPlayerStyle.PILL) scheme.onSurface else Color.Transparent
    val discColor = when (style) {
        TransportStyle.GLYPHS -> Color.Transparent
        TransportStyle.BUTTON -> Color.White
        TransportStyle.OUTLINE -> Color.White.copy(alpha = 0.10f)
    }
    // The words turn white sooner than they grow; the control does the same on its way over the player's dark picture.
    val ink = (t * 1.6f).coerceAtMost(1f)
    val tint = lerp(if (mini == MiniPlayerStyle.PILL) scheme.surface else scheme.onSurface, if (style == TransportStyle.BUTTON) Color.Black else Color.White, ink)
    val ring = style != TransportStyle.GLYPHS && settings.view.playRing
    val r = lerpRect(from, to, t)
    Box(
        Modifier
            .clearAndSetSemantics { testTag = "playerMorphPlay" }
            .layout { m, c ->
                val w = r.width.roundToInt().coerceAtLeast(1)
                val h = r.height.roundToInt().coerceAtLeast(1)
                val p = m.measure(Constraints.fixed(w, h))
                layout(c.maxWidth, c.maxHeight) { p.place(r.left.roundToInt(), r.top.roundToInt()) }
            },
        contentAlignment = Alignment.Center,
    ) {
        val d = lerpDp(miniDisc, disc, t)
        if (d > 0.5.dp) Box(
            Modifier.size(d).clip(CircleShape).background(lerp(miniDiscColor, discColor, t)).drawWithContent {
                drawContent()
                // The ring comes in with the disc, not all at once at the end.
                if (ring) drawCircle(PrismTokens.spectrumRing(), radius = size.minDimension / 2f, style = Stroke(2.dp.toPx()), alpha = t)
            },
        )
        Icon(
            if (isPlaying) PrismIcons.Pause else PrismIcons.Play, null,
            Modifier.size(lerpDp(miniIcon, icon, t)),
            tint = tint,
        )
    }
}

/** Previous or next between its two homes, at the player's own glyph size, height and weight. */
@Composable
private fun MorphSkip(from: Rect, to: Rect, t: Float, miniIcon: ImageVector, playerIcon: ImageVector, enabled: Boolean, tag: String) {
    val settings = LocalAppSettings.current
    val scheme = MaterialTheme.colorScheme
    val compact = PlayerMorph.playCompact
    val glyphs = settings.transportStyle == TransportStyle.GLYPHS
    val size: Dp = (if (compact) 44.dp else 53.dp) * (if (glyphs) 1f else 0.82f)
    val heightScale = if (glyphs) 0.85f else 1f
    val ink = (t * 1.6f).coerceAtMost(1f)
    val tint = lerp(scheme.onSurface, Color.White, ink)
    // The player dims a control it can't use (the queue's ends); the copy dims with it.
    val weight = 1f + (if (enabled) 0f else -0.7f) * t
    val r = lerpRect(from, to, t)
    Box(
        Modifier
            .clearAndSetSemantics { testTag = tag }
            .layout { m, c ->
                val w = r.width.roundToInt().coerceAtLeast(1)
                val h = r.height.roundToInt().coerceAtLeast(1)
                val p = m.measure(Constraints.fixed(w, h))
                layout(c.maxWidth, c.maxHeight) { p.place(r.left.roundToInt(), r.top.roundToInt()) }
            },
        contentAlignment = Alignment.Center,
    ) {
        val glyph = Modifier.size(lerpDp(24.dp, size, t)).graphicsLayer { scaleY = 1f + (heightScale - 1f) * t }
        Box(Modifier.graphicsLayer { alpha = weight }, contentAlignment = Alignment.Center) {
            if (miniIcon === playerIcon) {
                Icon(playerIcon, null, glyph, tint = tint)
            } else {
                // Classic's glyph is a different one from the mini player's: they hand over rather than cut.
                Icon(miniIcon, null, glyph.graphicsLayer { alpha = 1f - ink }, tint = tint)
                Icon(playerIcon, null, glyph.graphicsLayer { alpha = ink }, tint = tint)
            }
        }
    }
}

/** One line of the hand-over: the words, at the size and place between [from] and [to] the finger has reached. */
@Composable
private fun MorphLine(text: String, from: Rect, to: Rect, t: Float, style: TextStyle, color: Color, tag: String) {
    val r = lerpRect(from, to, t)
    // The room the line has is the wider of its two homes, not a width of its own that changes every frame: it never
    // truncates for room the player's row has, and the ellipsis doesn't come and go as the copy moves.
    val room = morphRoom(from, to)
    Text(
        text,
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clearAndSetSemantics { testTag = tag }
            .layout { m, c ->
                val p = m.measure(Constraints(maxWidth = room))
                layout(c.maxWidth, c.maxHeight) {
                    p.place(r.left.roundToInt(), (r.top + (r.height - p.height) / 2f).roundToInt())
                }
            },
    )
}

/** The rectangle between two places, the way every moving part of the morph is placed. */
internal fun lerpRect(a: Rect, b: Rect, t: Float): Rect = Rect(
    a.left + (b.left - a.left) * t,
    a.top + (b.top - a.top) * t,
    a.right + (b.right - a.right) * t,
    a.bottom + (b.bottom - a.bottom) * t,
)

/**
 * The room a moving line of words is given: the wider of the two rows it belongs to. It is the same at every point of
 * the transition, so the line doesn't lose its ending to a width that is only on its way from one row to the other,
 * and can use all of the room the player's row has from the start.
 */
internal fun morphRoom(from: Rect, to: Rect): Int = maxOf(from.width, to.width).roundToInt().coerceAtLeast(1)
