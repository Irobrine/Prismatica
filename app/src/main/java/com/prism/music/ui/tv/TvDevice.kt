package com.prism.music.ui.tv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration

/**
 * Prism TV (1.8.0): whether this device is a TV (Android TV / Google TV). One APK serves both: on a TV, Prism opens
 * [com.prism.music.TvActivity] and its own screens (`ui/tv`), never the phone's.
 *
 * [forced] (Settings → Speakers, TVs & browser → "Force TV mode") makes any device behave as a TV: the phone's screens
 * send themselves to [com.prism.music.TvActivity], the app asks for landscape, and Prism Connect offers it as a TV. It
 * is off in the TV's Settings → About again, which is reachable whatever the device.
 */
object TvDevice {
    /** Whether the system itself says this device is a TV: what a Force TV mode switch on a real TV mustn't change. */
    fun isRealTv(context: Context): Boolean =
        context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

    /**
     * Force TV mode, kept here (not read from the settings on every check): the app's settings are warmed before the
     * first screen, and [com.prism.music.AppContainer] writes this both then and whenever the setting changes.
     */
    @Volatile
    var forced: Boolean = false

    fun isTv(context: Context): Boolean = forced || isRealTv(context)

    /**
     * The orientation Prism TV's screen asks for: landscape while forced (a phone held either way), nothing on a TV,
     * which is landscape already and keeps its own. The window turns, not the device.
     */
    fun orientationFor(forced: Boolean): Int? =
        if (forced) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else null
}

/**
 * What a Force TV mode setting emission means: the first one only seeds the flag ([SEED]), a different later one is the
 * change the advertised record has to follow ([CHANGED]); the same one again is nothing ([SAME]). The switches write
 * the flag at once, so comparing with the flag itself would never see a change (a collector runs after them).
 */
enum class TvModeChange { SEED, SAME, CHANGED }

fun tvModeChange(last: Boolean?, now: Boolean): TvModeChange = when {
    last == null -> TvModeChange.SEED
    last == now -> TvModeChange.SAME
    else -> TvModeChange.CHANGED
}

/**
 * The activity this context is drawn in, through the wrappers Compose's LocalContext may hold; null outside one. The
 * Force TV mode switches hand over between the phone's screens and the TV's, and the one left finishes.
 */
fun Context.activityOrNull(): android.app.Activity? {
    var c: Context? = this
    while (c != null) {
        if (c is android.app.Activity) return c
        c = (c as? android.content.ContextWrapper)?.baseContext
    }
    return null
}
