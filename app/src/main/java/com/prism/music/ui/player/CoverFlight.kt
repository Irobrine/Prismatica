package com.prism.music.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.prism.music.data.model.hiRes
import kotlin.math.roundToInt

/**
 * The cover's place in the two players, and whether it's between them. The flight is no longer a transition of its
 * own: it is the artwork's share of [PlayerMorph], so the cover follows the same finger as everything else and stops
 * being a second animation running beside the first. Instantiated before the morph system was general (1.7.8, #199),
 * it keeps its name and the flags the tests and the player look at.
 */
object CoverFlight {
    /** The mini player's cover, in the window. Kept still while the morph is in flight. */
    var mini: Rect? = null

    /** The player's cover, once it's laid out (null while the player is closed). */
    var player by androidx.compose.runtime.mutableStateOf<Rect?>(null)

    /** "Cover flies back": set from Settings → Player → Look; only then does a closing morph read as a return. */
    var backOn by androidx.compose.runtime.mutableStateOf(false)

    /** The morph's own number, kept under the name the layer and the tests know. */
    val progress: Float get() = PlayerMorph.progress

    /** Between the two covers right now (Reduce motion has no flight at all, and a cover that can't fly keeps its own). */
    val flying: Boolean get() = PlayerMorph.drawsOverlay() && mini != null && player != null

    /** On its way back into the mini player. */
    val returning: Boolean get() = flying && backOn && !PlayerMorph.opening

    /** Where the mini player's cover is, kept while the mini player is at rest. */
    fun Modifier.miniCover(): Modifier = onGloballyPositioned {
        if (PlayerMorph.progress <= 0.001f) mini = it.boundsInRoot()
    }

    /** Where the player's cover is, kept as it moves. */
    fun Modifier.playerCover(): Modifier = onGloballyPositioned { player = it.boundsInRoot() }

    /** #223: whether closing the player flies the cover back. As the way in, it's never with Reduce motion (or Performance mode). */
    fun returnsWith(flight: Boolean, back: Boolean, reduceMotion: Boolean): Boolean = flight && back && !reduceMotion

    const val FLIGHT_MS = 420
}

/** The flying cover, over everything, while [CoverFlight.flying]. */
@Composable
fun CoverFlightLayer(thumbnail: String?) {
    if (!CoverFlight.flying) return
    val from = CoverFlight.mini ?: return
    val to = CoverFlight.player ?: return
    val density = LocalDensity.current
    val small = with(density) { 8.dp.toPx() }
    val big = with(density) { 14.dp.toPx() }
    Box(
        Modifier
            // Placed and sized every frame (a layout step only): the cover between the two places, square as it is.
            .layout { m, c ->
                val f = PlayerMorph.progress
                val from = CoverFlight.mini ?: from
                val w = (from.width + (to.width - from.width) * f).roundToInt().coerceAtLeast(1)
                val h = (from.height + (to.height - from.height) * f).roundToInt().coerceAtLeast(1)
                val p = m.measure(Constraints.fixed(w, h))
                layout(c.maxWidth, c.maxHeight) {
                    p.place((from.left + (to.left - from.left) * f).roundToInt(), (from.top + (to.top - from.top) * f).roundToInt())
                }
            }
            .graphicsLayer {
                clip = true
                shape = RoundedCornerShape(small + (big - small) * PlayerMorph.progress)
            },
    ) {
        // The mini player's picture (already loaded) under the large one, which may still be on its way.
        coil3.compose.AsyncImage(thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        coil3.compose.AsyncImage(hiRes(thumbnail, 1200), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}
