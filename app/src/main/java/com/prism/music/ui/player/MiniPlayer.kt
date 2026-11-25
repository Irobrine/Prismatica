package com.prism.music.ui.player

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.ui.theme.PrismIcons
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.prism.music.data.prefs.MiniPlayerStyle
import com.prism.music.data.prefs.MiniProgress
import com.prism.music.data.prefs.PlayerGesture
import com.prism.music.ui.components.Artwork
import com.prism.music.ui.theme.GlassSurface
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalUi
import com.prism.music.ui.theme.hairline
import androidx.compose.ui.draw.drawBehind
import kotlin.math.roundToInt

@Composable
fun MiniPlayer(
    modifier: Modifier = Modifier,
    /** No surface of its own: it sits in the tab bar's glass ("Joined to the tab bar", 1.7.6). */
    bare: Boolean = false,
    onOpen: () -> Unit,
) {
    val pc = LocalContainer.current.player
    val song by pc.currentSong.collectAsState()
    val playing by pc.isPlaying.collectAsState()
    // Stopping clears the song at once. The card still has to be there to slide away, so it keeps the last one for its
    // exit (it used to go blank on that frame and vanish without the animation the rest of the chrome has).
    val last = remember { mutableStateOf<com.prism.music.data.model.Song?>(null) }
    LaunchedEffect(song) { if (song != null) last.value = song }
    val s = song ?: last.value ?: return
    val settings = LocalAppSettings.current
    val ui = LocalUi.current
    // Read only in the progress drawing below, so the playhead moving redraws that line, not the whole mini player.
    val position = rememberPositionState(fast = false)
    val drag = remember { DragOffset() }
    // Pulled down far enough, it stops the music and goes (#59).
    val pull = remember { DragOffset() }
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val haptics = com.prism.music.ui.theme.rememberHaptics()
    val style = settings.miniPlayerStyle
    // A swipe up is a share of the window's height (PlayerMorph), not of the card's: the card is small, the player whole.
    val screenPx = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    // How far a sideways swipe goes before it counts: 48 dp on every phone, not 140 raw pixels (which was half as far
    // on a dense screen as on a coarse one, for the same gesture).
    val swipePx = with(LocalDensity.current) { 48.dp.toPx() }
    val morphSpan = screenPx * PlayerMorph.TRAVEL
    var menu by remember { mutableStateOf(false) }
    if (menu) com.prism.music.ui.components.SongActionsSheet(s) { menu = false }
    val container = LocalContainer.current
    var sleep by remember { mutableStateOf(false) }
    if (sleep) SleepTimerSheet { sleep = false }
    // The mini player's swipes (Settings → Player → Gestures): what doesn't need the full player.
    fun runMiniGesture(g: PlayerGesture) {
        when (g) {
            PlayerGesture.NEXT -> pc.next()
            PlayerGesture.PREVIOUS -> pc.previous()
            PlayerGesture.SEEK_BACK -> pc.seekBy(-10_000L)
            PlayerGesture.SEEK_AHEAD -> pc.seekBy(10_000L)
            PlayerGesture.PLAY_PAUSE -> pc.togglePlay()
            PlayerGesture.LIKE -> container.library.toggleLike(s)
            PlayerGesture.SLEEP -> sleep = true
            PlayerGesture.MENU -> menu = true
            PlayerGesture.STOP -> pc.stop()
            // The ones about the full player open it, on the page they name, through the same morph as a tap.
            PlayerGesture.OPEN, PlayerGesture.QUEUE, PlayerGesture.LYRICS, PlayerGesture.VIDEO, PlayerGesture.SING -> {
                PlayerMorph.request(PlayerMorph.pageOf(g))
                onOpen()
            }
            PlayerGesture.SEEK_SIDES, PlayerGesture.NONE -> {}
        }
    }

    // Card: the full-width piece of glass over the tab bar, its progress line along the bottom.
    val bar = style == MiniPlayerStyle.CARD
    val height = when (style) { MiniPlayerStyle.CARD -> 64.dp; MiniPlayerStyle.SLIM -> 54.dp; MiniPlayerStyle.PILL -> 56.dp }
    val shape = when (style) {
        MiniPlayerStyle.CARD -> ui.card
        MiniPlayerStyle.SLIM -> ui.shape(16.dp)
        MiniPlayerStyle.PILL -> RoundedCornerShape(50)
    }
    val artSize = when (style) { MiniPlayerStyle.CARD -> 44.dp; MiniPlayerStyle.SLIM -> 40.dp; MiniPlayerStyle.PILL -> 44.dp }
    val artShape = if (style == MiniPlayerStyle.PILL) CircleShape else if (bar) ui.smallArt else ui.shape(9.dp)
    fun fraction() = (position.value.toFloat() / pc.duration.coerceAtLeast(1)).coerceIn(0f, 1f)
    val dark = com.prism.music.ui.theme.LocalIsDark.current
    val material = ui.material
    // #231 (1.7.9.2): the progress in your accent, plain ink or the cover's color instead of the split light.
    val progressBrush = miniProgressBrush(settings.view.miniProgressColor, com.prism.music.data.PlaylistCovers.artOf(s))
    // Material You: the mini player is a tonal surface one step above the page, with the primary as its progress.
    val tint = if (material) scheme.surfaceContainerHigh else com.prism.music.navFill(settings.navBarFill)
    val surface: @Composable (Modifier, @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) -> Unit = { m, content ->
        if (bare) Box(m, content = content)
        else GlassSurface(m, shape = shape, tint = tint, content = content)
    }

    surface(
        modifier
            .fillMaxWidth()
            .height(height)
            .testTag("miniPlayer")
            .offset { IntOffset(drag.value.roundToInt(), pull.value.roundToInt()) }
            .graphicsLayer { alpha = 1f - (pull.value / (height.toPx() * 1.6f)).coerceIn(0f, 0.85f) }
            .pointerInput(morphSpan) {
                val slop = viewConfiguration.touchSlop
                val stopAt = height.toPx() * 0.9f
                val goneAt = height.toPx() * 1.6f
                // Up (#149, 1.7.7): opening the player (or a page of it) is the morph, following the finger over the
                // window's height (PlayerMorph), and finished past the same 10% every other way into the player uses.
                // A swipe set to an action that doesn't need the full player still lifts a third of the drag, at most a
                // third of the card, as before (#222: one set to Nothing doesn't move it at all).
                val upAt = -height.toPx() * 0.22f
                val upMost = -height.toPx() * 0.34f
                var raw = 0f
                var morphing = false
                var base = 0f
                var rawX = 0f
                // The speed the card is let go at, so the morph it starts carries on at it (PlayerMorph.released).
                val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                fun letGoSpeed(): Float = -tracker.calculateVelocity().y / morphSpan

                // Up and down (below).
                fun verticalStart() {
                    pull.cancel()
                    tracker.resetTracking()
                    raw = pull.value
                    morphing = false
                }
                fun verticalDrag(change: androidx.compose.ui.input.pointer.PointerInputChange, d: Float) {
                    tracker.addPosition(change.uptimeMillis, change.position)
                    raw += d
                    val now = container.settings.current
                    if (raw < 0f && PlayerMorph.opensPlayer(now.miniSwipeUp)) {
                        if (!morphing) {
                            morphing = true
                            // The page the action names (the queue, the lyrics), or the cover.
                            PlayerMorph.request(PlayerMorph.pageOf(now.miniSwipeUp))
                            base = PlayerMorph.progress
                            PlayerMorph.beginDrag(towardPlayer = true)
                        }
                        val p = base + PlayerMorph.progressForTravel(-raw, morphSpan, towardPlayer = true)
                        if ((PlayerMorph.progress < PlayerMorph.COMPLETE_AT) != (p < PlayerMorph.COMPLETE_AT)) haptics.threshold()
                        PlayerMorph.dragTo(p)
                        pull.set(0f)
                    } else {
                        if (morphing) {
                            morphing = false
                            PlayerMorph.request(null)
                            PlayerMorph.endDrag(towardPlayer = true, complete = false, scope = scope, reduceMotion = now.reduceMotion, velocity = letGoSpeed())
                        }
                        // #222: a way with no gesture set (Settings → Player → Gestures: Nothing) doesn't move the card at all.
                        val after = when {
                            raw >= 0f -> if (now.miniSwipeDown == PlayerGesture.NONE) 0f else raw
                            now.miniSwipeUp == PlayerGesture.NONE -> 0f
                            else -> (raw / 3f).coerceAtLeast(upMost)
                        }
                        if ((pull.value < stopAt) != (after < stopAt) || (pull.value > upAt) != (after > upAt)) haptics.threshold()
                        pull.set(after)
                    }
                }
                fun verticalEnd() {
                    val now = container.settings.current
                    if (morphing) {
                        val v = letGoSpeed()
                        haptics.gestureEnd()
                        if (PlayerMorph.completes(PlayerMorph.progress, towardPlayer = true, velocity = v)) {
                            PlayerMorph.released(v)
                            onOpen()
                        } else {
                            PlayerMorph.request(null)
                            PlayerMorph.endDrag(towardPlayer = true, complete = false, scope = scope, reduceMotion = now.reduceMotion, velocity = v)
                        }
                        pull.settle(scope, 0f)
                        return
                    }
                    val up = now.miniSwipeUp
                    // Pulled down: stop (#59) or whatever's set in Settings → Player → Gestures (1.7.4).
                    val down = now.miniSwipeDown
                    when {
                        pull.value <= upAt && up != PlayerGesture.NONE -> {
                            haptics.gestureEnd()
                            runMiniGesture(up)
                            pull.settle(scope, 0f)
                        }
                        pull.value > stopAt && down != PlayerGesture.NONE -> {
                            haptics.gestureEnd()
                            if (down == PlayerGesture.STOP) {
                                // It falls away, then the music stops and the card's own exit takes it from there.
                                // The offset comes home only after that (or never: the card is gone) so the song
                                // kept for the exit doesn't flash back up at the top for a frame.
                                pull.settle(scope, goneAt, tween(160, easing = com.prism.music.ui.theme.PrismTokens.Motion.Ease)) {
                                    pc.stop()
                                    kotlinx.coroutines.delay(400)
                                    pull.reset(0f)
                                }
                            } else {
                                runMiniGesture(down)
                                pull.settle(scope, 0f)
                            }
                        }
                        else -> pull.settle(scope, 0f)
                    }
                }
                fun verticalCancel() {
                    if (morphing) {
                        PlayerMorph.request(null)
                        PlayerMorph.endDrag(towardPlayer = true, complete = false, scope = scope, reduceMotion = container.settings.current.reduceMotion)
                    }
                    pull.settle(scope, 0f)
                }

                // Sideways (the mini player's left and right swipes).
                fun sidewaysStart() {
                    drag.cancel()
                    rawX = drag.value
                }
                fun sidewaysDrag(d: Float) {
                    val before = drag.value
                    rawX += d
                    // #222: nor sideways, toward a swipe set to Nothing.
                    val now = container.settings.current
                    val after = if ((if (rawX < 0f) now.miniSwipeLeft else now.miniSwipeRight) == PlayerGesture.NONE) 0f else rawX
                    if ((kotlin.math.abs(before) < swipePx) != (kotlin.math.abs(after) < swipePx)) haptics.threshold()
                    drag.set(after)
                }
                fun sidewaysEnd() {
                    val now = container.settings.current
                    val g = when {
                        drag.value < -swipePx -> now.miniSwipeLeft
                        drag.value > swipePx -> now.miniSwipeRight
                        else -> PlayerGesture.NONE
                    }
                    if (g != PlayerGesture.NONE) { haptics.gestureEnd(); runMiniGesture(g) }
                    drag.settle(scope, 0f)
                }

                awaitEachGesture {
                    // One gesture for both ways, decided by the way the hand actually went. Two detectors racing (one
                    // each way) let a sideways thumb-arc take an up swipe: the card slid a little and the player never
                    // opened, and the same on the cover or a button could pass for a tap. The dominant way wins, and
                    // from the slop on the card consumes the drag on the first pass, so a swipe it takes is never also
                    // read as a tap by the card's own tap or by one of its buttons.
                    val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    // "Customize this" took the touch (it opens the mini player's own settings from a tap).
                    if (down.isConsumed) return@awaitEachGesture
                    var totalX = 0f
                    var totalY = 0f
                    // 0 until it's a drag at all (a tap, left to the card and its buttons), 1 up and down, 2 sideways.
                    var axis = 0
                    var finished = false
                    while (true) {
                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val dx = change.position.x - change.previousPosition.x
                        val dy = change.position.y - change.previousPosition.y
                        if (axis == 0) {
                            // Something above took it (the page's own sideways swipe, a sheet).
                            if (change.isConsumed) break
                            totalX += dx
                            totalY += dy
                            val across = kotlin.math.abs(totalX)
                            val along = kotlin.math.abs(totalY)
                            // Up and down as soon as the hand has gone that way, however much it drifted sideways
                            // getting there. Sideways only once it has clearly gone further sideways than up: a
                            // thumb's arc starts a fast swipe with more sideways travel than up, so committing to
                            // the sideways way at the first slop took the swipe and nothing came of it.
                            val way = when {
                                along >= slop -> 1
                                across >= slop * 1.5f && across >= along * 1.8f -> 2
                                else -> 0
                            }
                            if (way == 0) {
                                if (change.pressed) continue
                                // Lifted before a way was clear: a real move is still a swipe (the longer way); anything
                                // shorter was a tap, left to the card and its buttons.
                                if (maxOf(across, along) < slop) { finished = true; break }
                                axis = if (along >= across) 1 else 2
                            } else axis = way
                            change.consume()
                            // The slop is the price of starting a drag; what's past it moves the card.
                            if (axis == 1) {
                                verticalStart()
                                verticalDrag(change, totalY - kotlin.math.sign(totalY) * slop)
                            } else {
                                sidewaysStart()
                                sidewaysDrag(totalX - kotlin.math.sign(totalX) * slop)
                            }
                        } else {
                            change.consume()
                            if (axis == 1) verticalDrag(change, dy) else sidewaysDrag(dx)
                        }
                        if (!change.pressed) {
                            if (axis == 1) verticalEnd() else sidewaysEnd()
                            finished = true
                            break
                        }
                    }
                    // The pointer was taken away mid-drag (the system or a sheet): put the card back.
                    if (!finished) {
                        if (axis == 1) verticalCancel()
                    }
                }
            },
    ) {
        // Fill: the played part of the song tints the whole card, in the split light (red at the start, violet at the
        // end; White light's accent or greys).
        if (settings.miniProgress == MiniProgress.FILL) Box(
            Modifier.matchParentSize().drawBehind {
                val f = fraction()
                if (f > 0f) drawRect(
                    progressBrush ?: if (material) androidx.compose.ui.graphics.SolidColor(scheme.primary) else com.prism.music.ui.theme.PrismTokens.spectrum(dark, 0f, size.width),
                    size = androidx.compose.ui.geometry.Size(size.width * f, size.height),
                    alpha = settings.view.miniFillStrength * (if (dark) 1f else 0.82f),
                )
            },
        )
        Row(
            // Holding it brings up the song's actions (like, add to playlist, share…) without opening the player.
            Modifier.fillMaxSize().combinedClickable(onClick = { PlayerMorph.request(null); onOpen() }, onLongClick = { haptics.click(); menu = true })
                .padding(start = if (style == MiniPlayerStyle.PILL) 6.dp else if (bar) 16.dp else 8.dp, end = if (style == MiniPlayerStyle.PILL) 8.dp else if (bar) 8.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The cover changes with the song too: a soft hand-over, not a cut from one picture to the next. While the
            // player's cover is flying back, the mini player's own stays unseen (the flying one lands in its place).
            AnimatedContent(
                com.prism.music.data.PlaylistCovers.artOf(s),
                transitionSpec = {
                    val ease = com.prism.music.ui.theme.PrismTokens.Motion.Ease
                    fadeIn(tween(com.prism.music.ui.theme.PrismTokens.Motion.STANDARD, easing = ease)) togetherWith
                        fadeOut(tween(com.prism.music.ui.theme.PrismTokens.Motion.QUICK))
                },
                modifier = Modifier.size(artSize).graphicsLayer {
                    // While the player is opening or closing, the moving cover draws this one's part.
                    alpha = if (CoverFlight.flying) 0f else 1f
                },
                label = "miniArt",
            ) { art ->
                Artwork(art, with(CoverFlight) { Modifier.fillMaxSize().miniCover() }, artShape, size = 226)
            }
            Spacer(Modifier.width(12.dp))
            AnimatedContent(
                s,
                // A small, quick hand-over: the old title leaves as the new one arrives, without either sliding a third of
                // the card or hanging about at the end of a spring.
                transitionSpec = {
                    val ease = com.prism.music.ui.theme.PrismTokens.Motion.Ease
                    (slideInHorizontally(tween(200, easing = ease)) { it / 6 } + fadeIn(tween(180, easing = ease))) togetherWith
                        (slideOutHorizontally(tween(160, easing = ease)) { -it / 6 } + fadeOut(tween(120)))
                },
                modifier = Modifier.weight(1f),
                label = "mini",
            ) { playing ->
                // #328: as the version picked to play in its place, if any.
                val song = com.prism.music.data.SongVersions.shownOf(playing)
                Column {
                    Text(
                        song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.miniTitleAnchor().graphicsLayer { alpha = if (PlayerMorph.hidesTitle) 0f else 1f },
                    )
                    if (style != MiniPlayerStyle.SLIM || song.artistText.isNotBlank()) Text(
                        song.artistText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                        modifier = Modifier.miniArtistAnchor().graphicsLayer { alpha = if (PlayerMorph.hidesArtist) 0f else 1f },
                    )
                }
            }
            if (settings.miniPrevButton) {
                IconButton(
                    onClick = { haptics.click(); pc.previous() },
                    modifier = Modifier.size(40.dp).miniPrevAnchor().graphicsLayer { alpha = if (PlayerMorph.hidesPrev) 0f else 1f },
                ) { Icon(PrismIcons.Previous, tr(R.string.widget_previous)) }
            }
            // Play / pause can be left off (Reddit, 1.8.1): a tap still opens the player.
            if (!settings.view.miniPlayButton) {
                if (!settings.miniSkipButtons) Spacer(Modifier.width(8.dp))
            } else {
                // The morph's moving copy starts on exactly these two (PlayerMorph.MorphPlay).
                val (playDisc, playTint) = miniPlayColors(style)
                if (style == MiniPlayerStyle.PILL) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(playDisc)
                            .miniPlayAnchor().graphicsLayer { alpha = if (PlayerMorph.hidesPlay) 0f else 1f }
                            .clickable { haptics.click(); pc.togglePlay() },
                        contentAlignment = Alignment.Center,
                    ) { MiniPlayPause(playing, 24.dp, tint = playTint) }
                } else {
                    IconButton(
                        onClick = { haptics.click(); pc.togglePlay() },
                        modifier = Modifier.size(44.dp).miniPlayAnchor().graphicsLayer { alpha = if (PlayerMorph.hidesPlay) 0f else 1f },
                    ) {
                        MiniPlayPause(playing, if (style == MiniPlayerStyle.SLIM) 26.dp else 28.dp, tint = playTint)
                    }
                }
            }
            if (settings.miniSkipButtons) {
                IconButton(
                    onClick = { haptics.click(); pc.next() },
                    modifier = Modifier.size(40.dp).miniNextAnchor().graphicsLayer { alpha = if (PlayerMorph.hidesNext) 0f else 1f },
                ) { Icon(PrismIcons.Next, tr(R.string.widget_next)) }
            }
        }
        if (settings.miniProgress == MiniProgress.LINE) {
            Canvas(Modifier.fillMaxWidth().height(2.dp).align(Alignment.BottomCenter).padding(horizontal = if (style == MiniPlayerStyle.PILL) 28.dp else 18.dp)) {
                drawLine(if (material) scheme.secondaryContainer else scheme.onSurface.copy(alpha = 0.12f), Offset(0f, 0f), Offset(size.width, 0f), 2.5.dp.toPx(), StrokeCap.Round)
                // Progress in split light (deeper on light glass).
                val f = fraction()
                if (f > 0f) drawLine(
                    progressBrush ?: if (material) androidx.compose.ui.graphics.SolidColor(scheme.primary) else com.prism.music.ui.theme.PrismTokens.spectrum(dark, 0f, size.width), Offset(0f, 0f), Offset(size.width * f, 0f), 2.5.dp.toPx(), StrokeCap.Round,
                )
            }
        }
    }
}

