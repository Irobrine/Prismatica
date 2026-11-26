package com.prism.music.ui.player

import com.prism.music.tr
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.data.prefs.PlayerPart
import com.prism.music.data.prefs.shows
import com.prism.music.data.prefs.showsMenuButton
import com.prism.music.data.prefs.drawsCover
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrain
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.offset as offsetConstraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.prism.music.R
import com.prism.music.data.canvas.CanvasArtwork
import com.prism.music.data.model.Song
import com.prism.music.data.model.hiRes
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.player.PlayerInk.inked
import com.prism.music.ui.Routes
import com.prism.music.ui.components.shareTarget
import com.prism.music.ui.components.tickWhileShown
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val THUMB_SIZE = 54.dp
private val HEADER_HEIGHT = 60.dp
private val ART_TITLE_GAP = 20.dp
private val DISMISS_STRIP = 32.dp
private val ART_BOX_TOP_PAD = 8.dp
/** How far the title may drop below the cover toward the scrubber, when the screen has room to spare. */
private val TITLE_DROP_MAX = 24.dp
/** What the like and ⋯ buttons beside the title take up (a centred title is balanced against it). */
private val TITLE_BUTTONS_WIDTH = 98.dp
/** The heart beside the title and the space after it, left out when the buttons row has Like. */
private val LIKE_GLYPH_ROOM = 48.dp
/** The space under each part of the deck when another follows it (as the deck was laid out before it could be arranged). */
private val DECK_GAP_AFTER = mapOf(
    com.prism.music.data.prefs.DeckPart.STRIP to 0.dp, com.prism.music.data.prefs.DeckPart.SCRUBBER to 8.dp,
    com.prism.music.data.prefs.DeckPart.TRANSPORT to 12.dp, com.prism.music.data.prefs.DeckPart.VOLUME to 6.dp,
    com.prism.music.data.prefs.DeckPart.BUTTONS to 10.dp,
)
private const val HERO_FADE = 0.42f
/** #293: "More room for the picture": the last quarter fades, not the last ~two fifths. */
private const val HERO_FADE_ROOMY = 0.24f

/** The deck's parts as they show, the gap after each one but the last, and room added under the last one. */
internal class DeckSpacing(val parts: List<com.prism.music.data.prefs.DeckPart>, val gaps: List<Dp>, val below: Dp)

/**
 * #329: the deck laid out from [shown] (its parts in the listener's order, the ones hidden from the player left out
 * already). A hidden volume bar leaves no hole of its own any more (its blank room sat between the controls and the
 * buttons row, so the gaps looked uneven): its room, kept so the controls and the cover above stay where they were
 * (1.6.5), is shared evenly between the gaps. [small]: Small over the lyrics or the queue, tight as before.
 * [tight] (#293): over a full-bleed picture that wants the room: a hidden volume bar keeps none, and the gaps are halved.
 * #361: the current lyric moved anywhere but over the progress bar sits midway between its neighbours.
 */
internal fun deckSpacing(shown: List<com.prism.music.data.prefs.DeckPart>, volumeShown: Boolean, small: Boolean, tight: Boolean = false): DeckSpacing {
    val volume = com.prism.music.data.prefs.DeckPart.VOLUME
    if (small) return DeckSpacing(shown, List((shown.size - 1).coerceAtLeast(0)) { 2.dp }, 0.dp)
    val hiddenVolume = !volumeShown && volume in shown
    val parts = if (hiddenVolume) shown - volume else shown
    if (tight) return DeckSpacing(parts, stripBalanced(parts, parts.dropLast(1).map { (DECK_GAP_AFTER[it] ?: 8.dp) / 2 }), 0.dp)
    // The room it had: the bar's own, and the gap under it when something followed.
    val spare = if (!hiddenVolume) 0.dp else VOLUME_ROW_HEIGHT + if (shown.last() != volume) DECK_GAP_AFTER.getValue(volume) else 0.dp
    val gaps = parts.dropLast(1).map { DECK_GAP_AFTER[it] ?: 8.dp }
    if (gaps.isEmpty()) return DeckSpacing(parts, gaps, spare)
    return DeckSpacing(parts, stripBalanced(parts, gaps.map { it + spare / gaps.size }), 0.dp)
}

/** Whether the current lyric at [index] of [parts] is in its first place, right over the progress bar, where it hugs the bar. */
internal fun stripOverScrubber(parts: List<com.prism.music.data.prefs.DeckPart>, index: Int): Boolean =
    parts.getOrNull(index + 1) == com.prism.music.data.prefs.DeckPart.SCRUBBER

/**
 * #361: [gaps] with the current lyric's two shared out evenly. Its own gap is none, to hug the progress bar beneath it, so
 * moved above anything else it stayed against that (1.8.1.1's shared-out room moved it further down still). The deck's
 * height stays the same, so nothing else moves.
 */
private fun stripBalanced(parts: List<com.prism.music.data.prefs.DeckPart>, gaps: List<Dp>): List<Dp> {
    val at = parts.indexOf(com.prism.music.data.prefs.DeckPart.STRIP)
    if (at !in 1 until parts.lastIndex || stripOverScrubber(parts, at)) return gaps
    val even = (gaps[at - 1] + gaps[at]) / 2
    return gaps.toMutableList().also { it[at - 1] = even; it[at] = even }
}

/**
 * How far the deck's current lyric has folded away over a panel (1 none of it left): with the panel's own number, and
 * with what the lyrics ask of it (its words are on the screen) or Small does (the deck is smaller over either panel).
 * The panel's number makes it go with the finger; the other two make it ease when what it answers to changes with the
 * panel standing still — from the lyrics to the queue, say.
 */
internal fun deckStripGone(panel: Float, lyrics: Float, small: Float, lyricsOnPlayer: Boolean): Float =
    if (!lyricsOnPlayer) 1f else minOf(maxOf(lyrics, small), panel).coerceIn(0f, 1f)

/**
 * The room [room] (0..1) a part of the deck takes: its whole height, or, between, the part squeezed into the room it has
 * — its bottom edge held where the part below it sits, its top cut off and faded — so a fold never moves what is under
 * it. Its own height is what the deck is summed from, so the deck's height needs no second animation of its own.
 */
internal fun Modifier.deckRoom(room: Float): Modifier {
    val r = room.coerceIn(0f, 1f)
    if (r >= 1f) return this
    // The layer is outside the smaller layout, so what's cut off at the top is clipped by the room itself, not drawn
    // over the part above it.
    return this
        .graphicsLayer {
            clip = true
            alpha = r
        }
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val room = (placeable.height * r).roundToInt()
            layout(placeable.width, room) { placeable.place(0, room - placeable.height) }
        }
}

/**
 * Whether the full-bleed cover is up: in full-bleed mode, once there's a picture to show, this song's or (#244) the
 * last one's while this one loads, or an animated cover.
 */
internal fun heroTarget(heroMode: Boolean, picture: Boolean, canvas: Boolean): Boolean = heroMode && (picture || canvas)

/**
 * #250: the player draws the band behind the navigation buttons itself on Android 15 and later ([sdk] 35+), where a
 * window's navigation bar color is ignored for apps targeting 35+; below that the window's color is the band.
 */
internal fun drawsNavBand(sdk: Int, on: Boolean): Boolean = on && sdk >= 35

/** The band's color: dark under the player, light over its White background (as the window's color below Android 15). */
internal fun navBandColor(light: Boolean): Color = if (light) Color(0x80F2F2F4) else Color(0x801B1B1B)

/**
 * The band behind three-button navigation (#250, Android 15+): as tall as the buttons' bar, from the tappable-element
 * insets, which are zero with gesture navigation (its handle isn't tappable), so there's no band there.
 */
@Composable
private fun PlayerNavBand(light: Boolean, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val buttons = WindowInsets.tappableElement.getBottom(density)
    if (buttons <= 0) return
    Box(modifier.fillMaxWidth().height(with(density) { buttons.toDp() }).background(navBandColor(light)).testTag("playerNavBand"))
}

/**
 * The cover's size: a little smaller while paused when asked, unless the listener chose Reduce motion themselves. A
 * Full width cover ([fullWidth]) stays edge to edge (#276: it shrank away from the screen's sides).
 */
internal fun pausedCoverScale(playing: Boolean, shrinkWhenPaused: Boolean, ownReduceMotion: Boolean, fullWidth: Boolean = false): Float =
    if (playing || !shrinkWhenPaused || ownReduceMotion || fullWidth) 1f else 0.86f

/**
 * The player's cover, [width] by [height], in a box [maxWidth] by [maxHeight] (the player's side margins [extra] wide
 * each, which Large and Full width reach into). It's square as big as the box lets it be; a Full width cover ([full])
 * always meets the screen's sides (#276: on most phones the player is too short for a square that wide, so Full kept the
 * margins in the Standard layout, looking just like Large), its top and bottom then cropped a little instead.
 */
internal fun coverBox(maxWidth: Dp, maxHeight: Dp, extra: Dp, full: Boolean, titleRoom: Dp = ART_TITLE_GAP + HEADER_HEIGHT, least: Dp = THUMB_SIZE): Pair<Dp, Dp> {
    val across = maxWidth + extra * 2
    val side = minOf(across, maxHeight - titleRoom).coerceAtLeast(least)
    return (if (full) maxOf(side, across) else side) to side
}

/**
 * [width] by [height] (a square when they're the same); it may be wider than the slot (a Large / Full width cover
 * reaches into the side margins). Taller than the slot, both sides give up the same.
 */
private fun Modifier.measuredBox(width: () -> Dp, height: () -> Dp): Modifier = layout { measurable, constraints ->
    val wanted = height().roundToPx()
    val h = wanted.coerceAtMost(constraints.maxHeight)
    val w = (width().roundToPx() - (wanted - h)).coerceAtLeast(0)
    val p = measurable.measure(Constraints.fixed(w, h))
    layout(minOf(p.width, constraints.maxWidth), p.height) { p.placeRelative(0, 0) }
}

@Composable
fun rememberPosition(fast: Boolean, hold: Boolean = false): Long = rememberPositionState(fast, hold).value

/**
 * The playhead as state, refreshed four times a second ([fast]: about 16). Read it where it's drawn (a draw block,
 * `graphicsLayer`) and only that redraws as the song plays, rather than everything around it recomposing (1.7.4: the
 * mini player, glass and all, was rebuilt four times a second over every screen).
 */
