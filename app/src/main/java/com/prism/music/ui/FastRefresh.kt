package com.prism.music.ui

import android.view.Display
import android.view.Window
import androidx.compose.animation.core.Transition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
 * The system otherwise keeps a window at 60 Hz (and Android 15/16 steps it down while it isn't touched), which
 * made the queue and lyrics panels move at 60 on a 120 Hz panel. Nothing is changed while Prism is still.
 *
 * The ask has to be in before the animation's first frame: asked a frame or two late, the display stayed where it was
 * for the whole move (measured: rises that began with the hold already held ran a clean 120 Hz, while ones that asked
 * 12–18 ms late stayed at 72 Hz throughout). So the animations hold it themselves, from their own start ([hold]), and
 * each holds with its own token: an ease replaced mid-way lets go of its token without dropping the one that took over.
 */
object FastRefresh {
    private var window = WeakReference<Window?>(null)
    private var bestMode = 0
    private var bestRate = 0f
    /** The animations asking for it right now; overlapping ones (a panel over the mini player's slide) share the hold. */
    private val holders = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())

    /** How long the fastest mode is kept after the last animation settles (see [drop]). */
    private const val LINGER_MS = 700L
    private val linger = android.os.Handler(android.os.Looper.getMainLooper())
    private val letGo = Runnable { letGoNow() }

    /** Called once per activity, with the display its window is on. */
    fun attach(w: Window, display: Display?) {
        window = WeakReference(w)
        holders.clear()
        linger.removeCallbacks(letGo)
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

    /** One animation starts asking for the fastest mode; [token] is the animation's own. */
    fun hold(token: Any) {
        if (bestMode == 0) return
        if (!holders.add(token)) return
        linger.removeCallbacks(letGo)
        if (holders.size > 1) return
        val w = window.get() ?: return
        w.attributes = w.attributes.apply {
            preferredDisplayModeId = bestMode
            preferredRefreshRate = bestRate
        }
        // Android 15's view-level rate API asks for the rate itself, as a source that keeps producing frames at it:
        // the mode hint alone let the system step the display down under the move (measured at 72 Hz).
        if (android.os.Build.VERSION.SDK_INT >= 35) w.decorView.setRequestedFrameRate(bestRate)
        android.os.Trace.beginAsyncSection(MotionTrace.FAST_MODE, 1)
    }

    /** The animation that held it with [token] is over (or was replaced): let go once nothing else is holding it. */
    fun drop(token: Any) {
        if (!holders.remove(token)) return
        if (holders.isNotEmpty()) return
        // A moment's grace: a close usually follows its open within a second, and giving the mode up between the two
        // left the second move at 72 Hz for its whole length even though it asked for the fastest mode again.
        linger.removeCallbacks(letGo)
        linger.postDelayed(letGo, LINGER_MS)
    }

    /** The grace is over: back to the mode the system picks, and the end of the trace's held section. */
    private fun letGoNow() {
        if (holders.isNotEmpty()) return
        val w = window.get() ?: return
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
    val token = remember { Any() }
    LaunchedEffect(active) { if (active) FastRefresh.hold(token) else FastRefresh.drop(token) }
    DisposableEffect(Unit) {
        onDispose { FastRefresh.drop(token) }
    }
}

/** [FastWhile] for an enter/exit transition, true for exactly the frames it animates. */
@Composable
fun FastWhileRunning(transition: Transition<*>) {
    val running by remember(transition) { derivedStateOf { transition.isRunning } }
    FastWhile(running)
}