/**
 * The mini player's play control as it draws it: the disc's fill (clear where it's a bare glyph) and the glyph's tint.
 * [PlayerMorph]'s moving copy starts on exactly these two, so opening it doesn't change the control's color in a frame.
 */
@Composable
internal fun miniPlayColors(style: MiniPlayerStyle): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> {
    val scheme = MaterialTheme.colorScheme
    val material = LocalUi.current.material
    return if (style == MiniPlayerStyle.PILL) {
        (if (material) scheme.primary else scheme.onSurface) to (if (material) scheme.onPrimary else scheme.surface)
    } else {
        androidx.compose.ui.graphics.Color.Transparent to androidx.compose.material3.LocalContentColor.current
    }
}

/**
 * The mini player's play / pause, swapped with the same quick cross-fade as the full player's disc: before a plain cut
 * from one glyph to the other, a small hard edge in the one control that's touched most.
 */
@Composable
private fun MiniPlayPause(playing: Boolean, size: androidx.compose.ui.unit.Dp, tint: androidx.compose.ui.graphics.Color? = null) {
    Crossfade(playing, animationSpec = tween(com.prism.music.ui.theme.PrismTokens.Motion.QUICK), label = "miniPlayPause") { p ->
        Icon(
            if (p) PrismIcons.Pause else PrismIcons.Play,
            tr(R.string.play_pause),
            Modifier.size(size),
            tint = tint ?: androidx.compose.material3.LocalContentColor.current,
        )
    }
}

/** The mini player's progress in [color] (#231); null keeps the split light. Unlike the player it can sit on light glass. */
@Composable
private fun miniProgressBrush(color: com.prism.music.data.prefs.ScrubberColor, cover: String?):androidx.compose.ui.graphics.Brush? {
    val scheme = MaterialTheme.colorScheme
    return when (color) {
        com.prism.music.data.prefs.ScrubberColor.SPECTRUM -> null
        com.prism.music.data.prefs.ScrubberColor.WHITE -> androidx.compose.ui.graphics.SolidColor(scheme.onSurface)
        com.prism.music.data.prefs.ScrubberColor.ACCENT -> androidx.compose.ui.graphics.SolidColor(scheme.primary)
        com.prism.music.data.prefs.ScrubberColor.COVER -> {
            val palette = com.prism.music.ui.components.rememberArtPalette(cover)
            androidx.compose.ui.graphics.SolidColor(palette?.vibrant ?: scheme.primary)
        }
    }
}