@Composable
fun rememberPositionState(fast: Boolean, hold: Boolean = false): androidx.compose.runtime.State<Long> {
    val pc = LocalContainer.current.player
    val pos = remember { mutableLongStateOf(pc.position) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    // [hold]: hidden under a sheet, the player isn't recomposed four times a second for a clock nobody sees; nor while
    // Prism is out of sight (the screen off, another app in front).
    LaunchedEffect(fast, hold, lifecycle) {
        pos.longValue = pc.position
        if (!hold) lifecycle.tickWhileShown(if (fast) 60 else 250) { pos.longValue = pc.position }
    }
    return pos
}


/**
 * Now Playing, modelled on BitChord v1.7 (itself after Apple Music): artwork
 * that shrinks while paused, a hairline scrubber, oversized transport glyphs, a
 * volume capsule, and lyrics / Song·Video / queue along the bottom. Lyrics and
 * the queue live inside the player; the sleeve collapses into a header for them.
 */
@Composable
fun NowPlayingScreen(onCollapse: () -> Unit) {
    val c = LocalContainer.current
    val pc = c.player
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val view = LocalView.current
    val settings by c.settings.flow.collectAsState()
    val playingSong by pc.currentSong.collectAsState()
    // Stopping clears the song at once (the player's own stop gesture, the end of a queue). The screen keeps the last
    // one for its slide away, instead of going blank on that frame and cutting out as the player closes.
    val lastSong = remember { mutableStateOf<Song?>(null) }
    LaunchedEffect(playingSong) { if (playingSong != null) lastSong.value = playingSong }
    val song = playingSong ?: lastSong.value ?: return
    val isPlaying by pc.isPlaying.collectAsState()
    val buffering by pc.buffering.collectAsState()
    val videoMode by pc.videoMode.collectAsState()
    val videoAvailable by pc.videoAvailable.collectAsState()
    val hasNext by pc.hasNext.collectAsState()
    val hasPrevious by pc.hasPrevious.collectAsState()
    val liked by c.library.likedIds.collectAsState()

    // #280: "Reopen on the last page". Nothing's read or written with it off.
    val pageMemory = remember(settings.behavior.playerRemembersPage) { if (settings.behavior.playerRemembersPage) PlayerPageMemory(view.context) else null }
    // A page asked for as the player opens (a mini player or cover gesture naming the queue or the lyrics) wins over the
    // remembered one for this open; then, as before, the page kept from last time. A page asked for by a gesture morphs
    // in with the player (the panel starts at the cover's end); a remembered one is simply there, as before.
    val askedOnOpen = remember { PlayerMorph.takeRequestedPage() }
    val opensOn = remember { askedOnOpen ?: pageMemory?.opensOn(song.isEpisode) ?: PlayerPage.COVER }
    // Opened on a page, its controls start as opening that page would start them ("Full screen" hides them).
    val openPanelControls = opensOn == PlayerPage.COVER || settings.view.panelPlayer != com.prism.music.data.prefs.PanelPlayer.HIDDEN
    var lyricsOpen by rememberSaveable { mutableStateOf(opensOn == PlayerPage.LYRICS) }
    var queueOpen by rememberSaveable { mutableStateOf(opensOn == PlayerPage.QUEUE) }
    var lyricsControlsOpen by remember { mutableStateOf(openPanelControls) }
    var queueControlsOpen by remember { mutableStateOf(openPanelControls) }
    var showProviders by remember { mutableStateOf(false) }
    var statsOpen by rememberSaveable { mutableStateOf(false) }
    var showActions by remember { mutableStateOf(false) }
    /** The song menu was opened by a swipe up on the cover: the player stays collapsed behind it, as for a panel. */
    var menuBySwipe by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showTiming by remember { mutableStateOf(false) }
    var showSing by remember { mutableStateOf(false) }
    var showEnhance by remember { mutableStateOf(false) }
    // Opened from the player's own buttons (1.7.4): where the sound goes, a playlist to add to, sharing.
    var showOutputs by remember { mutableStateOf(false) }
    var showPlaylists by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    // 1.8.5: the snoozed tracks' list, opened by holding the Snooze button; [snoozeFocus] is that button's song (the
    // list opens at it when it's snoozed), or null when opened from Settings.
    var showSnoozes by remember { mutableStateOf(false) }
    var snoozeFocus by remember { mutableStateOf<String?>(null) }
    var fullscreenVideo by rememberSaveable { mutableStateOf(false) }
    var videoFill by rememberSaveable { mutableStateOf(true) }

    // A sheet over the player (⋯, lyrics source, sleep timer): what moves underneath it every frame (the animated
    // cover, the word sweep, the title marquee) holds still, since both windows draw on the same thread and dragging
    // the sheet stuttered. The timing sheet isn't counted: the lyrics are what's being lined up there.
    val covered = showActions || showProviders || showSleep || showSing || showOutputs || showPlaylists || showShare || showSpeed || showSnoozes
    // Where the lyrics are on show (the lyrics view, immersive mode, the source sheet): with "Lyrics on mobile data"
    // off, that's when they're looked up on mobile data (#95).
    // Settings → Player → Layout (1.7.4); a wide player (tablet, landscape) keeps its own.
    val wideNow = com.prism.music.ui.theme.LocalWindowLayout.current.let { it.twoPanePlayer || it.shortLandscape || it.tabletop }
    val layout = if (wideNow) com.prism.music.data.prefs.PlayerLayout.STANDARD else settings.playerLayout
    val lyricsState = rememberLyricsState(song, opened = lyricsOpen || settings.immersivePlayer || showProviders || layout == com.prism.music.data.prefs.PlayerLayout.LYRICS)
    // With the lyrics in, the best part can start where a line does (or come from the chorus, without a heat curve).
    LaunchedEffect(song.id, lyricsState.lyrics) { if (lyricsState.lyrics?.synced == true) c.bestParts.refine(song) }
    // The playhead is read only where it's shown (the scrubber), so the player isn't recomposed as the song plays.
    val position = rememberPositionState(fast = false, hold = covered)
    val pastStart by remember { derivedStateOf { position.value > 3000 } }
    val volume = rememberPlayerVolume()
    val scope = rememberCoroutineScope()
    // A wide window (tablet, foldable, phone on its side) shows the cover and controls beside lyrics / the queue.
    val window = com.prism.music.ui.theme.LocalWindowLayout.current
    val wide = window.twoPanePlayer || window.shortLandscape || window.tabletop
    // A wide player has its own panes, always showing one of them: only a phone's page is remembered (below, once the
    // panel's morph has settled: a pull that turned back is not a page the player was left on).

    // The player is always dark, so it owns light system-bar icons, and the navigation bar's scrim (three-button
    // navigation) is a dark one: in light mode a light grey strip sat under the player.
    // Player → Show on the player → "Band behind the navigation buttons" (#250, 1.7.9.1): off, the player shows through.
    val navBand = settings.playerNavBand
    DisposableEffect(navBand) {
        val window = (view.context as? Activity)?.window
        val ctl = window?.let { WindowCompat.getInsetsController(it, view) }
        val previous = ctl?.isAppearanceLightStatusBars
        val previousNav = ctl?.isAppearanceLightNavigationBars
        ctl?.isAppearanceLightStatusBars = false
        ctl?.isAppearanceLightNavigationBars = false
        @Suppress("DEPRECATION")
        val previousNavColor = window?.navigationBarColor
        @Suppress("DEPRECATION")
        window?.navigationBarColor = if (navBand) android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b) else android.graphics.Color.TRANSPARENT
        // Android's own scrim behind three buttons (10+) would put a band back. On 15+ the color above isn't drawn (edge to
        // edge is enforced) and the player draws its band itself ([PlayerNavBand]), so the system's scrim stays off there.
        val previousEnforced = if (android.os.Build.VERSION.SDK_INT >= 29) window?.isNavigationBarContrastEnforced else null
        if (android.os.Build.VERSION.SDK_INT >= 29 && (!navBand || android.os.Build.VERSION.SDK_INT >= 35)) window?.isNavigationBarContrastEnforced = false
        onDispose {
            if (previous != null) ctl.isAppearanceLightStatusBars = previous
            if (previousNav != null) ctl.isAppearanceLightNavigationBars = previousNav
            @Suppress("DEPRECATION")
            if (previousNavColor != null) window.navigationBarColor = previousNavColor
            if (android.os.Build.VERSION.SDK_INT >= 29 && previousEnforced != null) window?.isNavigationBarContrastEnforced = previousEnforced
        }
    }
    // Lyrics on screen keep it awake (a wide player shows them unless its other pane is picked), and so does a music
    // video while it plays (GitHub #169: the screen went off mid-video).
    val lyricsInView = lyricsOpen || (wide && !queueOpen && !statsOpen)
    val awake = lyricsInView || (videoMode && isPlaying)
    DisposableEffect(awake) {
        view.keepScreenOn = awake
        onDispose { view.keepScreenOn = false }
    }

    // Settings → Player → "Lyrics and the queue" (#193, #197): the controls hide after a moment (as before), always stay,
    // or start hidden so the lyrics and the queue get the whole screen (a tap or a swipe up brings them).
    val panelPlayer = settings.view.panelPlayer
    // Small (#230): they stay too, in less room.
    val controlsStay = panelPlayer == com.prism.music.data.prefs.PanelPlayer.ALWAYS || panelPlayer == com.prism.music.data.prefs.PanelPlayer.SMALL
    val controlsAtOpen = panelPlayer != com.prism.music.data.prefs.PanelPlayer.HIDDEN
    val openLyrics = { lyricsControlsOpen = controlsAtOpen; lyricsOpen = true; queueOpen = false; statsOpen = false }
    val closeLyrics = { lyricsControlsOpen = false; lyricsOpen = false }
    // #215: a recognized song played opens on its lyrics (taken once, by that song).
    LaunchedEffect(PlayerAsks.lyricsFor, song.id) {
        if (PlayerAsks.lyricsFor == song.id) { PlayerAsks.lyricsFor = null; openLyrics() }
    }
    val toggleQueue = {
        val opening = !queueOpen
        queueOpen = opening
        if (opening) { closeLyrics(); statsOpen = false; queueControlsOpen = controlsAtOpen }
    }
    val toggleStats = {
        val opening = !statsOpen
        statsOpen = opening
        if (opening) { closeLyrics(); queueOpen = false }
    }
    // Sing's controls up over the lyrics: the deck stays (it holds them), and comes back when they're opened.
    val singing by c.sing.on.collectAsState()
    // Sing's button held as a slider (1.7.8, V): the deck stays up while it's in use.
    val singControls = singing && SingUi.holding
    LaunchedEffect(singControls) { if (singControls) lyricsControlsOpen = true }
    LaunchedEffect(lyricsOpen, lyricsControlsOpen, volume.dragging, singControls, controlsStay) {
        if (lyricsOpen && lyricsControlsOpen && !volume.dragging && !singControls && !controlsStay) { delay(5000); lyricsControlsOpen = false }
    }
    // Cover layout: a tap on the cover brings the controls up; they go again after a few seconds of music.
    var coverDeck by remember { mutableStateOf(false) }
    // A playing music video does the same when "Controls hide over videos" is on (#166): they show as the video starts,
    // go after a few seconds, and a tap on the video brings them; they stay away from song to song.
    val videoHidesDeck = settings.view.videoControlsHide && videoMode
    val deckOnTap = layout == com.prism.music.data.prefs.PlayerLayout.COVER || videoHidesDeck
    LaunchedEffect(videoHidesDeck) { if (videoHidesDeck) coverDeck = true }
    LaunchedEffect(coverDeck, isPlaying, volume.dragging, deckOnTap) {
        if (coverDeck && isPlaying && !volume.dragging && deckOnTap) { delay(5000); coverDeck = false }
    }
    // #293: "Video buttons wait for a tap": shown by a tap on the video, gone again after a moment.
    var videoChips by remember { mutableStateOf(false) }
    LaunchedEffect(videoChips) { if (videoChips) { delay(5000); videoChips = false } }
    // Lyrics layout: each song opens on its lyrics once they're in (a song without any stays on the cover).
    val hasLyrics = lyricsState.lyrics != null
    LaunchedEffect(song.id, hasLyrics, layout) {
        if (layout == com.prism.music.data.prefs.PlayerLayout.LYRICS && hasLyrics && !queueOpen && !statsOpen && !lyricsOpen) openLyrics()
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Sideways, a video fills the screen; its exit (or Back) keeps it in the player instead, until the phone turns or the
    // video's switched off (#245: on a Fold held sideways it went straight back to full screen, no way out but turning it).
    var sidewaysInPlayer by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(landscape, videoMode) { if (!landscape || !videoMode) sidewaysInPlayer = false }
    val sidewaysFull = landscape && !sidewaysInPlayer
    fun leaveFullscreenVideo() {
        fullscreenVideo = false
        if (landscape) sidewaysInPlayer = true
    }
    // How far a swipe can pull the player down, as a share of the window, before it's closing for good: the same 10%
    // (and the same number, PlayerMorph) the mini player's swipe up uses, so both ends of the change feel alike.
    val screenPx = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val morphSpan = screenPx * PlayerMorph.TRAVEL
    var closing by remember { mutableStateOf(false) }
    fun slideClosed() {
        if (closing) return
        closing = true
        // Back, the menu, a stop, a swipe: the same morph back to the mini player, from wherever the finger left it.
        onCollapse()
        // Opened again while it leaves, this is the same player still: it has to be able to close again.
        scope.launch { delay(if (settings.reduceMotion) 160 else 380); closing = false }
    }
    // Pulled down (or sliding closed), the page under the player shows again: its backgrounds move again.
    val cover = com.prism.music.ui.components.LocalPlayerCover.current
    LaunchedEffect(cover) {
        snapshotFlow { PlayerMorph.progress < 1f || closing }.collect { cover.pulled = it }
    }
    DisposableEffect(cover) { onDispose { cover.pulled = false } }
    // Opened again before it had gone, this is the same player still, part-way down or away: it comes back to its place,
    // and can close again. (The morph itself is the one already running: it's turned around, not restarted.)
    LaunchedEffect(cover) {
        snapshotFlow { cover.leaving }.collect { leaving ->
            if (!leaving && closing) closing = false
        }
    }
    // While the player is sliding closed, slideClosed() returns early: an enabled handler would swallow Back for the
    // whole 380 ms window. Letting go of the handler as soon as the close starts keeps Back live during that time;
    // the dismissal itself (the morph and its delay) is untouched.
    BackHandler(enabled = !closing) {
        when {
            fullscreenVideo || (videoMode && sidewaysFull) -> leaveFullscreenVideo()
            // A wide player always shows its lyrics pane: "open" (left from portrait) isn't something Back undoes there.
            // #280: with "Reopen on the last page", Back leaves the player on that page: stepping back to the cover first
            // would make the cover the last page every time.
            lyricsOpen && !wide -> if (pageMemory != null) slideClosed() else closeLyrics()
            queueOpen -> if (pageMemory != null && !wide) slideClosed() else queueOpen = false
            statsOpen -> statsOpen = false
            else -> slideClosed()
        }
    }
    // Picture-in-picture: a music video playing here floats over other apps when the listener
    // leaves Prism (or taps the PiP button); the window then shows just the video.
    val inPip = LocalPip.current
    val activity = view.context as? com.prism.music.MainActivity
    DisposableEffect(videoMode, isPlaying, activity) {
        val size = pc.player?.videoSize
        activity?.setPipEligible(
            videoMode && isPlaying,
            ((size?.width ?: 0) * (size?.pixelWidthHeightRatio ?: 1f)).toInt(), size?.height ?: 0,
        )
        onDispose { activity?.setPipEligible(false) }
    }
    if (videoMode && (fullscreenVideo || sidewaysFull || inPip)) {
        FullscreenVideo(song, forceLandscape = fullscreenVideo && !inPip, pip = inPip, onExit = { leaveFullscreenVideo() })
        return
    }

    // ---------------------------------------------------------------- Motion
    // The panels' morph (cover into the header, the deck down) follows the finger on a swipe (PanelShift), and eases to
    // match the flags when a button, Back or an action opened them. It is one number, as the player's own morph is.
    val panelShift = remember { PanelShift(if (opensOn != PlayerPage.COVER && askedOnOpen == null) 1f else 0f) }
    // The display's fastest mode is held by the panel's own ease, from its first frame (see PanelShift.animateTo).
    LaunchedEffect(lyricsOpen, queueOpen, statsOpen, menuBySwipe, showActions) {
        if (panelShift.dragging) return@LaunchedEffect
        val open = lyricsOpen || queueOpen || statsOpen || (menuBySwipe && showActions)
        // The panel's content is composed on this frame (a big list's queue, the lyrics): let that frame pass, invisible,
        // before the rise and fade start, so its first animation frame isn't the one carrying the composition.
        if (open && panelShift.value < 0.01f) androidx.compose.runtime.withFrameNanos { }
        panelShift.animateTo(scope, if (open) 1f else 0f, settings.reduceMotion)
        // The menu has gone: the player opens back up, and the swipe isn't holding it collapsed any more.
        if (menuBySwipe && !showActions) menuBySwipe = false
    }
    // The queue stays composed while it fades out on close (it used to be cut at once while the deck rose under it);
    // removed once the morph has reached the cover. A player that is closing drops it at once: a panel still fading
    // must not be drawn (and faded offscreen) under the player's own close.
    val showQueuePanel by remember {
        derivedStateOf {
            (queueOpen || panelShift.value > 0.001f) &&
                (!closing || com.prism.music.ui.player.PlayerMorph.progress >= 1f)
        }
    }
    val p: () -> Float = { panelShift.value }
    // The thresholds that steer composable branches go through derived states: each flips only when the panel's number
    // crosses it, where a direct read invalidated this whole composition on every frame of the panel's morph.
    val collapsePastHalf by remember { derivedStateOf { panelShift.value >= 0.5f } }
    val panelFull by remember { derivedStateOf { panelShift.value >= 1f } }
    val panelAlmostClosed by remember { derivedStateOf { panelShift.value < 0.999f } }
    val panelAtStart by remember { derivedStateOf { panelShift.value < 0.01f } }
    // What the player was left showing is kept once the panel has settled (not from a pull that turned back).
    LaunchedEffect(lyricsOpen, queueOpen, pageMemory, wide, panelFull) {
        if (!wide && panelFull) pageMemory?.keep(PlayerPage.of(lyricsOpen, queueOpen))
    }
    // Settings → Player → What's on the player (1.8.0): a hidden part leaves the player; the cover, title and buttons come
    // back in the header over the lyrics and the queue, where they say which song it is.
    val inHeader = { part: PlayerPart -> settings.shows(part) || collapsePastHalf }
    // The panel itself comes in with the morph's last part, and follows a finger pulling it away. Its number is read
    // where it is used, inside the three layers below.
    val panelFade: () -> Float = { ((panelShift.value - 0.55f) / 0.45f).coerceIn(0f, 1f) }
    // The listener's own Reduce motion, not Performance mode's (Reddit, 1.7.9.3: Performance mode turns Reduce motion on,
    // which took the shrink away too; it's one scale of one layer, nothing to save).
    val ownReduceMotion = c.settings.stored.collectAsState().value.reduceMotion
    val artScale by animateFloatAsState(
        pausedCoverScale(isPlaying, settings.artShrinkOnPause, ownReduceMotion, fullWidth = settings.coverSize == com.prism.music.data.prefs.CoverSize.FULL),
        spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "artScale",
    )
    val swipe = remember { DragOffset() }
    val haptics = com.prism.music.ui.theme.rememberHaptics()
    var seekFlash by remember { mutableStateOf<SeekFlash?>(null) }
    // Kept for the label's fade out (it isn't cleared on the same frame it leaves).
    val lastSeek = remember { mutableStateOf<SeekFlash?>(null) }
    LaunchedEffect(seekFlash?.at) {
        seekFlash?.let { lastSeek.value = it }
        if (seekFlash != null) { delay(750); seekFlash = null }
    }

    // ---------------------------------------------------------------- Canvas
    val metered = remember { context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true }
    val savedCanvases by c.canvas.saved.collectAsState()
    val canvasSaved = savedCanvases.has(song.id, com.prism.music.data.canvas.CanvasRepository.albumKey(song))
    val songCanvas by produceState<CanvasArtwork?>(c.canvas.cached(song), song.id, song.album?.title, settings.canvasEnabled) {
        // Saved clips play from the phone, so mobile data doesn't matter for them.
        if (!settings.canvasEnabled || (metered && (!settings.canvasOnCellular || settings.dataSaver) && !c.canvas.isSaved(song))) { value = null; return@produceState }
        val known = c.canvas.cached(song)
        value = known
        if (known == null) delay(500)
        value = c.canvas.canvasFor(song)
    }
    // Animated artwork can be switched off per song from the song options.
    val canvasHidden by c.canvas.hidden.collectAsState()
    val canvas = songCanvas.takeIf { !videoMode && song.id !in canvasHidden }
    var canvasCover by remember(canvas) { mutableFloatStateOf(0f) }

    // ---------------------------------------------------------------- Artwork
    // The cover picked for the song or its album (#305), else its own.
    val songArt = c.covers.art(song)
    val artUrl = hiRes(songArt, 1200)
    // Decoded at full size whatever size the cover is first laid out at (#86): opened mid-animation or with lyrics
    // open it starts out small, and the picture decoded for that size was then stretched over the whole cover.
    val artRequest = remember(artUrl, settings.fullBleedArtwork) { playerCoverRequest(context, artUrl, settings.fullBleedArtwork) }
    var artLoaded by remember(artUrl) { mutableStateOf(false) }
    // Vinyl: the cover is a record (its own shape, no edge-to-edge banner).
    val vinyl = layout == com.prism.music.data.prefs.PlayerLayout.VINYL && !videoMode
    // Cassette (1.7.4): the cover on a tape, its reels turning.
    val cassette = layout == com.prism.music.data.prefs.PlayerLayout.CASSETTE && !videoMode
    val heroMode = settings.fullBleedArtwork && !videoMode && !wide && !vinyl && !cassette
    // The animated cover plays behind the whole player instead of in the cover (not with full-bleed artwork, which
    // already shows it edge to edge).
    val canvasBehind = settings.canvasFillsBackground && !heroMode && !videoMode && !wide
    // Full-bleed video (#120): the video runs edge to edge from the top of the screen and fades into the background,
    // in the full-bleed cover's place; the box it would sit in keeps only its buttons.
    val videoBehind = settings.videoFullBleed && videoMode && !wide
    // #293: "More room for the picture": over a full-bleed picture the controls close up, the picture takes what they
    // give back, and it fades later.
    val bleedRoomy = settings.view.bleedRoomy && (heroMode || videoBehind)
    val heroFade = if (bleedRoomy) HERO_FADE_ROOMY else HERO_FADE
    var heroHeight by remember { mutableStateOf(0.dp) }
    // #244 (1.7.9.3): once the full-bleed cover has shown it stays up while the next song's picture loads, over the last
    // one (it used to drop to the square cover for every new song, then grow back, a jump on each skip).
    var heroPicture by remember { mutableStateOf<coil3.request.ImageRequest?>(null) }
    LaunchedEffect(heroMode) { if (!heroMode) heroPicture = null }
    val heroT = animateFloatAsState(if (heroTarget(heroMode, artLoaded || heroPicture != null, canvasCover > 0f)) 1f else 0f, tween(420, easing = FastOutSlowInEasing), label = "hero")
    val heroVisible: () -> Float = { heroT.value * (1f - p()) }
    // #232, #338: only one of the two is worked out, for the background that's up.
    val onGradient = settings.playerBackground == com.prism.music.data.prefs.PlayerBackground.GRADIENT
    val mesh = rememberArtworkMesh(songArt.takeUnless { onGradient })
    val coverGradient = rememberCoverGradient(songArt.takeIf { onGradient })
    val blurImage = rememberFullArtworkBlur(songArt, settings.playerBlur)
    val fullBlur by animateFloatAsState(if (lyricsOpen || queueOpen || statsOpen) 1f else 0f, tween(360, easing = FastOutSlowInEasing), label = "fullBlur")
    // #320: over the White background the type and controls are dark ([PlayerInk]); over the lyrics and the queue (their
    // dark blurred cover), a canvas or a video behind, a wide player or immersive mode they're white as ever.
    val material = com.prism.music.ui.theme.LocalUi.current.material
    val inkLight = (settings.playerBackground == com.prism.music.data.prefs.PlayerBackground.WHITE ||
        (material && !com.prism.music.ui.theme.LocalIsDark.current && settings.playerBackground == com.prism.music.data.prefs.PlayerBackground.THEME)) &&
        !wide && !canvasBehind && !videoBehind &&
        fullBlur < 0.5f && !(settings.immersivePlayer && (!videoMode || settings.view.immersiveVideo))
    // These are global snapshot states: writing the same value on every recomposition invalidated every reader for
    // nothing. Only write when the value actually changes.
    if (PlayerInk.light != inkLight) PlayerInk.light = inkLight
    val accentAdapted = inkLight && material
    if (PlayerInk.accentAdapted != accentAdapted) PlayerInk.accentAdapted = accentAdapted
    DisposableEffect(Unit) { onDispose { PlayerInk.light = false; PlayerInk.accentAdapted = false } }
    // The status bar's and the navigation bar's icons dark over it too.
    DisposableEffect(inkLight, settings.playerNavBand) {
        if (!inkLight) return@DisposableEffect onDispose {}
        val window = (view.context as? Activity)?.window
        val ctl = window?.let { WindowCompat.getInsetsController(it, view) }
        ctl?.isAppearanceLightStatusBars = true
        ctl?.isAppearanceLightNavigationBars = true
        @Suppress("DEPRECATION")
        val previousNavColor = window?.navigationBarColor
        @Suppress("DEPRECATION")
        if (settings.playerNavBand) window?.navigationBarColor = android.graphics.Color.argb(0x80, 0xf2, 0xf2, 0xf4)
        onDispose {
            ctl?.isAppearanceLightStatusBars = false
            ctl?.isAppearanceLightNavigationBars = false
            @Suppress("DEPRECATION")
            if (previousNavColor != null) window.navigationBarColor = previousNavColor
        }
    }
    val statusTop = statusBarRoom().asPaddingValues().calculateTopPadding()
    // Collapsed into the header thumbnail, the sleeve keeps gentle corners whatever the setting.
    // A Full width cover meets the screen's edges, so it has square corners until it shrinks into the header.
    val artCorners = if (settings.coverSize == com.prism.music.data.prefs.CoverSize.FULL) 0.dp else settings.playerArtCorners.dp
    // Another shape picked for the player's cover (1.7.7) keeps it all the way into the header. Its corner follows the
    // panel's number, but the shape is also the shadow's and the play flash's, both composition-level arguments: moving
    // just the clip to a graphicsLayer would change how those land, so this read stays here.
    val artShape = when {
        material -> RoundedCornerShape(lerp(artCorners, 8.dp, panelShift.value))
        vinyl -> CircleShape
        settings.shapes.player != com.prism.music.data.prefs.CoverShape.ROUNDED && !cassette ->
            com.prism.music.ui.theme.CoverShapes.of(settings.shapes.player, artCorners)
        else -> RoundedCornerShape(lerp(artCorners, 8.dp, panelShift.value))
    }
    val roomyCover = settings.coverSize != com.prism.music.data.prefs.CoverSize.STANDARD

    // ---------------------------------------------------------------- Visualizer (1.7.4, off by default)
    // Runs only while it's picked, the song plays and the player is in view (not under a sheet, not a video, not with
    // Reduce motion): otherwise nothing listens and nothing redraws.
    val vizStyle = settings.visualizer
    val viz = rememberVisualizer(
        vizStyle != com.prism.music.data.prefs.VisualizerStyle.OFF && !settings.reduceMotion && !covered && !videoMode && isPlaying,
    )
    val vizBrush: Brush = if (viz == null) androidx.compose.ui.graphics.SolidColor(Color.White) else
        scrubberFill(settings.visualizerColor, songArt, adapt = PlayerInk.accentAdapted, onLight = PlayerInk.light && material)
            ?: Brush.horizontalGradient(*com.prism.music.ui.theme.PrismTokens.spectrumColors(true).toTypedArray())
    val vizColor: Color = (vizBrush as? androidx.compose.ui.graphics.SolidColor)?.value
        ?: com.prism.music.ui.theme.PrismTokens.spectrumColors(true).let { it[it.size / 2].second }

    // Only ever the song's album (tapping it opens the album); nothing for songs without one.
    val originAlbum = song.album?.takeIf { it.id != null && it.title.isNotBlank() }

    // The bottom row's buttons that open something over the player (Settings → Player → Buttons).
    val onButton: (com.prism.music.data.prefs.PlayerButton) -> Unit = { b ->
        when (b) {
            com.prism.music.data.prefs.PlayerButton.SLEEP -> showSleep = true
            com.prism.music.data.prefs.PlayerButton.OUTPUT -> showOutputs = true
            com.prism.music.data.prefs.PlayerButton.ADD_TO_PLAYLIST -> showPlaylists = true
            com.prism.music.data.prefs.PlayerButton.SHARE -> showShare = true
            com.prism.music.data.prefs.PlayerButton.SPEED -> showSpeed = true
            // Sing on (with the lyrics opening) or off; the download sheet the first time.
            com.prism.music.data.prefs.PlayerButton.SING -> {
                if (c.sing.engineReady() && !c.sing.on.value) openLyrics()
                toggleSing(c) { showSing = true }
            }
            com.prism.music.data.prefs.PlayerButton.EQUALIZER -> { onCollapse(); nav.go(Routes.settings(com.prism.music.ui.screens.settings.SettingsPageId.SOUND_QUALITY.key, com.prism.music.tr(com.prism.music.R.string.sound))) }
            else -> {}
        }
    }

    // The sheets that open over the player (in immersive mode too).
    val playerSheets = @Composable {
        if (showProviders) LyricsProviderSheet(lyricsState, onEnhance = { showProviders = false; showEnhance = true }) { showProviders = false }
        if (showActions) PlayerOptionsSheet(
            song,
            hasCanvas = songCanvas != null,
            statsShown = statsOpen,
            hasNext = hasNext,
            // What the player's hidden parts did (1.8.0): open the lyrics or the queue, the music video, the buttons.
            onOpenLyrics = { openLyrics() },
            onOpenQueue = { if (!queueOpen) toggleQueue() },
            videoMode = videoMode,
            videoAvailable = videoAvailable,
            onVideoMode = { pc.setVideoMode(it) },
            onButton = { onButton(it) },
            onLyricsSource = { showProviders = true },
            onLyricsTiming = { showTiming = true },
            onRedownloadLyrics = { lyricsState.redownload() },
            onToggleStats = { toggleStats() },
            onSleepTimer = { showSleep = true },
            onSpeedPitch = { showSpeed = true },
            onCollapse = onCollapse,
            romanizable = hasForeignScript(lyricsState),
            // Sing from the menu: the lyrics open with Sing on and its controls up (the download sheet the first time).
            // ⋯ → Sing: its sheet (Sing on or off, the voice, the key; the one-time download first).
            onSing = { showSing = true },
            // ⋯ → Lyrics enhancement: word timings, separated vocals, generated instrumentals, one at a time.
            onEnhance = { showEnhance = true },
        ) { showActions = false }
        if (showSleep) SleepTimerSheet { showSleep = false }
        if (showTiming) LyricsTimingSheet { showTiming = false }
        if (showSing) SingSheet(song, onEnhance = { showEnhance = true }) { showSing = false }
        if (showEnhance) EnhanceSheet(song) { showEnhance = false }
        // Held Best part button, or ⋯ → Your best part.
        if (BestPartEditor.open) BestPartSheet { BestPartEditor.open = false }
        if (showOutputs) {
            val profile by c.account.profile.collectAsState()
            val name = (profile?.name ?: settings.accountName).trim().split(" ").firstOrNull()?.takeIf { it.isNotBlank() }?.let { tr(R.string.s_phone, it) } ?: tr(R.string.this_phone)
            OutputPickerSheet(name) { showOutputs = false }
        }
        if (showPlaylists) com.prism.music.ui.components.PlaylistPicker(song) { showPlaylists = false }
        if (showShare) com.prism.music.ui.components.ShareSheet(song.shareTarget()) { showShare = false }
        if (showSpeed) SpeedPitchSheet(song) { showSpeed = false }
        if (showSnoozes) SnoozeSheet(snoozeFocus) { showSnoozes = false }
    }

    // ------------------------------------------------ Immersive mode (⋯ menu): the cover fills the screen, lyrics over it
    // A music video playing leaves it for the normal player, unless immersive's "Show the video" is on (#170).
    if (settings.immersivePlayer && (!videoMode || settings.view.immersiveVideo)) {
        // Back leaves immersive mode for the normal player, as it leaves a full-screen video; Back again closes it.
        BackHandler { c.settings.setImmersivePlayer(false) }
        ImmersivePlayer(
            song, canvas, canvasSaved, isPlaying, lyricsState, covered, video = videoMode,
            onExit = { c.settings.setImmersivePlayer(false) },
            onLyricsTiming = { showTiming = true },
            onLyricsSource = { showProviders = true },
            onSongOptions = { showActions = true },
            onCollapse = onCollapse,
        )
        playerSheets()
        return
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalVisualizer provides viz) {
    Box(
        Modifier
            .fillMaxSize()
            .background(PlayerFallbackBackdrop),
    ) {
        // ------------------------------------------------ Backdrop
        when (settings.playerBackground) {
            com.prism.music.data.prefs.PlayerBackground.MESH -> {
                if (fullBlur < 1f) ArtworkMeshBackdrop(
                    mesh, Modifier.graphicsLayer { alpha = 1f - fullBlur }, seam = if (heroMode) heroHeight else 0.dp,
                    // Held still under a sheet, like everything else that moves here.
                    speed = if (covered) 0f else settings.meshSpeed, intensity = settings.meshIntensity, blur = settings.playerBlur,
                )
                if (fullBlur > 0f) FullArtworkBlurBackdrop(blurImage, Modifier.graphicsLayer { alpha = fullBlur })
            }
            // "Show background" lifts the darkening: about as before at its default, down to a light veil at full.
            com.prism.music.data.prefs.PlayerBackground.BLUR -> {
                FullArtworkBlurBackdrop(blurImage, scrim = 1.3f - 0.95f * settings.playerBackdropShow.coerceAtLeast(0f))
                // #309: below 0 the slider darkens on, to black at its start.
                backdropBlackout(settings.playerBackdropShow).takeIf { it > 0f }?.let { Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = it))) }
            }
            com.prism.music.data.prefs.PlayerBackground.APPEARANCE -> {
                AppearanceBackdrop(settings.playerBackdropShow.coerceAtLeast(0f))
                backdropBlackout(settings.playerBackdropShow).takeIf { it > 0f }?.let { Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = it))) }
                if (fullBlur > 0f) FullArtworkBlurBackdrop(blurImage, Modifier.graphicsLayer { alpha = fullBlur })
            }
            com.prism.music.data.prefs.PlayerBackground.THEME -> ThemeBackdrop(darkGround = wide)
            com.prism.music.data.prefs.PlayerBackground.BLACK -> Box(Modifier.fillMaxSize().background(Color.Black))
            // #320: white, the type dark ([PlayerInk]); the lyrics and the queue fade to the dark blurred cover as the
            // mesh does. A wide player keeps the mesh (its two panes are drawn for a dark ground).
            com.prism.music.data.prefs.PlayerBackground.WHITE -> if (wide) ArtworkMeshBackdrop(mesh, speed = 0f, intensity = settings.meshIntensity, blur = settings.playerBlur) else {
                Box(Modifier.fillMaxSize().background(PlayerWhiteBackdrop))
                if (fullBlur > 0f) FullArtworkBlurBackdrop(blurImage, Modifier.graphicsLayer { alpha = fullBlur })
            }
            // #232 ("not blurry at all"): the lyrics and the queue stay on the gradient, a little darker under their lines.
            com.prism.music.data.prefs.PlayerBackground.GRADIENT -> {
                CoverGradientBackdrop(coverGradient, settings.view.gradientDepth)
                if (fullBlur > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f * fullBlur)))
            }
        }
        // Settings → Lyrics → Background (1.7.5): while the lyrics are open, their own background over the player's.
        val lyricsGround = settings.lyricsPageBackground()
        val lyricsGroundT by animateFloatAsState(
            if (lyricsOpen && lyricsGround != com.prism.music.data.prefs.LyricsBackground.PLAYER) 1f else 0f, tween(420, easing = FastOutSlowInEasing), label = "lyricsGround",
        )
        if (lyricsGroundT > 0f) LyricsBackdrop(
            lyricsGround, settings, songArt, blurImage, canvas, canvasSaved, isPlaying,
            Modifier.fillMaxSize().graphicsLayer { alpha = lyricsGroundT }, hold = covered,
        )
        // The visualizer's Glow behind everything, and its Bars along the bottom, under the controls.
        if (viz != null) when (vizStyle) {
            com.prism.music.data.prefs.VisualizerStyle.GLOW -> VisualizerGlow(viz, vizColor, Modifier.fillMaxSize())
            com.prism.music.data.prefs.VisualizerStyle.BARS -> VisualizerBars(
                viz, vizBrush,
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.2f).padding(horizontal = 12.dp).navigationBarsPadding().inked(),
            )
            else -> {}
        }
        // Settings → Player → Background → "Animated cover fills the player" (1.7.4): the clip plays behind everything,
        // darkened under the type, and the cover itself shows the still picture (one clip playing, not two).
        if (canvasBehind) canvas?.takeIf { !collapsePastHalf }?.let { clip ->
            CanvasPlayer(
                clip, canvasSaved, isPlaying, Modifier.fillMaxSize(), hold = covered,
                presentationAlpha = { (1f - 2f * p()).coerceIn(0f, 1f) },
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.25f), 0.5f to Color.Black.copy(alpha = 0.45f), 1f to Color.Black.copy(alpha = 0.75f))))
        }
        if (videoBehind && heroHeight > 0.dp && !collapsePastHalf) {
            MusicVideoSurface(
                Modifier.fillMaxWidth().height(heroHeight), fill = true, bottomFade = heroFade,
                alpha = { (1f - 2f * p()).coerceIn(0f, 1f) },
            )
        }

        // ------------------------------------------------ Full-bleed banner (+ canvas)
        if (heroMode && heroHeight > 0.dp && !(collapsePastHalf && heroVisible() < 0.001f)) {
            // "Whole cover" (#175): the square cover isn't cropped to the banner's shape; it sits whole on a blurred,
            // cropped copy of itself that fills the rest.
            val fit = settings.view.bleedFit == com.prism.music.data.prefs.BleedFit.FIT
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(heroHeight)
                    .graphicsLayer {
                        alpha = heroVisible() * (1f - canvasCover)
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height * (1f - heroFade), endY = size.height),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            ) {
                // Prism's own software frost of the cover, so it's soft below Android 12 too.
                val frost = if (fit) com.prism.music.ui.components.rememberFrost(artUrl) else null
                if (frost != null) androidx.compose.foundation.Image(
                    frost.image, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize().graphicsLayer { alpha = 0.8f },
                )
                // The last song's picture under the new one until it's in (#244).
                heroPicture?.takeIf { it != artRequest }?.let { last ->
                    AsyncImage(last, null, contentScale = if (fit) ContentScale.Fit else ContentScale.Crop, modifier = Modifier.matchParentSize())
                }
                AsyncImage(
                    artRequest, null, contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
                    onState = { if (it is AsyncImagePainter.State.Success) { artLoaded = true; heroPicture = artRequest } },
                    modifier = Modifier.matchParentSize(),
                )
            }
            canvas?.takeIf { !collapsePastHalf }?.let { clip ->
                CanvasPlayer(
                    clip, canvasSaved, isPlaying,
                    Modifier.fillMaxWidth().height(heroHeight),
                    bottomFade = heroFade,
                    presentationAlpha = { heroT.value * (1f - 2f * p()).coerceIn(0f, 1f) },
                    onCoverChanged = { canvasCover = it },
                )
            }
        }
        // Status-bar scrim (none on the White background, whose status bar has dark icons)
        if (!inkLight) Box(Modifier.fillMaxWidth().height(statusTop + DISMISS_STRIP).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.32f), Color.Transparent))))

        // A panel opened or closed by a gesture, with the morph following the hand. The same 10%-of-the-window rule
        // decides whether it stays open (or closed) when the finger lifts, so a pull that turns back unwinds itself.
        fun openPanelByGesture(page: PlayerPage) {
            when (page) {
                PlayerPage.QUEUE -> if (!queueOpen) toggleQueue()
                PlayerPage.LYRICS -> if (!lyricsOpen) openLyrics()
                else -> {}
            }
        }
        fun closePanelsNow() {
            queueOpen = false
            statsOpen = false
            closeLyrics()
        }
        // The progress at which a closing panel has gone far enough is PlayerMorph's own (a tenth of the window).
        fun settlePanel(startedOpen: Boolean, velocity: Float = 0f) {
            panelShift.end()
            haptics.gestureEnd()
            val reduce = c.settings.current.reduceMotion
            val towardPlayer = !startedOpen
            if (PlayerMorph.completes(panelShift.value, towardPlayer, velocity)) {
                if (towardPlayer) panelShift.animateTo(scope, 1f, reduce, velocity)
                else panelShift.animateTo(scope, 0f, reduce, velocity) { closePanelsNow() }
            } else if (towardPlayer) {
                // It fades with the morph and only then leaves the player, so the list isn't cut away mid-pull.
                panelShift.animateTo(scope, 0f, reduce, velocity) { closePanelsNow() }
            } else panelShift.animateTo(scope, 1f, reduce, velocity)
        }
        // The queue pulled down at its very top (with nothing above it left to scroll to) takes the panel back into the
        // player under the finger: the same morph a swipe down on the deck runs, so the queue fades and rises away as
        // far as the hand has pulled, and the cover, title and deck come back with it. Letting go settles it from where
        // it is, at the speed it was let go.
        val queuePull = remember(morphSpan) {
            object : PanelPull {
                /** Where the panel was when this pull began (the queue's own place, unless it was still on its way in). */
                private var base = 1f
                override fun begin() {
                    base = panelShift.value
                    panelShift.begin()
                }
                override fun follow(px: Float) {
                    val v = base - px / morphSpan
                    if (PlayerMorph.completes(panelShift.value, towardPlayer = false) != PlayerMorph.completes(v, towardPlayer = false)) haptics.threshold()
                    panelShift.follow(v)
                }
                // A downward speed closes; the morph's own numbers (progress a second) are the other way round.
                override fun release(velocity: Float) = settlePanel(startedOpen = true, velocity = -velocity / morphSpan)
                override fun cancel() {
                    panelShift.end()
                    panelShift.animateTo(scope, 1f, c.settings.current.reduceMotion)
                }
            }
        }
        fun settleMenuSwipe(velocity: Float = 0f) {
            panelShift.end()
            haptics.gestureEnd()
            val reduce = c.settings.current.reduceMotion
            if (PlayerMorph.completes(panelShift.value, towardPlayer = true, velocity = velocity)) {
                panelShift.animateTo(scope, 1f, reduce, velocity)
                menuBySwipe = true
                showActions = true
            } else {
                panelShift.animateTo(scope, 0f, reduce, velocity)
            }
        }
        val dismissGesture = Modifier.pointerInput(morphSpan) {
            // The finger writes the morph's number as it moves; it eases back (or finishes) when the finger lifts, at
            // the speed it was moving then.
            val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
            fun letGoSpeed(): Float = -tracker.calculateVelocity().y / morphSpan
            detectVerticalDragGestures(
                onDragStart = { if (!closing) { PlayerMorph.beginDrag(towardPlayer = false); tracker.resetTracking() } },
                onDragEnd = {
                    if (closing) return@detectVerticalDragGestures
                    val v = letGoSpeed()
                    if (PlayerMorph.completes(PlayerMorph.progress, towardPlayer = false, velocity = v)) {
                        PlayerMorph.released(v)
                        slideClosed()
                    } else PlayerMorph.endDrag(towardPlayer = false, complete = false, scope = scope, reduceMotion = c.settings.current.reduceMotion, velocity = v)
                },
                onDragCancel = {
                    if (!closing) PlayerMorph.endDrag(towardPlayer = false, complete = false, scope = scope, reduceMotion = c.settings.current.reduceMotion)
                },
            ) { change, amount ->
                change.consume()
                tracker.addPosition(change.uptimeMillis, change.position)
                if (!closing) PlayerMorph.dragTo(PlayerMorph.progress - amount / morphSpan)
            }
        }
        // Settings → Player → Gestures (1.7.4): what a swipe, double tap or long press on the cover does. Read from the
        // settings as each gesture happens (the gesture handlers live as long as the player).
        fun runGesture(g: com.prism.music.data.prefs.PlayerGesture) {
            when (g) {
                com.prism.music.data.prefs.PlayerGesture.NEXT -> pc.next()
                com.prism.music.data.prefs.PlayerGesture.PREVIOUS -> pc.previous()
                com.prism.music.data.prefs.PlayerGesture.SEEK_BACK, com.prism.music.data.prefs.PlayerGesture.SEEK_AHEAD -> {
                    val ahead = g == com.prism.music.data.prefs.PlayerGesture.SEEK_AHEAD
                    pc.seekBy(if (ahead) 10_000L else -10_000L)
                    seekFlash = SeekFlash(ahead, if (seekFlash?.ahead == ahead) seekFlash!!.steps + 1 else 1, System.nanoTime(), 10)
                }
                com.prism.music.data.prefs.PlayerGesture.PLAY_PAUSE -> pc.togglePlay()
                com.prism.music.data.prefs.PlayerGesture.LIKE -> c.library.toggleLike(song)
                com.prism.music.data.prefs.PlayerGesture.QUEUE -> toggleQueue()
                com.prism.music.data.prefs.PlayerGesture.LYRICS -> if (lyricsOpen) closeLyrics() else openLyrics()
                com.prism.music.data.prefs.PlayerGesture.SLEEP -> showSleep = true
                com.prism.music.data.prefs.PlayerGesture.VIDEO -> if (videoAvailable || videoMode) pc.setVideoMode(!videoMode)
                com.prism.music.data.prefs.PlayerGesture.SING -> onButton(com.prism.music.data.prefs.PlayerButton.SING)
                com.prism.music.data.prefs.PlayerGesture.MENU -> showActions = true
                // Stop first (the mini player mustn't come up for the song being stopped), then close. The screen keeps
                // its last song for the exit, so the player slides away whole instead of going blank on the first frame.
                com.prism.music.data.prefs.PlayerGesture.STOP -> { pc.stop(); onCollapse() }
                // OPEN is the mini player's; the player is open already.
                com.prism.music.data.prefs.PlayerGesture.SEEK_SIDES, com.prism.music.data.prefs.PlayerGesture.OPEN,
                com.prism.music.data.prefs.PlayerGesture.NONE -> {}
            }
        }
        // Drag on the sleeve: down closes the player (or a panel, with the same morph), up opens what's set (the queue
        // comes in, by default) or does its action. One drag chooses its way from the first movement. The keys here
        // mustn't include the panel flags: opening one during the drag would restart the handler and cut the finger off.
        fun sleeveDrag(trackPanels: Boolean = true) = Modifier.pointerInput(trackPanels, morphSpan) {
            var total = 0f
            var mode = 0 // 0 undecided, 1 a panel, 2 the player, 3 an action at the end
            var startedOpen = false
            var menuTarget = false
            var base = 0f
            var startProgress = 1f
            val slop = 6.dp.toPx()
            // The speed the finger lifts at, so the panel or the player carries on at it instead of stopping dead.
            val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
            fun letGoSpeed(): Float = -tracker.calculateVelocity().y / morphSpan
            detectVerticalDragGestures(
                onDragStart = {
                    total = 0f; mode = 0; menuTarget = false
                    tracker.resetTracking()
                    startedOpen = trackPanels && (lyricsOpen || queueOpen || statsOpen)
                    base = panelShift.value
                    startProgress = PlayerMorph.progress
                },
                onDragEnd = {
                    val v = letGoSpeed()
                    when (mode) {
                        1 -> if (menuTarget) settleMenuSwipe(v) else settlePanel(startedOpen, v)
                        2 -> {
                            if (closing) return@detectVerticalDragGestures
                            if (PlayerMorph.completes(PlayerMorph.progress, towardPlayer = false, velocity = v)) {
                                PlayerMorph.released(v)
                                slideClosed()
                            } else PlayerMorph.endDrag(towardPlayer = false, complete = false, scope = scope, reduceMotion = c.settings.current.reduceMotion, velocity = v)
                        }
                        3 -> if (total < -90f) {
                            val up = c.settings.current.coverSwipeUp
                            if (up != com.prism.music.data.prefs.PlayerGesture.NONE) { haptics.gestureEnd(); runGesture(up) }
                        }
                    }
                },
                // An interrupted drag (a system gesture, a sheet opening) used to leave the player part-way down.
                onDragCancel = {
                    when (mode) {
                        1 -> {
                            panelShift.end()
                            if (startedOpen) panelShift.animateTo(scope, 1f, c.settings.current.reduceMotion)
                            else panelShift.animateTo(scope, 0f, c.settings.current.reduceMotion) { closePanelsNow() }
                        }
                        2 -> if (!closing) PlayerMorph.endDrag(towardPlayer = false, complete = false, scope = scope, reduceMotion = c.settings.current.reduceMotion)
                        else -> {}
                    }
                },
            ) { change, amount ->
                change.consume()
                tracker.addPosition(change.uptimeMillis, change.position)
                total += amount
                if (mode == 0 && kotlin.math.abs(total) > slop) {
                    if (total > 0f) {
                        // Pulled down: a panel open goes back the way it came; otherwise the player itself.
                        if (startedOpen) { panelShift.begin(); mode = 1 }
                        else if (!closing) { PlayerMorph.beginDrag(towardPlayer = false); mode = 2 }
                    } else {
                        val up = c.settings.current.coverSwipeUp
                        if (startedOpen) { panelShift.begin(); mode = 1 }
                        else if (up == com.prism.music.data.prefs.PlayerGesture.QUEUE || up == com.prism.music.data.prefs.PlayerGesture.LYRICS) {
                            panelShift.begin(); mode = 1
                            openPanelByGesture(PlayerMorph.pageOf(up)!!)
                        } else if (up == com.prism.music.data.prefs.PlayerGesture.MENU) {
                            // The song menu is a sheet, not a panel, but the player under it makes the same move the
                            // queue and the lyrics do: the cover into the header, the controls away, out of its way.
                            panelShift.begin(); mode = 1; menuTarget = true
                        } else mode = 3
                    }
                }
                when (mode) {
                    1 -> {
                        val v = base - total / morphSpan
                        if (PlayerMorph.completes(panelShift.value, !startedOpen) != PlayerMorph.completes(v, !startedOpen)) haptics.threshold()
                        panelShift.follow(v)
                    }
                    2 -> PlayerMorph.dragTo(startProgress - total / morphSpan)
                    else -> {}
                }
            }
        }
        val sleeveGesture = sleeveDrag()
        // Double-tap the cover: by default its left or right half seeks back or ahead (#79, as on YouTube Music); long
        // press: nothing unless one's set. A double tap set to nothing leaves single taps instant.
        val seekStep = settings.doubleTapSeekSeconds
        val doubleTap = settings.coverDoubleTap.takeUnless { it == com.prism.music.data.prefs.PlayerGesture.SEEK_SIDES && seekStep <= 0 }
            ?: com.prism.music.data.prefs.PlayerGesture.NONE
        val longPress = settings.coverLongPress
        val tapForDeck = deckOnTap
        fun tapsAndHolds(panelOpen: Boolean, tapShowsDeck: Boolean) = Modifier.pointerInput(panelOpen, seekStep, doubleTap, longPress, tapShowsDeck) {
            if (panelOpen) return@pointerInput
            if (doubleTap == com.prism.music.data.prefs.PlayerGesture.NONE && longPress == com.prism.music.data.prefs.PlayerGesture.NONE && !tapShowsDeck) return@pointerInput
            detectTapGestures(
                onTap = if (!tapShowsDeck) null else { _ -> coverDeck = !coverDeck },
                onDoubleTap = if (doubleTap == com.prism.music.data.prefs.PlayerGesture.NONE) null else { at ->
                    haptics.gestureEnd()
                    if (doubleTap == com.prism.music.data.prefs.PlayerGesture.SEEK_SIDES) {
                        val ahead = at.x > size.width / 2
                        pc.seekBy(if (ahead) seekStep * 1000L else -seekStep * 1000L)
                        // Taps in a row add up on the label, as YouTube's does.
                        seekFlash = SeekFlash(ahead, if (seekFlash?.ahead == ahead) seekFlash!!.steps + 1 else 1, System.nanoTime(), seekStep)
                    } else runGesture(doubleTap)
                },
                onLongPress = if (longPress == com.prism.music.data.prefs.PlayerGesture.NONE) null else { _ -> haptics.click(); runGesture(longPress) },
            )
        }
        val doubleTapSeek = tapsAndHolds(lyricsOpen || queueOpen || statsOpen, tapForDeck)
        fun sideSwipe(panelOpen: Boolean) = Modifier.pointerInput(panelOpen) {
            if (panelOpen) return@pointerInput
            var total = 0f
            val threshold = 72.dp.toPx()
            detectHorizontalDragGestures(
                onDragStart = { total = 0f; swipe.cancel() },
                onDragCancel = { swipe.settle(scope, 0f, spring(stiffness = Spring.StiffnessMediumLow)) },
                onDragEnd = {
                    val now = c.settings.current
                    val g = when {
                        total <= -threshold -> now.coverSwipeLeft
                        total >= threshold -> now.coverSwipeRight
                        else -> com.prism.music.data.prefs.PlayerGesture.NONE
                    }
                    if (g != com.prism.music.data.prefs.PlayerGesture.NONE) { haptics.gestureEnd(); runGesture(g) }
                    swipe.settle(scope, 0f, spring(stiffness = Spring.StiffnessMediumLow))
                },
            ) { _, d ->
                val now = c.settings.current
                // #222: toward a swipe set to Nothing the cover stays still, and gives no tick.
                val off = (if (total + d < 0f) now.coverSwipeLeft else now.coverSwipeRight) == com.prism.music.data.prefs.PlayerGesture.NONE
                if (!off && (kotlin.math.abs(total) < threshold) != (kotlin.math.abs(total + d) < threshold)) haptics.threshold()
                total += d
                swipe.set(if (off) 0f else total * 0.35f)
            }
        }
        val skipGesture = sideSwipe(lyricsOpen || queueOpen || statsOpen)

        if (wide) WidePlayerContent(
            song, isPlaying, buffering, position, hasNext, hasPrevious, videoMode, videoAvailable, lyricsState, covered,
            canvas, canvasSaved, artRequest, volume,
            pane = when { queueOpen -> PlayerPane.QUEUE; statsOpen -> PlayerPane.STATS; else -> PlayerPane.LYRICS },
            onPane = { p ->
                // The lyrics are the wide player's resting pane; the flags only say when another one is picked.
                lyricsOpen = false
                queueOpen = p == PlayerPane.QUEUE
                statsOpen = p == PlayerPane.STATS
            },
            dismissGesture = dismissGesture,
            onMore = { showActions = true },
            onLyricsSource = { showProviders = true },
            onCollapse = onCollapse,
            onSing = { showSing = true },
            onSingNeedsDownload = { showSing = true },
            onEnhance = { showEnhance = true },
            onButton = onButton,
        ) else
        Column(Modifier.fillMaxSize().windowInsetsPadding(statusBarRoom()).navigationBarsPadding().then(skipGesture), horizontalAlignment = Alignment.CenterHorizontally) {
            // ------------------------------------------------ Handle + caption
            Box(Modifier.fillMaxWidth().height(DISMISS_STRIP).then(dismissGesture).inked()) {
                // Settings → Player → Show on the player → Handle at the top (Reddit, 1.7.9.1): off, the strip still
                // takes the swipe down.
                if (settings.playerHandle) Box(
                    Modifier.align(Alignment.TopCenter).testTag("playerHandle")
                        .offset { IntOffset(0, lerp(6.dp, (DISMISS_STRIP - 5.dp) / 2, p()).roundToPx()) }
                        .size(36.dp, 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.6f)),
                )
                if (panelAlmostClosed && settings.showPlayingFrom && originAlbum != null) PlaybackOriginCaption(
                    // The song's own album, which needn't be what's playing (a playlist, a radio): not "Playing from".
                    tr(R.string.from_the_album_2, originAlbum.title),
                    Modifier.align(Alignment.BottomCenter).graphicsLayer { alpha = 1f - p() },
                ) {
                    onCollapse(); nav.go(Routes.album(originAlbum.id!!))
                }
            }

            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = PLAYER_GUTTER), horizontalAlignment = Alignment.CenterHorizontally) {
                BoxWithConstraints(
                    Modifier.weight(1f).fillMaxWidth()
                        // #276: a swipe down anywhere round the cover closes the player, not only on the cover and the strip
                        // at the top (the Cover and Minimal layouts leave room above the cover that took nothing). The whole
                        // area takes the cover's own swipes (Settings → Gestures → On the cover), so a swipe up set to the
                        // lyrics or the queue follows the finger there too, as it does over the cover itself. It stays on
                        // through the panel opening (the drag would be cut off if this changed mid-gesture); what's on it
                        // (the cover, the title's buttons) takes its own taps first.
                        .then(sleeveGesture)
                        .testTag("playerArtBox")
                        .padding(top = ART_BOX_TOP_PAD, bottom = if (roomyCover) 12.dp else 18.dp),
                ) {
                    // The cover and the words above the deck are laid out from the room the player has with its deck at
                    // rest. While a panel opens or closes the deck changes shape (the smaller deck, a row that comes or
                    // goes); its own height animates, but the cover's place must not be re-measured every frame of that
                    // change or the cover, title and artist would jump with it.
                    val panelsOpenNow = lyricsOpen || queueOpen || statsOpen
                    var restingHeight by remember { mutableStateOf(maxHeight) }
                    var panelSettling by remember { mutableStateOf(false) }
                    LaunchedEffect(panelsOpenNow) {
                        if (panelsOpenNow) panelSettling = true
                        else {
                            // Long enough for the deck's own size change to finish coming back.
                            kotlinx.coroutines.delay(360)
                            panelSettling = false
                        }
                    }
                    val usesRestingLayout = panelsOpenNow || panelSettling
                    LaunchedEffect(maxHeight, usesRestingLayout) {
                        if (!usesRestingLayout) restingHeight = maxHeight
                    }
                    val layoutHeight = if (usesRestingLayout) restingHeight else maxHeight
                    // Settings → Player → Cover size: Large and Full width reach into the side margins (still square).
                    // The Cover layout's cover is at least Large (its controls are out of the way).
                    val extra = if (layout == com.prism.music.data.prefs.PlayerLayout.COVER) maxOf(settings.coverSize.extra, com.prism.music.data.prefs.CoverSize.LARGE.extra) else settings.coverSize.extra
                    // Full width meets the screen's sides whatever the player's height (#276); a record, a tape and a
                    // shaped cover stay whole.
                    val fullWidth = settings.coverSize == com.prism.music.data.prefs.CoverSize.FULL && !vinyl && !cassette && settings.shapes.player == com.prism.music.data.prefs.CoverShape.ROUNDED
                    // The title and the artist under the cover, and the room they take (#464: more than HEADER_HEIGHT
                    // in a tall font or a large text size, so the artist line isn't squeezed and cut off).
                    val shownSong = com.prism.music.data.SongVersions.shownOf(song)
                    val titleStyle = playerTitleStyle(MaterialTheme.typography, settings)
                    val artistStyle = playerArtistStyle(MaterialTheme.typography, settings)
                    val headerHeight = playerHeaderHeight(shownSong.title, titleStyle, shownSong.artistText, artistStyle)
                    val (artWidth, fullArt) = coverBox(maxWidth, layoutHeight, extra.dp, fullWidth, titleRoom = ART_TITLE_GAP + headerHeight)
                    // Room the cover can't use (a tall screen) is shared above and below it, as before 1.6.4 (all of it
                    // above pushed the whole player down). The title drops into part of the room below, so no blank
                    // band opens up between the artist and the scrubber (issue #9).
                    val slack = (layoutHeight - fullArt - ART_TITLE_GAP - headerHeight).coerceAtLeast(0.dp)
                    val groupTop = slack / 2
                    // #293: the title sits right above the controls, and the picture runs down to it.
                    val titleDrop = if (bleedRoomy) slack - groupTop else minOf(slack / 2, TITLE_DROP_MAX)
                    val bannerBottom = statusTop + DISMISS_STRIP + ART_BOX_TOP_PAD + groupTop + fullArt + ART_TITLE_GAP / 2 +
                        if (bleedRoomy) titleDrop + ART_TITLE_GAP / 2 else 0.dp
                    if (!lyricsOpen && !queueOpen && !statsOpen && bannerBottom != heroHeight) SideEffect { heroHeight = bannerBottom }
                    val sleeveMaxWidth = maxWidth
                    fun artSize(): Dp = lerp(fullArt, THUMB_SIZE, p())
                    fun artWidthNow(): Dp = lerp(artWidth, THUMB_SIZE, p())
                    fun artTop(): Dp = lerp(groupTop, 0.dp, p())
                    fun artStart(): Dp = lerp((maxWidth - artWidth) / 2, 0.dp, p())
                    fun titleTop(): Dp = lerp(groupTop + fullArt + ART_TITLE_GAP + titleDrop, 0.dp, p())
                    fun titleStart(): Dp = lerp(0.dp, THUMB_SIZE + 12.dp, p())

                    // ---- Music video: spans the full screen width, filling the artwork slot.
                    if (videoMode && !collapsePastHalf) {
                        Box(
                            Modifier
                                // requiredWidth wider than the column overflows equally on
                                // both sides, so this already reaches both screen edges.
                                .offset { IntOffset(0, artTop().roundToPx()) }
                                .requiredWidth(sleeveMaxWidth + PLAYER_GUTTER * 2)
                                .height(fullArt + ART_TITLE_GAP / 2)
                                .graphicsLayer { alpha = 1f - 2f * p() }
                                .then(sleeveGesture)
                                // Controls hidden over the video (#166): a tap on it brings them, another sends them away.
                                // #293: the video's own buttons wait for a tap too; with the controls hiding as well, they go together.
                                .then(
                                    if (videoHidesDeck || settings.view.videoButtonsHide) Modifier.pointerInput(videoHidesDeck) {
                                        detectTapGestures(onTap = { if (videoHidesDeck) coverDeck = !coverDeck else videoChips = !videoChips })
                                    } else Modifier
                                ),
                        ) {
                            // One video view at a time: full-bleed, it's drawn behind the player (see videoBehind).
                            if (!videoBehind) MusicVideoSurface(Modifier.fillMaxSize(), fill = videoFill)
                            val chipsShown = !settings.view.videoButtonsHide || (if (videoHidesDeck) coverDeck else videoChips)
                            val chipsAlpha by animateFloatAsState(if (chipsShown) 1f else 0f, tween(180), label = "videoChips")
                            if (chipsShown || chipsAlpha > 0f) Row(Modifier.align(Alignment.BottomEnd).graphicsLayer { alpha = chipsAlpha }.padding(10.dp).testTag("videoChips"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!videoBehind) VideoChip(PrismIcons.AspectRatio, if (videoFill) tr(R.string.fit_video) else tr(R.string.fill)) { videoFill = !videoFill }
                                if (!wide) VideoChip(PrismIcons.FillIn, if (videoBehind) tr(R.string.video_in_a_box) else tr(R.string.full_bleed_video)) {
                                    c.settings.setVideoFullBleed(!settings.videoFullBleed)
                                }
                                if (activity != null) VideoChip(PrismIcons.PictureInPicture, tr(R.string.picture_in_picture)) {
                                    if (!activity.enterPip()) android.widget.Toast.makeText(
                                        context, tr(R.string.picture_in_picture_is_off_for), android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }
                                VideoChip(PrismIcons.Fullscreen, tr(R.string.full_screen)) { fullscreenVideo = true }
                            }
                        }
                    }

                    // ---- The sleeve (collapses to a header thumbnail)
                    // #464: a play / pause tapped on the small cover over the lyrics shows its sign on it for a moment.
                    var playFlash by remember { mutableStateOf<Long?>(null) }
                    Box(
                        Modifier
                            .offset { IntOffset(artStart().roundToPx(), artTop().roundToPx()) }
                            .measuredBox(::artWidthNow, ::artSize)
                            .then(if (!videoMode) sleeveGesture.then(doubleTapSeek) else Modifier)
                            .graphicsLayer {
                                val idle = artScale + (1f - artScale) * p()
                                scaleX = idle; scaleY = idle
                                translationX = swipe.value * (1f - p())
                            }
                            .smallCoverTaps(
                                lyricsOpen, queueOpen || statsOpen, settings.behavior.lyricsCoverPlays, isPlaying,
                                onPlayPause = { pc.togglePlay(); playFlash = System.nanoTime() },
                                onClose = { queueOpen = false; statsOpen = false; closeLyrics() },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        // A hidden cover (What's on the player, 1.8.0) isn't drawn at all until it shrinks into the header:
                        // no animated cover decoding, no record or tape turning, no blend or ring around nothing.
                        val coverAway = !settings.drawsCover(collapsePastHalf)
                        // The visualizer's Ring, behind the cover (it reaches out past the edge).
                        if (viz != null && vizStyle == com.prism.music.data.prefs.VisualizerStyle.RING && !collapsePastHalf && !heroMode && !coverAway) {
                            // A tape is wider than tall, with its own corners (Cassette.kt).
                            val cornerPx = with(LocalDensity.current) { if (cassette) 0f else artCorners.toPx() }
                            VisualizerRing(
                                viz, vizBrush, round = vinyl, cornerPx = cornerPx, modifier = Modifier.matchParentSize(),
                                aspect = if (cassette) CASSETTE_ASPECT else 1f,
                            )
                        }
                        // The cover flying in from the mini player lands here (#199): where it is, and unseen until then.
                        val flightTarget = !vinyl && !cassette && !heroMode && !videoMode
                        // #223: nothing to fly back from when it isn't this cover that's showing.
                        LaunchedEffect(flightTarget) { if (!flightTarget) CoverFlight.player = null }
                        Box(
                            Modifier
                                .fillMaxSize()
                                .then(if (flightTarget) with(CoverFlight) { Modifier.playerCover() } else Modifier)
                                .graphicsLayer {
                                    alpha = when {
                                        CoverFlight.flying -> 0f
                                        videoMode -> (2f * p() - 1f).coerceIn(0f, 1f)
                                        heroMode && (artLoaded || heroPicture != null) -> 1f - heroVisible()
                                        // Hidden (1.8.0): it shows only as it shrinks into the header.
                                        !settings.shows(PlayerPart.COVER) -> p()
                                        else -> 1f
                                    }
                                }
                                // A record keeps its own round shadow and clip (the tonearm reaches past its edge); a
                                // tape draws its own shell.
                                .then(if (vinyl || cassette) Modifier else Modifier.shadow(if (artLoaded) 10.dp else 0.dp, com.prism.music.ui.theme.CoverShapes.shadowOf(artShape)).clip(artShape).background(Color.Black.copy(alpha = 0.18f))),
                            contentAlignment = Alignment.Center,
                        ) {
                            // Vinyl: the cover as a record's label, turning while it plays (the animated cover isn't used).
                            if (vinyl) VinylRecord(
                                artRequest, isPlaying, settings.vinylTonearm, Modifier.fillMaxSize(), hold = covered || coverAway,
                                collapsed = p, onArtLoaded = { artLoaded = true },
                            ) else if (cassette) CassetteTape(
                                artRequest, isPlaying, Modifier.fillMaxSize(), hold = covered || coverAway,
                                collapsed = p, onArtLoaded = { artLoaded = true },
                            ) else {
                            // A file on the phone with no artwork gets a cover of its own rather than a bare note.
                            if (!artLoaded) {
                                if (song.isLocal) com.prism.music.ui.components.LocalCoverFill(song)
                                else Icon(PrismIcons.Song, null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(40.dp))
                            }
                            AsyncImage(
                                artRequest, null, contentScale = ContentScale.Crop,
                                onState = { if (it is AsyncImagePainter.State.Success) artLoaded = true },
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (!videoMode && !coverAway) BlendingCover(song.id, Modifier.fillMaxSize())
                            }
                            if (!heroMode && !videoMode && !canvasBehind && !vinyl && !cassette && !coverAway) canvas?.takeIf { !collapsePastHalf }?.let { clip ->
                                CanvasPlayer(clip, canvasSaved, isPlaying, Modifier.fillMaxSize(), onCoverChanged = { canvasCover = it })
                            }
                        }
                        SeekFlashLabel(seekFlash, lastSeek.value, !collapsePastHalf) { f ->
                            Modifier.align(if (f.ahead) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 18.dp)
                        }
                        if (playFlash != null) LaunchedEffect(playFlash) { delay(700); playFlash = null }
                        // What the tap did: paused shows pause, playing shows play. It comes and goes softly rather than
                        // flashing on and cutting off over the cover.
                        PlayFlash(playFlash != null && !collapsePastHalf, artShape, isPlaying)
                    }

                    // The visualizer's Wave, behind the title.
                    if (viz != null && vizStyle == com.prism.music.data.prefs.VisualizerStyle.WAVE && !collapsePastHalf) VisualizerWave(
                        viz, vizBrush,
                        Modifier.fillMaxWidth().height(headerHeight).offset { IntOffset(0, titleTop().roundToPx()) }.graphicsLayer { alpha = 1f - 2f * p() }.inked(),
                    )
                    // ---- Credits: title, artist, like, menu
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .offset { IntOffset(0, (titleTop() - lerp(0.dp, (headerHeight - THUMB_SIZE) / 2, p())).roundToPx()) }
                            .layout { measurable, constraints ->
                                val start = titleStart().roundToPx()
                                val placeable = measurable.measure(constraints.offsetConstraints(horizontal = -start))
                                layout(constraints.constrainWidth(placeable.width + start), constraints.constrainHeight(placeable.height)) {
                                    placeable.placeRelative(start, 0)
                                }
                            }
                            .height(headerHeight)
                            // The like and ⋯ beside the title come in with it, at the end of the morph (the moving title
                            // is still on its way up before then).
                            .graphicsLayer { alpha = PlayerMorph.elementReveal }
                            .then(sleeveGesture)
                            // #320: dark over the White background.
                            .inked(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Settings → Player → Title (1.7.4): centred titles sit in the middle of the screen (a space as wide
                        // as the buttons on the right balances them), until the cover shrinks into the header.
                        val centred = settings.playerTitleAlign == com.prism.music.data.prefs.PlayerTitleAlign.CENTER && !collapsePastHalf
                        // Like picked for the buttons row: one heart is enough while that row is on screen (the
                        // header keeps its own over the lyrics and the queue, and with Cover / Minimal, which hide the row).
                        val heartBelow = com.prism.music.data.prefs.PlayerButton.LIKE in settings.playerButtons &&
                            settings.shows(PlayerPart.BUTTONS) &&
                            layout != com.prism.music.data.prefs.PlayerLayout.MINIMAL && layout != com.prism.music.data.prefs.PlayerLayout.COVER &&
                            !lyricsOpen && !queueOpen && !statsOpen
                        if (centred) Spacer(Modifier.width(if (heartBelow) TITLE_BUTTONS_WIDTH - LIKE_GLYPH_ROOM else TITLE_BUTTONS_WIDTH))
                        Column(
                            Modifier.weight(1f).graphicsLayer {
                                val s = 1f - 0.2f * p()
                                scaleX = s; scaleY = s
                                transformOrigin = TransformOrigin(if (centred) 0.5f else 0f, 0.5f)
                            },
                            horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start,
                        ) {
                            val scrolls = panelAtStart && !covered && settings.playerTitleScroll
                            // With the ⋯ button hidden (1.8.0), a long press on the title or the artist opens the menu.
                            val menuHeld: (() -> Unit)? = if (settings.shows(PlayerPart.MENU)) null else ({ showActions = true })
                            // #328: the title, artists and album of the version picked to play in its place, if any.
                            @Suppress("NAME_SHADOWING") val song = com.prism.music.data.SongVersions.shownOf(song)
                            if (inHeader(PlayerPart.TITLE)) PlayerMarquee(
                                // The title leads, the artist under it a size smaller (#41). Spectrum: large and light.
                                song.title, titleStyle, Color.White, scrolls,
                                Modifier.testTag("playerTitle").playerTitleAnchor()
                                    .combinedClickable(enabled = song.album?.id != null || menuHeld != null, onLongClick = menuHeld) {
                                    song.album?.id?.let { onCollapse(); nav.go(Routes.album(it)) }
                                },
                            )
                            // With more than one artist, the name asks which one to open (#42).
                            // Artists YouTube names without a link open by name (#78).
                            val linked = com.prism.music.ui.components.openableArtists(song)
                            var pickArtist by remember(song.id) { mutableStateOf(false) }
                            if (inHeader(PlayerPart.ARTIST)) Box {
                                // #289 (1.8.1): each name its own link, if asked for; a tap between them still asks.
                                val artistLine = artistLinkText(song.artistText, linked, settings.view.artistLinks) { a -> onCollapse(); nav.go(Routes.artist(a)) }
                                PlayerMarquee(
                                    artistLine, artistStyle, Color.White.copy(alpha = 0.6f), scrolls,
                                    Modifier.testTag("playerArtist").playerArtistAnchor()
                                        .combinedClickable(enabled = linked.isNotEmpty() || menuHeld != null, onLongClick = menuHeld) {
                                        if (linked.size > 1) pickArtist = true else linked.firstOrNull()?.let { onCollapse(); nav.go(Routes.artist(it)) }
                                    },
                                    delayMs = 5500,
                                )
                                androidx.compose.material3.DropdownMenu(pickArtist, { pickArtist = false }) {
                                    linked.forEach { a ->
                                        androidx.compose.material3.DropdownMenuItem(
                                            { Text(a.name) }, { pickArtist = false; onCollapse(); nav.go(Routes.artist(a)) },
                                            leadingIcon = { androidx.compose.material3.Icon(PrismIcons.Person, null) },
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        // A Smart Shuffle pick: keep it in the playlist, or turn it down.
                        val smartPicks by c.queue.smartPicks.collectAsState()
                        if (song.id in smartPicks) {
                            SmartPickButton(song)
                            Spacer(Modifier.width(8.dp))
                        }
                        val isLiked = song.id in liked
                        val headerHeart = !heartBelow && inHeader(PlayerPart.LIKE) && !song.isRss
                        AnimatedVisibility(
                            headerHeart,
                            enter = fadeIn(tween(200, easing = FastOutSlowInEasing)) +
                                expandHorizontally(tween(240, easing = FastOutSlowInEasing)),
                            exit = fadeOut(tween(140)) + shrinkHorizontally(tween(180, easing = FastOutSlowInEasing)),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircleGlyph(if (isLiked) PrismIcons.HeartFilled else PrismIcons.Heart, if (isLiked) tr(R.string.remove_from_liked) else tr(R.string.widget_like), isLiked) {
                                    c.library.toggleLike(song)
                                }
                                Spacer(Modifier.width(8.dp))
                            }
                        }
                        val headerMenu = settings.showsMenuButton() || collapsePastHalf
                        AnimatedVisibility(
                            headerMenu,
                            enter = fadeIn(tween(200, easing = FastOutSlowInEasing)) +
                                scaleIn(tween(220, easing = FastOutSlowInEasing), initialScale = 0.8f) +
                                expandHorizontally(tween(220, easing = FastOutSlowInEasing)),
                            exit = fadeOut(tween(140)) + scaleOut(tween(180, easing = FastOutSlowInEasing), targetScale = 0.8f) +
                                shrinkHorizontally(tween(180, easing = FastOutSlowInEasing)),
                        ) { CircleGlyph(PrismIcons.More, tr(R.string.song_options)) { showActions = true } }
                    }

                    // ---- Lyrics and queue panels, under the collapsed header
                    // Both panels make the same move (the side only picks which): they rise and fade with the finger,
                    // whatever brought them there. One layer, one rise (panelRise), for the lyrics, the queue and the
                    // stats alike.
                    if (lyricsOpen) {
                        LyricsPanel(
                            lyricsState, isPlaying && !covered, lyricsControlsOpen,
                            onRevealControls = { lyricsControlsOpen = true },
                            onHideControls = { if (!controlsStay) lyricsControlsOpen = false },
                            modifier = Modifier.fillMaxSize().padding(top = HEADER_HEIGHT).graphicsLayer {
                                val fade = panelFade()
                                alpha = fade
                                translationY = panelRise(fade).toPx()
                            },
                            onChangeSource = { showProviders = true },
                        )
                    }
                    if (statsOpen) {
                        NerdStatsPanel(
                            Modifier.fillMaxSize().padding(top = HEADER_HEIGHT + 8.dp).graphicsLayer {
                                val fade = panelFade()
                                alpha = fade
                                translationY = panelRise(fade).toPx()
                            },
                        )
                    }
                    if (showQueuePanel) {
                        InlineQueue(
                            onRevealPlayer = { queueControlsOpen = true },
                            onHidePlayer = { if (!controlsStay) queueControlsOpen = false },
                            onClose = { haptics.gestureEnd(); toggleQueue() },
                            pull = queuePull,
                            modifier = Modifier.fillMaxSize().padding(top = HEADER_HEIGHT + 8.dp).graphicsLayer {
                                val fade = panelFade()
                                alpha = fade
                                translationY = panelRise(fade).toPx()
                            },
                        )
                    }
                }

                // ------------------------------------------------ The deck
                // Cover layout: the deck stays away until the cover's tapped (the lyrics and queue bring it as before).
                val deckShown = (!lyricsOpen || lyricsControlsOpen || controlsStay) && (!queueOpen || queueControlsOpen || controlsStay) &&
                    (!deckOnTap || coverDeck || lyricsOpen || queueOpen || statsOpen || (videoHidesDeck && !isPlaying))
                AnimatedVisibility(
                    deckShown,
                    // A tap brings the controls back; they shouldn't keep the thumb waiting. (A little longer than a tab,
                    // as the deck is a whole row of room opening at the top.)
                    enter = expandVertically(tween(300, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) + fadeIn(tween(220, easing = FastOutSlowInEasing)),
                    exit = shrinkVertically(tween(260, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) + fadeOut(tween(200)),
                ) {
                    // Settings → Gestures & buttons → "Swipe up on the player" (#200, #197; each side picked since 1.9):
                    // up on the deck's lower half opens the queue or the lyrics, whichever that side brings (as Prism
                    // came, the queue from the right and the lyrics from the left), and whichever it is the same drag
                    // and the same morph bring it — the letters and the list rise and fade alike. Down closes whichever
                    // is open, and with neither open it pulls the player itself down. It follows the finger (panelShift
                    // / PlayerMorph), so a pull that turns back unwinds, and it's one-handed instead of reaching for the
                    // buttons.
                    val panelSwipes = settings.behavior.panelSwipes
                    val swipes = if (panelSwipes == com.prism.music.data.prefs.PanelSwipes.OFF) Modifier else Modifier.pointerInput(panelPlayer, morphSpan, panelSwipes) {
                        var total = 0f
                        var fromRight = true
                        var mode = 0
                        var startedOpen = false
                        var base = 0f
                        var startProgress = 1f
                        val slop = 6.dp.toPx()
                        // The speed the finger lifts at: the panel (or the player) goes on at it.
                        val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                        fun letGoSpeed(): Float = -tracker.calculateVelocity().y / morphSpan
                        detectVerticalDragGestures(
                            onDragStart = { at ->
                                total = 0f; mode = 0; fromRight = at.x > size.width / 2
                                tracker.resetTracking()
                                startedOpen = lyricsOpen || queueOpen || statsOpen
                                base = panelShift.value
                                startProgress = PlayerMorph.progress
                            },
                            onDragEnd = {
                                val v = letGoSpeed()
                                when (mode) {
                                    1 -> settlePanel(startedOpen, v)
                                    2 -> if (!closing) {
                                        if (PlayerMorph.completes(PlayerMorph.progress, towardPlayer = false, velocity = v)) {
                                            PlayerMorph.released(v)
                                            slideClosed()
                                        } else PlayerMorph.endDrag(towardPlayer = false, complete = false, scope = scope, reduceMotion = c.settings.current.reduceMotion, velocity = v)
                                    }
                                    else -> {}
                                }
                            },
                            onDragCancel = {
                                when (mode) {
                                    1 -> {
                                        panelShift.end()
                                        if (startedOpen) panelShift.animateTo(scope, 1f, c.settings.current.reduceMotion)
                                        else panelShift.animateTo(scope, 0f, c.settings.current.reduceMotion) { closePanelsNow() }
                                    }
                                    2 -> if (!closing) PlayerMorph.endDrag(towardPlayer = false, complete = false, scope = scope, reduceMotion = c.settings.current.reduceMotion)
                                    else -> {}
                                }
                            },
                        ) { change, amount ->
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            total += amount
                            if (mode == 0 && kotlin.math.abs(total) > slop) {
                                if (total < 0f) {
                                    if (lyricsOpen || queueOpen || statsOpen) { panelShift.begin(); mode = 1; startedOpen = true }
                                    else {
                                        panelShift.begin(); mode = 1; startedOpen = false
                                        panelSwipes.page(fromRight)?.let { openPanelByGesture(it) }
                                    }
                                } else {
                                    if (lyricsOpen || queueOpen || statsOpen) { panelShift.begin(); mode = 1; startedOpen = true }
                                    else if (!closing) { PlayerMorph.beginDrag(towardPlayer = false); mode = 2 }
                                }
                            }
                            when (mode) {
                                1 -> panelShift.follow(base - total / morphSpan)
                                2 -> PlayerMorph.dragTo(startProgress - total / morphSpan)
                                else -> {}
                            }
                        }
                    }
                    // #259 (1.7.9.2): "Gestures anywhere on the player": the cover's swipes, double tap and long press work
                    // around the controls too (the buttons and bars keep their own taps and drags).
                    val coverGestures = when {
                        panelSwipes != com.prism.music.data.prefs.PanelSwipes.OFF -> Modifier
                        // #282: below the lyrics, the deck takes the cover's gestures. The lyrics are their own list above it,
                        // so scrolling them never reaches this.
                        lyricsOpen && !queueOpen && !statsOpen && settings.behavior.gesturesUnderLyrics ->
                            sleeveDrag(trackPanels = false)
                                .then(tapsAndHolds(panelOpen = false, tapShowsDeck = false))
                                .then(sideSwipe(panelOpen = false))
                        !settings.behavior.gesturesEverywhere || lyricsOpen || queueOpen || statsOpen -> Modifier
                        else -> sleeveGesture.then(doubleTapSeek)
                    }
                    Column(
                        swipes.then(coverGestures).testTag("playerDeck")
                            .clipToBounds()
                            .graphicsLayer { alpha = PlayerMorph.deckReveal }.inked(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        com.prism.music.ui.player.PanelDeck(panelShift) { panel ->
                        // The deck takes its shape over a panel from the panel's own number (PanelShift), not from an
                        // animation of its own: the parts a panel makes room for fold away as it comes and come back as
                        // it goes, so a drag takes the deck apart under the finger, a pull that turns back puts it
                        // together, and the parts at the bottom hold still. (They used to be cut out in one frame while
                        // the deck's height eased after them: a jump up and then a slide down on opening, the same the
                        // other way round on closing.)
                        val panelsUp = lyricsOpen || queueOpen || statsOpen
                        // The number is read inside this scope, so the deck alone follows a panel's morph: read in the
                        // player's own composition it recomposed the whole player (the queue panel included) every
                        // frame of the animation, which was most of its frame time.
                        // Small (#230): over the lyrics or the queue, only the progress, a smaller transport and the
                        // buttons (they switch between lyrics and queue), so the list keeps most of the screen.
                        val smallTarget = (lyricsOpen || queueOpen) && panelPlayer == com.prism.music.data.prefs.PanelPlayer.SMALL
                        val panelBeat = tween<Float>(320, easing = FastOutSlowInEasing)
                        // What the deck answers to, each with a number of its own: the panel's, so a finger takes the
                        // deck apart; the lyrics' (its words are on the screen) and Small's, so the deck still eases when
                        // they change with the panel standing still — from the lyrics to the queue, say.
                        val lyricsAmount by animateFloatAsState(if (lyricsOpen) 1f else 0f, panelBeat, label = "deckLyrics")
                        val smallAmount by animateFloatAsState(if (smallTarget) 1f else 0f, panelBeat, label = "deckSmall")
                        // How much of the panel's layout the deck has taken: the smaller deck and the tighter one
                        // ("More room for the picture", #293) are both on that number.
                        val small = minOf(smallAmount, panel)
                        val tight = if (!bleedRoomy) 0f else if (panelsUp) 1f - panel else 1f
                        val deckCompact = maxOf(small, tight)
                        val stripGone = deckStripGone(panel, lyricsAmount, smallAmount, settings.lyricsOnPlayer)
                        val volumeGone = minOf(smallAmount, panel)
                        // What the deck draws as settings put it, before a panel takes any of it: the room each part
                        // takes is read from this, so the parts a panel folds keep their place in the order (at no
                        // height) and the ones under them never move.
                        val all = settings.playerDeck
                            // What's on the player (1.8.0): the progress bar and the buttons row can go too.
                            .filter { it != com.prism.music.data.prefs.DeckPart.SCRUBBER || settings.shows(PlayerPart.PROGRESS) }
                            .filter { it != com.prism.music.data.prefs.DeckPart.BUTTONS || settings.shows(PlayerPart.BUTTONS) }
                        // #329: what's hidden leaves no hole; the hidden volume bar's room is shared between the gaps.
                        val roomy = deckSpacing(all, settings.shows(PlayerPart.VOLUME), small = false, tight = false)
                        val tighter = deckSpacing(all, settings.shows(PlayerPart.VOLUME), small = false, tight = true)
                        // The gaps and the room under the deck at this point of the change.
                        fun gapOf(i: Int): Dp = lerp(
                            lerp(roomy.gaps.getOrNull(i) ?: 8.dp, tighter.gaps.getOrNull(i) ?: 8.dp, tight),
                            2.dp, small,
                        )
                        val deckGap = lerp(lerp(if (roomyCover) 12.dp else 18.dp, 8.dp, tight), 6.dp, small)
                        val underDeck = lerp(lerp(roomy.below, tighter.below, tight), 0.dp, small)
                        // With the lyrics open: Sing's one button at their lower right (1.7.8, V; their source is a line
                        // at their end), and the source as a button at the lower left when asked for (1.7.9). It's a row
                        // of its own, so it also has a beat of its own: switched for the queue, the panel under it doesn't
                        // move, and the row still comes and goes smoothly.
                        AnimatedVisibility(
                            lyricsOpen,
                            enter = expandVertically(tween(320, easing = FastOutSlowInEasing), expandFrom = Alignment.Bottom) +
                                fadeIn(tween(220, easing = FastOutSlowInEasing)),
                            exit = shrinkVertically(tween(320, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Bottom) +
                                fadeOut(tween(180)),
                        ) {
                            LyricsButtonsRow(song, lyricsState, onChangeSource = { showProviders = true }, onNeedsDownload = { showSing = true }, onEnhance = { showEnhance = true }, modifier = Modifier.padding(bottom = 6.dp))
                        }
                        // Minimal: play, and gestures for the rest (the lyrics and queue still have the whole deck).
                        if (layout == com.prism.music.data.prefs.PlayerLayout.MINIMAL && !panelsUp) {
                            MinimalPlay(isPlaying, buffering)
                            Spacer(Modifier.height(deckGap))
                        } else {
                            // The parts in the listener's order (Settings → Player → Arrange, 1.7.4); what's switched off
                            // elsewhere stays off.
                            roomy.parts.forEachIndexed { i, part ->
                                val room = when (part) {
                                    com.prism.music.data.prefs.DeckPart.STRIP -> 1f - stripGone
                                    com.prism.music.data.prefs.DeckPart.VOLUME -> 1f - volumeGone
                                    else -> 1f
                                }
                                if (room > 0f) Box(Modifier.deckRoom(room)) {
                                    when (part) {
                                        com.prism.music.data.prefs.DeckPart.STRIP -> CurrentLyricStrip(lyricsState, isPlaying && !covered, overScrubber = stripOverScrubber(roomy.parts, i), onClick = openLyrics)
                                        com.prism.music.data.prefs.DeckPart.SCRUBBER -> PlayheadScrubber(position, pc.duration) {
                                            // Settings → Player → "Stream quality" hides it (the full stats stay in the ⋯ menu).
                                            // In a song's opening seconds the middle offers its best part instead, while it's still ahead.
                                            if (!SingWord() && !BestPartChip() && settings.showQuality) QualityLabel(Modifier.align(Alignment.Center), selected = statsOpen) { toggleStats() }
                                        }
                                        com.prism.music.data.prefs.DeckPart.TRANSPORT ->
                                            TransportRow(
                                                isPlaying, buffering, previousEnabled = hasPrevious || pastStart, nextEnabled = hasNext,
                                                compact = deckCompact >= 0.5f, episode = song.isEpisode,
                                                compactFraction = deckCompact,
                                                skips = settings.shows(PlayerPart.PREV_NEXT),
                                                playAnchor = Modifier.playerPlayAnchor().graphicsLayer { alpha = PlayerMorph.playReveal },
                                            )
                                        com.prism.music.data.prefs.DeckPart.VOLUME -> VolumeRow(volume)
                                        com.prism.music.data.prefs.DeckPart.BUTTONS -> PlayerActionRow(
                                            lyricsOpen, queueOpen, videoMode, videoAvailable,
                                            onToggleLyrics = { if (lyricsOpen) closeLyrics() else openLyrics() },
                                            onToggleQueue = toggleQueue,
                                            onVideoMode = { pc.setVideoMode(it) },
                                            episode = song.isEpisode,
                                            onButton = onButton,
                                            onSnoozeMenu = { id -> snoozeFocus = id; showSnoozes = true },
                                        )
                                    }
                                }
                                // The gap after a part goes with it: nothing is left behind when one folds, and Small's
                                // tighter gaps are the same fold.
                                if (room > 0f && i < roomy.parts.lastIndex) Spacer(Modifier.height(gapOf(i) * room))
                            }
                            Spacer(Modifier.height(deckGap + underDeck))
                        }
                        // The device line, with the sleep timer beside it while one is set (on its own when the line is
                        // hidden; over a panel, Small keeps the timer alone). It changes size over the same beat as the
                        // panel rather than in one frame, so the room above it doesn't move when it does.
                        if (settings.showOutputDevice) AnimatedContent(
                            smallTarget,
                            transitionSpec = {
                                (fadeIn(tween(220, easing = FastOutSlowInEasing)) togetherWith fadeOut(tween(180)))
                                    .using(androidx.compose.animation.SizeTransform(clip = true))
                            },
                            contentAlignment = Alignment.Center,
                            label = "deckDevice",
                        ) { small ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (!small) {
                                    OutputCaption()
                                    Spacer(Modifier.width(8.dp))
                                }
                                SleepBadge()
                            }
                        } else {
                            SleepBadge()
                            if (!roomyCover) Spacer(Modifier.height(lerp(14.dp, 0.dp, tight)))
                        }
                        Spacer(Modifier.height(deckGap))
                        }
                    }
                }
                // The controls away over the lyrics or the queue: a swipe up from the bottom edge brings them back (a tap
                // on the lyrics or the queue does too).
                if (!deckShown && (lyricsOpen || queueOpen)) Box(
                    Modifier.fillMaxWidth().height(40.dp).pointerInput(Unit) {
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = { if (total < -40f) { lyricsControlsOpen = true; queueControlsOpen = true } },
                        ) { change, amount -> change.consume(); total += amount }
                    },
                )
            }
        }
        // #250 on Android 15 and later, where a window's navigation bar color isn't drawn: the band is the player's own.
        if (drawsNavBand(android.os.Build.VERSION.SDK_INT, navBand)) PlayerNavBand(inkLight, Modifier.align(Alignment.BottomCenter))
    }
    }

    playerSheets()
}

/**
 * The deck's content, composed in a scope of its own so that a panel's morph (reading [PanelShift.value]) recomposes
 * the deck only, not the whole player — the queue panel included — on every frame of its rise and fade.
 */
@Composable
private fun PanelDeck(panelShift: PanelShift, content: @Composable (Float) -> Unit) {
    content(panelShift.value)
}

/** The "Theme" player background. Material You: a tonal surface (light or dark, from the scheme). Prism: a deep wash of the accent color, lighter at the top. */
@Composable
internal fun ThemeBackdrop(darkGround: Boolean = false) {
    val material = com.prism.music.ui.theme.LocalUi.current.material
    val scheme = if (material && darkGround) {
        val s = com.prism.music.ui.theme.LocalAppSettings.current
        val context = androidx.compose.ui.platform.LocalContext.current
        val seed = com.prism.music.ui.theme.MaterialYou.seed(s, dark = true, artwork = null)
        remember(s, seed) { com.prism.music.ui.theme.MaterialYou.scheme(s, dark = true, seed, com.prism.music.ui.theme.MaterialYou.dynamic(s, context)) }
    } else MaterialTheme.colorScheme
    if (material) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(scheme.surfaceContainerHigh, scheme.surfaceContainerLow, scheme.surface))))
        return
    }
    fun Color.deep(f: Float) = Color(red * f, green * f, blue * f, 1f)
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(scheme.primary.deep(0.55f), scheme.tertiary.deep(0.28f), Color.Black.copy(alpha = 0.92f).compositeOver(scheme.primary.deep(0.1f))))
        ),
    )
}

