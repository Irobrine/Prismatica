package com.prism.music.ui

import android.view.Display
import android.view.Window
import androidx.compose.animation.core.Transition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import java.lang.ref.WeakReference

/**
 * The names of the section a frame trace (Perfetto) shows for Prism's own motion: never read by anyone, only looked up
 * by a trace, so they live here rather than in strings.xml with the text people read.
 */
internal object MotionTrace {
    /** One panel's ease (PlayerMorph's PanelShift): the exact frames its rise or fall runs on. */
    const val PANEL_MORPH = "PrismPanelMorph"

    /** The display's fastest mode being held for those frames (see [FastRefresh]). */
    const val FAST_MODE = "PrismFastMode"
}

/**
 * Asks the display for its fastest mode only while an animation runs, and lets the system choose again afterwards.
 * The system otherwise keeps a window at 60 Hz (and Android 15/16 steps it down while it isn't being touched), which
 * made the queue and lyrics panels move at 60 on a 120 Hz panel. Nothing is changed while Prism is still.
 *
 * The mode change is a hint: it takes effect with the animation's first frame and is undone on its last.
 */
object FastRefresh {
    private var window = WeakReference<Window?>(null)
    private var bestMode = 0
    private var bestRate = 0f
    /** The number of animations asking for it (they can overlap: a panel morph and the mini player's slide). */
    private var holds = 0

    /** Called once per activity, with the display its window is on. */
    fun attach(w: Window, display: Display?) {
        window = WeakReference(w)
        holds = 0
        bestMode = 0
        bestRate = 0f
        val modes = display?.supportedModes?.toList().orEmpty()
        val now = display?.mode ?: return
        // The fastest mode at the size the window has now: another resolution could letterbox it.
        val best = modes.filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }
            .maxByOrNull { it.refreshRate }
            ?: modes.maxByOrNull { it.refreshRate }
            ?: return
        if (best.refreshRate > 60f) {
            bestMode = best.modeId
            bestRate = best.refreshRate
        }
    }

    fun acquire() {
        val w = window.get() ?: return
        if (bestMode == 0) return
        if (holds++ > 0) return
        w.attributes = w.attributes.apply {
            preferredDisplayModeId = bestMode
            preferredRefreshRate = bestRate
        }
        // The mode hint alone lets the system step the display down under the move (measured 72 Hz stretches). Android
        // 15's view-level rate API asks for the rate itself, as a source that keeps producing frames at it.
        if (android.os.Build.VERSION.SDK_INT >= 35) w.decorView.setRequestedFrameRate(bestRate)
        android.os.Trace.beginAsyncSection(MotionTrace.FAST_MODE, 1)
    }

    fun release() {
        val w = window.get() ?: return
        if (holds == 0) return
        if (--holds > 0) return
        android.os.Trace.endAsyncSection(MotionTrace.FAST_MODE, 1)
        // A rate that isn't a number is "no preference" to the view API.
        if (android.os.Build.VERSION.SDK_INT >= 35) w.decorView.setRequestedFrameRate(Float.NaN)
        w.attributes = w.attributes.apply {
            preferredDisplayModeId = 0
            preferredRefreshRate = 0f
        }
    }
}

/** Holds the fastest mode while [active], letting it go when it turns false or this leaves the composition. */
@Composable
fun FastWhile(active: Boolean) {
    val held = remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active && !held.value) {
            held.value = true
            FastRefresh.acquire()
        } else if (!active && held.value) {
            held.value = false
            FastRefresh.release()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (held.value) {
                held.value = false
                FastRefresh.release()
            }
        }
    }
}

/** [FastWhile] for an enter/exit transition, true for exactly the frames it animates. */
@Composable
fun FastWhileRunning(transition: Transition<*>) {
    val running by remember(transition) { derivedStateOf { transition.isRunning } }
    FastWhile(running)
}