/**
 * The background picked for Home in Appearance → Backgrounds. The player is always dark (white text and
 * controls), so the background is drawn in its dark version whatever the app's theme, under a veil whose
 * strength [show] sets, heavier toward the controls at the bottom.
 */
@Composable
internal fun AppearanceBackdrop(show: Float) {
    val s = com.prism.music.ui.theme.LocalAppSettings.current
    // The player's own pick (1.7.4), else Home's.
    val (style, photo) = com.prism.music.ui.theme.Backdrop.parse(
        s.backdrops[com.prism.music.ui.theme.PLAYER_BACKDROP] ?: s.backdrops[com.prism.music.ui.theme.BackdropScreen.HOME.name],
    )
    val primary = MaterialTheme.colorScheme.primary
    val darkScheme = remember(primary) { com.prism.music.ui.theme.schemeFromSeed(primary, dark = true, pureBlack = false) }
    val veil = 1f - show
    MaterialTheme(colorScheme = darkScheme) {
        androidx.compose.runtime.CompositionLocalProvider(com.prism.music.ui.theme.LocalIsDark provides true) {
            Box(Modifier.fillMaxSize().background(darkScheme.background)) {
                com.prism.music.ui.theme.BackdropLayer(
                    style, photo, dim = 0.7f * veil, motion = s.backdropMotion && !s.reduceMotion, modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.10f * veil), 0.55f to Color.Black.copy(alpha = 0.18f * veil + 0.04f),
                            1f to Color.Black.copy(alpha = 0.45f * veil + 0.18f)),
                    ),
                )
            }
        }
    }
}

@Composable
private fun VideoChip(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = Color.White, modifier = Modifier.size(20.dp)) }
}

/**
 * The main player's video, drawn on a TextureView so it composes with the layout. [bottomFade]: the share of its
 * height, from the bottom, over which it dissolves into what's behind (the full-bleed video, #120).
 */
@OptIn(UnstableApi::class)
@Composable
fun MusicVideoSurface(modifier: Modifier, fill: Boolean, bottomFade: Float = 0f, alpha: () -> Float = { 1f }) {
    val pc = LocalContainer.current.player
    AndroidView(
        factory = { ctx ->
            FadingFrame(ctx).apply {
                addView((LayoutInflater.from(ctx).inflate(R.layout.texture_player, this, false) as PlayerView).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setKeepContentOnPlayerReset(true)
                    player = pc.player
                })
                // The fade's gradient is as tall as the view: laid out again, it's made again.
                if (android.os.Build.VERSION.SDK_INT >= 31) addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
                    if (bottom - top != oldBottom - oldTop) v.bottomFade((v as FadingFrame).wanted)
                }
            }
        },
        update = { frame ->
            val view = frame.getChildAt(0) as PlayerView
            view.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            if (view.player !== pc.player) view.player = pc.player
            frame.alpha = alpha()
            frame.wanted = bottomFade
            if (android.os.Build.VERSION.SDK_INT >= 31) frame.bottomFade(bottomFade) else frame.fade = bottomFade
        },
        onRelease = { (it.getChildAt(0) as PlayerView).player = null },
        modifier = modifier,
    )
}

/** True while Prism is a picture-in-picture window (provided by MainActivity). */
val LocalPip = androidx.compose.runtime.compositionLocalOf { false }

/**
 * Edge-to-edge landscape video with tap-to-show controls. In picture-in-picture ([pip]) it's the
 * video alone: Android draws the window's own controls (from the media session) over it.
 */
@Composable
private fun FullscreenVideo(song: Song, forceLandscape: Boolean, pip: Boolean = false, onExit: () -> Unit) {
    val pc = LocalContainer.current.player
    val view = LocalView.current
    val activity = view.context as? Activity
    val isPlaying by pc.isPlaying.collectAsState()
    var controls by remember { mutableStateOf(true) }
    val position = rememberPositionState(fast = false)
    val prefs = LocalContainer.current.settings
    DisposableEffect(forceLandscape) {
        val window = activity?.window
        val ctl = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousOrientation = activity?.requestedOrientation
        if (forceLandscape) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            // Back to the bars as the settings have them (#115: the status bar may stay hidden).
            com.prism.music.ui.components.SystemBars.apply(ctl, com.prism.music.ui.components.SystemBars.hidden(prefs.current))
            if (forceLandscape) activity?.requestedOrientation = previousOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    LaunchedEffect(controls, isPlaying) { if (controls && isPlaying) { delay(3500); controls = false } }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { controls = !controls }, onDoubleTap = { pc.togglePlay() }) },
    ) {
        MusicVideoSurface(Modifier.fillMaxSize(), fill = false)
        AnimatedVisibility(
            controls && !pip,
            enter = fadeIn(tween(com.prism.music.ui.theme.PrismTokens.Motion.STANDARD, easing = com.prism.music.ui.theme.PrismTokens.Motion.Ease)),
            exit = fadeOut(tween(com.prism.music.ui.theme.PrismTokens.Motion.STANDARD)),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
                Column(Modifier.align(Alignment.TopStart).padding(24.dp)) {
                    Text(song.title, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                    Text(song.artistText, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
                Box(
                    Modifier.align(Alignment.TopEnd).padding(16.dp).size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)).clickable(onClick = onExit),
                    contentAlignment = Alignment.Center,
                ) { Icon(PrismIcons.FullscreenExit, tr(R.string.exit_full_screen), tint = Color.White) }
                Box(
                    Modifier.align(Alignment.Center).size(72.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f))
                        .clickable(remember { MutableInteractionSource() }, indication = null) { pc.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    // The same quick glyph hand-over as the player's disc, rather than a cut.
                    Crossfade(isPlaying, animationSpec = tween(com.prism.music.ui.theme.PrismTokens.Motion.QUICK), label = "fullscreenPlayPause") { p ->
                        Icon(if (p) PrismIcons.Pause else PrismIcons.Play, tr(R.string.play_pause), tint = Color.White, modifier = Modifier.size(44.dp))
                    }
                }
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp)) {
                    PlayheadScrubber(position, pc.duration)
                }
            }
        }
    }
}

/** The seek label over the cover (double-tap back or ahead): it grows in and fades away with the label kept for the exit. */
@Composable
private fun SeekFlashLabel(flash: SeekFlash?, last: SeekFlash?, visible: Boolean, modifier: (SeekFlash) -> Modifier) {
    val f = flash ?: last ?: return
    AnimatedVisibility(
        visible && flash != null,
        modifier = modifier(f),
        enter = fadeIn(tween(com.prism.music.ui.theme.PrismTokens.Motion.QUICK)) +
            scaleIn(initialScale = 0.9f, animationSpec = tween(180, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(com.prism.music.ui.theme.PrismTokens.Motion.STANDARD, easing = FastOutSlowInEasing)) +
            scaleOut(targetScale = 0.96f, animationSpec = tween(com.prism.music.ui.theme.PrismTokens.Motion.STANDARD, easing = FastOutSlowInEasing)),
    ) {
        SeekLabel(f, Modifier)
    }
}

/** The pause / play shown over the cover after a tap on it: fades in and out instead of cutting on and off. */
@Composable
private fun PlayFlash(visible: Boolean, shape: androidx.compose.ui.graphics.Shape, isPlaying: Boolean) {
    AnimatedVisibility(
        visible,
        enter = fadeIn(tween(com.prism.music.ui.theme.PrismTokens.Motion.QUICK)),
        exit = fadeOut(tween(com.prism.music.ui.theme.PrismTokens.Motion.STANDARD, easing = FastOutSlowInEasing)),
    ) {
        Box(Modifier.fillMaxSize().clip(shape).background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
            Icon(if (isPlaying) PrismIcons.Play else PrismIcons.Pause, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

/** The last double-tap seek on the cover (#79): which way, how many taps in a row, and when (to time the label out). */
/** A seek shown over the cover: [steps] in a row of [step] seconds each, back or [ahead]. */
internal data class SeekFlash(val ahead: Boolean, val steps: Int, val at: Long, val step: Int = 10)

/** "−10 s" / "+10 s" over the cover's side while double-taps seek. */
@Composable
internal fun SeekLabel(f: SeekFlash, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!f.ahead) Icon(PrismIcons.Previous, null, tint = Color.White, modifier = Modifier.size(16.dp))
        Text(
            (if (f.ahead) "+" else "−") + "${f.steps * f.step} s",
            color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 6.dp),
        )
        if (f.ahead) Icon(PrismIcons.Next, null, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

/**
 * "Covers blend too" (1.7.8, A6, after Apple Music): while a crossfade plays into [songId], the ending song's cover lies
 * over it and fades away over the blend, so the cover changes with the sound instead of at the blend's start. With
 * Reduce motion it simply switches halfway. Nothing is drawn otherwise.
 */
@Composable
private fun BlendingCover(songId: String, modifier: Modifier) {
    val c = LocalContainer.current
    val settings = com.prism.music.ui.theme.LocalAppSettings.current
    if (!settings.view.blendCovers) return
    val blend by c.mixState.blend.collectAsState()
    val now = blend?.takeIf { it.toId == songId } ?: return
    val queue by c.player.queue.collectAsState()
    val from = remember(now.fromId) { queue.firstOrNull { it.song.id == now.fromId }?.song } ?: return
    val alpha = if (settings.reduceMotion) (if (now.progress < 0.5f) 1f else 0f) else 1f - now.progress
    if (alpha <= 0f) return
    coil3.compose.AsyncImage(
        hiRes(from.thumbnail, 1200), null, contentScale = ContentScale.Crop,
        modifier = modifier.graphicsLayer { this.alpha = alpha },
    )
}

/**
 * The status bar's room at the top, also while it's hidden (Hide status bar): the player keeps its place and only the bar
 * goes (the owner, 1.8.0: it moved the whole player up).
 */
@kotlin.OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun statusBarRoom(): WindowInsets = WindowInsets.statusBarsIgnoringVisibility

/**
 * The player's title under the cover: a size under headlineMedium (#41; a tenth under, so longer titles fit before they
 * scroll), at Settings → Player → Title's size and weight.
 */
internal fun playerTitleStyle(type: androidx.compose.material3.Typography, settings: com.prism.music.data.prefs.AppSettings): androidx.compose.ui.text.TextStyle {
    val titleScale = settings.playerTitleSize.scale
    return type.headlineMedium.let { it.copy(fontSize = it.fontSize * 0.9f * titleScale, lineHeight = it.lineHeight * 0.9f * titleScale, fontWeight = settings.type.playerTitleWeight.title ?: it.fontWeight) }
}

/**
 * What a tap does on the small cover beside the title while a panel is open. Over the queue or the stats, and over the
 * lyrics with [tapPlays] off (Settings → Gestures & buttons, "Tap the small cover to play or pause", off by default), a
 * tap closes the panels ([onClose]), as always. Over the lyrics with it on (#464, 1.8.5), [lyricsCoverTaps]. With no
 * panel open, nothing (the big cover's own gestures are set elsewhere).
 */
@Composable
internal fun Modifier.smallCoverTaps(
    lyricsOpen: Boolean, otherPanelOpen: Boolean, tapPlays: Boolean, isPlaying: Boolean,
    onPlayPause: () -> Unit, onClose: () -> Unit,
): Modifier = when {
    lyricsOpen && !otherPanelOpen && tapPlays -> lyricsCoverTaps(isPlaying, onPlayPause, onClose)
    lyricsOpen || otherPanelOpen -> clickable { onClose() }
    else -> this
}

/**
 * #464 (1.8.5): the small cover beside the title while the lyrics are open, with "Tap the small cover to play or pause"
 * on. A tap plays or pauses (the lyrics stay up); a long press closes the lyrics, as a tap on it does with the switch
 * off. Nothing else is set on it there (the cover's own double tap, long press and swipes are for the big cover, and
 * wait while a panel is open).
 */
@Composable
internal fun Modifier.lyricsCoverTaps(isPlaying: Boolean, onPlayPause: () -> Unit, onClose: () -> Unit): Modifier {
    val haptics = com.prism.music.ui.theme.rememberHaptics()
    return combinedClickable(
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        indication = null,
        onClickLabel = if (isPlaying) tr(R.string.widget_pause) else tr(R.string.widget_play),
        onLongClickLabel = tr(R.string.close_lyrics),
        onLongClick = { haptics.click(); onClose() },
    ) { haptics.click(); onPlayPause() }
}

/** The artists' line under the player's title, a size smaller than it (#41). */
internal fun playerArtistStyle(type: androidx.compose.material3.Typography, settings: com.prism.music.data.prefs.AppSettings): androidx.compose.ui.text.TextStyle =
    type.bodyLarge.let { it.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = settings.type.playerTitleWeight.artist ?: it.fontWeight) }

/**
 * How tall the title and artist block under the cover (and in the header over the lyrics and the queue) is: 60 dp, or
 * as much as its two lines need when that's more. #464 (1.8.4.1): in a tall font (Nothing's NDot as the app's and the
 * titles' font) or a larger text size, the title took its height and the artist line got what was left of a fixed 60 dp,
 * so it was cut off at the bottom. Both lines are counted even when one is hidden (the header over the lyrics shows
 * both), so the cover doesn't change size as the player closes into it.
 */
@Composable
internal fun playerHeaderHeight(
    title: String,
    titleStyle: androidx.compose.ui.text.TextStyle,
    artist: String,
    artistStyle: androidx.compose.ui.text.TextStyle,
): Dp {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer(cacheSize = 4)
    val density = LocalDensity.current
    val needed = remember(title, titleStyle, artist, artistStyle, density, measurer) {
        fun line(text: String, style: androidx.compose.ui.text.TextStyle) =
            measurer.measure(text, style, maxLines = 1, softWrap = false).size.height
        with(density) { (line(title, titleStyle) + line(artist, artistStyle)).toDp() }
    }
    return maxOf(HEADER_HEIGHT, needed)
}
