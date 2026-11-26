package com.prism.music

import com.prism.music.ui.components.quietGraphicsLayers
import com.prism.music.ui.components.tabTint
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import com.prism.music.ui.components.customizable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.prism.music.data.model.Song
import com.prism.music.data.model.hiRes
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.MiniPlayerStyle
import com.prism.music.data.prefs.NavBarStyle
import com.prism.music.data.prefs.NavLabels
import com.prism.music.data.prefs.NavTab
import com.prism.music.download.SmartDownloadWorker
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Navigator
import com.prism.music.ui.Routes
import com.prism.music.ui.player.MiniPlayer
import com.prism.music.ui.player.NowPlayingScreen
import com.prism.music.ui.player.PlayerMorph
import com.prism.music.ui.player.sanely
import com.prism.music.ui.player.playerMorphMini
import com.prism.music.ui.screens.ArtistScreen
import com.prism.music.ui.screens.ArtistConnectionsScreen
import com.prism.music.ui.screens.BrowseScreen
import com.prism.music.ui.screens.CatalogueScreen
import com.prism.music.ui.screens.CollectionScreen
import com.prism.music.ui.screens.CollectionType
import com.prism.music.ui.screens.EqualizerScreen
import com.prism.music.ui.screens.HomeScreen
import com.prism.music.ui.screens.LibraryScreen
import com.prism.music.ui.screens.LoginScreen
import com.prism.music.ui.screens.ReplayScreen
import com.prism.music.ui.screens.SearchScreen
import com.prism.music.ui.screens.SettingsScreen
import com.prism.music.ui.screens.WelcomeScreen
import com.prism.music.ui.theme.BackdropScreen
import com.prism.music.ui.theme.GlassSurface
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalHazeState
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.theme.PrismTheme
import com.prism.music.ui.theme.ScreenBackdrop
import com.prism.music.ui.theme.hairline
import com.prism.music.ui.theme.ink3
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.semantics.selected
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The app's screen. Declared in the manifest as [PrismActivity], so that ".MainActivity" can be the classic app-icon
 * alias (#20) and home-screen icons made before stay valid.
 */
/** How .prismlook files are sent (a zip: the Look's code and any photos or font it carries). */
private const val LOOK_MIME = "application/zip"

open class MainActivity : ComponentActivity() {
    // Prism's language, when picked in Prism (below Android 13; AppLanguage).
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.prism.music.AppLanguage.wrap(base))

    // Each time Prism comes to the front (at most hourly): it can stay alive for days, with Home never built again (1.8.1.1).
    override fun onResume() {
        super.onResume()
        container.updates.check()
    }
    private val deepLink = MutableStateFlow<Uri?>(null)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Prism TV (1.8.0): on a TV, Prism's screens are TvActivity's (made for a remote); whatever opened this one (a
        // link, the media notification, an old shortcut) opens that instead.
        if (com.prism.music.ui.tv.TvDevice.isTv(this)) {
            // Added to its flags, not in their place: a shared file's or link's read permission comes with them.
            startActivity(Intent(intent).setComponent(android.content.ComponentName(this, TvActivity::class.java)).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
            return
        }
        val c = container
        c.player.connect()
        // The downloads start in the background (their index and cache come off the disk) while the first frame draws;
        // screens read c.downloadState meanwhile, so they never wait for them (1.7.9.3: Home's first frame waited 3.4 s
        // on a slow phone). Media3's download manager still answers on the main thread.
        c.scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { c.downloads } }
        // Not asked here any more (a prompt before the welcome screen, with no reason given): the tour or the first
        // download asks, through this.
        if (c.returningUser == null) c.returningUser = c.settings.current.let { it.onboarded || it.isLoggedIn }
        c.notificationPrompt = { if (Build.VERSION.SDK_INT >= 33) runOnUiThread { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) } }
        SmartDownloadWorker.schedule(this, c.settings.current.smartDownloads)
        // Tidies the smart-download queue (it only adds more if it's been a while).
        if (c.settings.current.smartDownloads) SmartDownloadWorker.runNow(this, force = false)
        // Lossless files added while Prism is closed are picked up by LosslessSyncWorker, scheduled by LosslessSync
        // while the lossless add-on is on.
        if (c.settings.current.isLoggedIn) c.library.syncInBackground()
        // A link, Look or Blend shared to Prism is handled once. Android hands the activity the intent it was first
        // started with again when it comes back after being closed (state restored, opened from Recents, after an
        // update), which showed a shared Look's dialog again on every restart (found in 1.7.7's release check).
        val fresh = savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0
        deepLink.value = if (fresh) linkIn(intent) else null
        if (intent.action != Intent.ACTION_MAIN) setIntent(Intent(this, javaClass).setAction(Intent.ACTION_MAIN))
        requestFastestRefreshRate()
        setContent {
            CompositionLocalProvider(
                LocalContainer provides c,
                com.prism.music.ui.player.LocalPip provides inPip.value,
                com.prism.music.ui.theme.LocalWindowLayout provides com.prism.music.ui.theme.rememberWindowLayout(),
            ) {
                PrismRoot(deepLink, launchFresh = fresh)
            }
        }
        quietGraphicsLayers()
    }

    /**
     * Asks for the panel's fastest mode. Without it the system keeps Prism's window at 60 Hz (a normal app's frame
     * rate, even on a 120 Hz panel), so the queue and lyrics panels move at 60 and read as stuttering.
     */
    private fun requestFastestRefreshRate() {
        val d = if (Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
        val modes = d?.supportedModes?.toList().orEmpty()
        if (modes.isEmpty()) return
        val now = d?.mode ?: return
        // The same size as the window has now (a mode with another resolution could letterbox it), at its fastest.
        val best = (modes.filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }.maxByOrNull { it.refreshRate }
            ?: modes.maxByOrNull { it.refreshRate }) ?: return
        if (best.refreshRate <= 60f) return
        window.attributes = window.attributes.apply {
            preferredRefreshRate = best.refreshRate
            preferredDisplayModeId = best.modeId
        }
        // Android 15/16 power-saves by stepping a window down to 60 while it isn't being touched: a panel that eases
        // open after a tap then runs at 60 on a 120 Hz panel. These ask for the display's own rate through the
        // animation instead. (Guarded: only the newest Androids have them.)
        if (Build.VERSION.SDK_INT >= 35) runCatching { window.setFrameRateBoostOnTouchEnabled(true) }
        if (Build.VERSION.SDK_INT >= 36) runCatching { window.setFrameRatePowerSavingsBalanced(false) }
    }

    // ---------------------------------------------------------------- Picture-in-picture

    /** True while Prism is shown as a picture-in-picture window (a music video floating over other apps). */
    private val inPip = androidx.compose.runtime.mutableStateOf(false)
    /** A music video is playing on the open player, so leaving Prism floats it. */
    private var pipEligible = false
    private var pipAspect = android.util.Rational(16, 9)

    private val pipSupported by lazy { packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE) }

    /** Settings → Controls & gestures → Picture-in-picture: "Only with its button" (#167) never floats the video by itself. */
    private val pipOnLeave: Boolean get() = container.settings.current.behavior.pip == com.prism.music.data.prefs.PipMode.AUTO

    private fun pipParams(): android.app.PictureInPictureParams = android.app.PictureInPictureParams.Builder()
        .setAspectRatio(pipAspect)
        .apply { if (Build.VERSION.SDK_INT >= 31) { setAutoEnterEnabled(pipEligible && pipOnLeave); setSeamlessResizeEnabled(true) } }
        .build()

    /** The player says whether a music video is playing on it, and its shape ([width] × [height], 0 if unknown). */
    fun setPipEligible(eligible: Boolean, width: Int = 0, height: Int = 0) {
        if (!pipSupported) return
        pipEligible = eligible
        // Android only takes ratios between 1:2.39 and 2.39:1.
        if (width > 0 && height > 0) pipAspect = android.util.Rational(width, height).let { r ->
            when {
                r.toFloat() > 2.39f -> android.util.Rational(239, 100)
                r.toFloat() < 1 / 2.39f -> android.util.Rational(100, 239)
                else -> r
            }
        }
        // Android 12+ floats the video by itself when the listener goes Home.
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    /** Floats the music video now (the player's PiP button). False if Android or the listener's settings won't allow it. */
    fun enterPip(): Boolean = pipSupported && runCatching { enterPictureInPictureMode(pipParams()) }.getOrDefault(false)

    /** A touch or key: the listener is here (desk mode's ambient screen waits, or goes). */
    override fun onUserInteraction() {
        super.onUserInteraction()
        com.prism.music.ui.tv.Idle.touched()
    }

    /** Every touch: on slow phones background song scans wait while the listener scrolls (#343). */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        com.prism.music.playback.MixTempo.touched(ev.actionMasked)
        return super.dispatchTouchEvent(ev)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Before Android 12 there's no auto-enter: going Home with a video playing floats it here.
        if (Build.VERSION.SDK_INT < 31 && pipEligible && pipOnLeave) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        linkIn(intent)?.let { deepLink.value = it }
    }

    /** While casting, the volume keys turn the speaker or TV up and down (with Prism open; elsewhere, the media session does). */
    private var castVolumeKeyAt = 0L

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val cast = container.cast
        if (cast.casting && (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
            // #534: one step per press, and a held key no faster than the phone's own volume climbs.
            val now = android.os.SystemClock.uptimeMillis()
            if (com.prism.music.playback.RemoteVolume.takesKey(event.repeatCount, now, castVolumeKeyAt)) {
                castVolumeKeyAt = now
                val up = keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP
                cast.setVolume(com.prism.music.playback.RemoteVolume.stepped(cast.volume ?: 50, if (up) 1 else -1))
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (container.cast.casting && (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) return true
        return super.onKeyUp(keyCode, event)
    }

    /** A music.youtube.com link opened in Prism, or a YouTube / YouTube Music link shared to it. */
    private fun linkIn(intent: Intent?): Uri? {
        // A friend's Blend code, shared to Prism from a chat: Blend asks whether to make it.
        val blendCode = if (intent?.action == Intent.ACTION_SEND) com.prism.music.data.blend.BlendCode.findIn(intent.getStringExtra(Intent.EXTRA_TEXT)) else null
        if (blendCode != null) {
            container.blends.pending.value = blendCode
            return Uri.parse("prism://blends")
        }
        // A Look (1.7.3): a code in a message, or a .prismlook file sent or opened. Shown first, with Apply and Save.
        val lookCode = if (intent?.action == Intent.ACTION_SEND) com.prism.music.data.looks.LookCode.findIn(intent.getStringExtra(Intent.EXTRA_TEXT)) else null
        if (lookCode != null) {
            container.looks.decode(lookCode)?.let { container.looks.pending.value = it }
                ?: Toast.makeText(this, tr(R.string.that_look_code_couldn_t_be), Toast.LENGTH_SHORT).show()
            return Uri.parse("prism://looks")
        }
        // An audio file opened with Prism from a file manager (1.8.5): it plays on its own, from the file, and the
        // player opens. The opener's read grant comes with the intent; nothing is copied. Prism's own files and providers
        // are never opened this way (another app could name them).
        intent?.data?.takeIf {
            com.prism.music.data.local.OpenedAudio.accepts(intent.action, it, intent.type ?: runCatching { contentResolver.getType(it) }.getOrNull()) &&
                !com.prism.music.data.local.OpenedAudio.prismsOwn(this, it)
        }?.let { file ->
            container.scope.launch(Dispatchers.IO) {
                val song = com.prism.music.data.local.OpenedAudio.read(applicationContext, file)
                withContext(Dispatchers.Main) {
                    container.player.playQueue(listOf(song), 0, com.prism.music.playback.QueueSource(com.prism.music.playback.QueueKind.SINGLE, song.title))
                }
            }
            return Uri.parse("prism://player")
        }
        lookFile(intent)?.let { file ->
            container.scope.launch(Dispatchers.Main) {
                val look = runCatching { contentResolver.openInputStream(file)?.use { container.looks.readFile(it) } }
                look.getOrNull()?.let { container.looks.pending.value = it }
                    ?: Toast.makeText(this@MainActivity, look.exceptionOrNull()?.message ?: tr(R.string.that_file_isn_t_a_prism), Toast.LENGTH_SHORT).show()
            }
            return Uri.parse("prism://looks")
        }
        return linkOf(intent)
    }

    /** A .prismlook file sent to Prism or opened with it. */
    private fun lookFile(intent: Intent?): Uri? {
        val uri = when (intent?.action) {
            Intent.ACTION_SEND -> androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data?.takeIf { it.scheme == "content" || it.scheme == "file" }
            else -> null
        } ?: return null
        val type = intent?.type ?: contentResolver.getType(uri)
        val name = runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment
        return uri.takeIf { name?.endsWith("." + com.prism.music.data.looks.LookStore.FILE_EXTENSION, ignoreCase = true) == true || type == LOOK_MIME }
    }

    private fun linkOf(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_SEND -> com.prism.music.data.ShareLinks.findIn(intent.getStringExtra(Intent.EXTRA_TEXT))?.let(Uri::parse).also {
            if (it == null) Toast.makeText(this, tr(R.string.that_isn_t_a_youtube_or), Toast.LENGTH_SHORT).show()
        }
        else -> intent?.data
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

/** [name]: the listener's own name for it (1.7.6), else its own. */
private fun NavTab.tab(name: String? = null): Tab {
    val label = name ?: label
    return when (this) {
        NavTab.HOME -> Tab(Routes.HOME, label, PrismIcons.Home, PrismIcons.HomeFilled)
        NavTab.SEARCH -> Tab(Routes.SEARCH, label, PrismIcons.Search, PrismIcons.SearchBold)
        NavTab.LIBRARY -> Tab(Routes.LIBRARY, label, PrismIcons.Library, PrismIcons.LibraryFilled)
        NavTab.REPLAY -> Tab(Routes.REPLAY, label, PrismIcons.Replay, PrismIcons.ReplayFilled)
        NavTab.SETTINGS -> Tab(Routes.SETTINGS, label, PrismIcons.Settings, PrismIcons.SettingsFilled)
        NavTab.LOCAL -> Tab(Routes.LOCAL, label, PrismIcons.Phone, PrismIcons.Phone)
        NavTab.DOWNLOADS -> Tab(Routes.DOWNLOADS, label, PrismIcons.Download, PrismIcons.DownloadDone)
        NavTab.LIKED -> Tab(Routes.liked(), label, PrismIcons.Heart, PrismIcons.HeartFilled)
        NavTab.PODCASTS -> Tab(Routes.PODCASTS, label, PrismIcons.Podcasts, PrismIcons.Podcasts)
    }
}

/** Heights of the floating chrome, so pages can leave room for it. */
private fun navHeight(style: NavBarStyle, size: com.prism.music.data.prefs.NavBarSize, material: Boolean = false): Dp = when (style) {
    NavBarStyle.FLOATING -> size.floating.dp
    NavBarStyle.DOCKED -> if (material) 80.dp else (size.docked + 2).dp
    NavBarStyle.MINIMAL -> size.minimal.dp
}

/**
 * The pages' transitions with "Slide between screens" (#208): all null when it's off, and the fades apply. [rtl]
 * mirrors them, so a page always comes in from where reading goes. For moves between two tabs [sideAt] follows the tab
 * bar's order (see [pageSlideSide]): the page of the tab on the right comes in from the right.
 */
internal class PageSlides(on: Boolean, rtl: Boolean, sideAt: Int? = null) {
    private val side: Int = sideAt ?: if (rtl) -1 else 1
    // The same length as the cross-fade a page gets (PrismTokens.Motion.SCREEN): moving between screens shouldn't
    // take longer just because it slides.
    private val ms = 300
    // Back (and predictive back) hands over quicker than a page opening: no delayed fade, so the old page starts
    // giving way as soon as the gesture commits instead of sitting opaque for half the animation.
    private val popMs = 150
    val enter: EnterTransition? = if (!on) null else slideInHorizontally(tween(ms)) { it * side } + fadeIn(tween(ms / 2))
    val exit: ExitTransition? = if (!on) null else slideOutHorizontally(tween(ms)) { -it * side / 4 } + fadeOut(tween(ms))
    val popEnter: EnterTransition? = if (!on) null else slideInHorizontally(tween(popMs)) { -it * side / 4 } + fadeIn(tween(popMs))
    val popExit: ExitTransition? = if (!on) null else slideOutHorizontally(tween(popMs)) { it * side } + fadeOut(tween(popMs))
}

private fun miniHeight(style: MiniPlayerStyle): Dp = when (style) {
    MiniPlayerStyle.CARD -> 64.dp
    MiniPlayerStyle.SLIM -> 54.dp
    MiniPlayerStyle.PILL -> 56.dp
}

/**
 * Whether two destinations are both top-level tabs, whose pages hand over quicker than one opened from a list or a
 * card (a tab's own route with optional arguments, "liked?genre={genre}", still counts as that tab).
 */
internal fun bothAreTabs(fromRoute: String?, toRoute: String?, tabRoutes: Set<String>): Boolean =
    fromRoute?.substringBefore('?') in tabRoutes && toRoute?.substringBefore('?') in tabRoutes

/**
 * Which side a page comes in from: +1 the right edge, -1 the left. Moving between two tabs follows their places on the
 * bar (the tab to the right comes in from the right, whichever way reading goes); any other page keeps the old way,
 * from where reading goes in the language ([rtl]).
 */
internal fun pageSlideSide(fromRoute: String?, toRoute: String?, tabs: List<String>, rtl: Boolean): Int {
    val readingSide = if (rtl) -1 else 1
    val from = fromRoute?.substringBefore('?') ?: return readingSide
    val to = toRoute?.substringBefore('?') ?: return readingSide
    val fromIndex = tabs.indexOf(from)
    val toIndex = tabs.indexOf(to)
    if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return readingSide
    val towardRight = toIndex > fromIndex
    return if (towardRight == !rtl) 1 else -1
}

@Composable
private fun rememberArtworkColor(song: Song?, settings: com.prism.music.data.prefs.AppSettings): Color? {
    val context = LocalContext.current
    var color by remember { mutableStateOf<Color?>(null) }
    val swatch = settings.artworkSwatch
    val vivid = settings.artworkVivid
    // "New albums only" (1.7.2): songs from the same album keep the color; otherwise every cover is looked at.
    // The cover picked for the song or its album (#305) gives the colors too.
    val art = song?.let { com.prism.music.data.PlaylistCovers.artOf(it) }
    val picked = song?.let { com.prism.music.data.PlaylistCovers.shared?.picked(it) }
    val key = if (settings.artworkChange == com.prism.music.data.prefs.ArtworkChange.ALBUM) (song?.album?.id ?: song?.album?.title ?: art) to picked else art
    LaunchedEffect(key, swatch, vivid) {
        val url = art ?: return@LaunchedEffect
        // One palette for both: the accent's swatch, and the light "From the cover" (#123; also what its tile in Color shows).
        val made = withContext(Dispatchers.IO) {
            runCatching {
                val req = ImageRequest.Builder(context).data(hiRes(url, 160)).allowHardware(false).build()
                val bmp = (context.imageLoader.execute(req) as? SuccessResult)?.image?.toBitmap() ?: return@runCatching null
                val p = Palette.from(bmp).generate()
                // A tint on a speck of the picture isn't the cover's color (#236): swatches under 2% of it are left out.
                val total = p.swatches.sumOf { it.population }.coerceAtLeast(1)
                val stops = com.prism.music.ui.theme.SpectrumPresets.fromCover(
                    listOfNotNull(p.vibrantSwatch, p.lightVibrantSwatch, p.darkVibrantSwatch, p.mutedSwatch, p.lightMutedSwatch, p.dominantSwatch)
                        .filter { it.population * 50 >= total }.map { it.rgb or (0xFF shl 24) },
                )
                com.prism.music.ui.theme.pickSwatch(p, swatch, vivid)?.let { Color(it) } to stops
            }.getOrNull()
        } ?: return@LaunchedEffect
        made.first?.let { color = it }
        // The cover light fades as the accent does (#254), unless motion is reduced (Performance mode too) or "At once".
        val stops = com.prism.music.ui.theme.SpectrumPresets
        val old = stops.coverStops
        val ms = if (settings.reduceMotion) 0 else settings.artworkChange.ms
        if (ms <= 0 || old.size < 2 || settings.spectrumPreset != stops.COVER) { stops.coverStops = made.second; return@LaunchedEffect }
        val start = androidx.compose.runtime.withFrameMillis { it }
        while (true) {
            val t = ((androidx.compose.runtime.withFrameMillis { it } - start) / ms.toFloat()).coerceIn(0f, 1f)
            stops.coverStops = stops.fadeStops(old, made.second, androidx.compose.animation.core.FastOutSlowInEasing.transform(t))
            if (t >= 1f) break
            // About 25 a second, not every frame: everything drawn in the light redraws with each step.
            kotlinx.coroutines.delay(40)
        }
        stops.coverStops = made.second
    }
    return color
}

@Composable
private fun PrismRoot(deepLink: MutableStateFlow<Uri?>, launchFresh: Boolean) {
    val c = LocalContainer.current
    val settings by c.settings.flow.collectAsState()
    val song by c.player.currentSong.collectAsState()
    // Stopping clears the song at once, while the player is still on its way out: the moving copies keep the last one
    // for that last moment, as the player's own screen does.
    val lastChromeSong = remember { mutableStateOf<com.prism.music.data.model.Song?>(null) }
    LaunchedEffect(song) { if (song != null) lastChromeSong.value = song }
    val artColor = rememberArtworkColor(song, settings)
    val context = LocalContext.current
    // Settings → Playback → "Start playing shuffled": the one auto-start. Only a fresh launch, only when nothing was
    // restored (the service's attach queues our commands after its own restoreQueue), and only once the library's liked
    // songs are in from the database rather than the empty seed.
    LaunchedEffect(launchFresh) {
        if (!launchFresh || !settings.onboarded || !settings.behavior.startShuffled) return@LaunchedEffect
        kotlinx.coroutines.withTimeoutOrNull(5_000) { c.player.ready.first { it } } ?: return@LaunchedEffect
        if (c.player.currentSong.value != null) return@LaunchedEffect
        val songs = kotlinx.coroutines.withTimeoutOrNull(2_500) { c.library.likedShown.first { it.isNotEmpty() } }
            ?: c.library.likedShown.value
        if (songs.isEmpty()) return@LaunchedEffect
        c.player.playQueue(
            songs, 0, com.prism.music.playback.QueueSource(com.prism.music.playback.QueueKind.LIBRARY, tr(R.string.library)),
            shuffle = true,
        )
    }

    PrismTheme(settings, artColor) {
        // System bar icons follow Prism's theme, not the phone's (they differ when Appearance isn't "System").
        val dark = com.prism.music.ui.theme.LocalIsDark.current
        val rootView = androidx.compose.ui.platform.LocalView.current
        LaunchedEffect(dark) {
            (rootView.context as? android.app.Activity)?.window?.let { w ->
                androidx.core.view.WindowCompat.getInsetsController(w, rootView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
        // Settings → Layout & navigation → "Hide the status bar" (#115), and again each time Prism comes back to the front.
        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        androidx.compose.runtime.DisposableEffect(settings.hideStatusBar, settings.hideStatusBarPlayer, lifecycle) {
            val bars = com.prism.music.ui.components.SystemBars
            bars.apply(rootView, bars.hidden(settings))
            val watcher = androidx.lifecycle.LifecycleEventObserver { _, e ->
                if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) bars.apply(rootView, bars.hidden(c.settings.current))
            }
            lifecycle.addObserver(watcher)
            onDispose { lifecycle.removeObserver(watcher) }
        }
        // Settings → Controls & gestures → "Lock to portrait" (#190): phones stay upright; tablets keep their own layouts.
        // A full-screen video still turns sideways (it sets its own and puts this back after).
        val tablet = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp >= 600
        androidx.compose.runtime.LaunchedEffect(settings.behavior.lockPortrait, tablet) {
            (rootView.context as? android.app.Activity)?.requestedOrientation =
                if (settings.behavior.lockPortrait && !tablet) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        val navController = rememberNavController()
        // Keep the state object (not just its value) so the remembered open/close lambdas below can read it at call time
        // instead of capturing a value that would go stale between root recompositions.
        val playerOpenState = rememberSaveable { mutableStateOf(false) }
        var playerOpen by playerOpenState
        val motionScope = rememberCoroutineScope()
        // A mix card's radio lookup: which mix is being started, and which tap is the newest (a second card tapped while
        // the first loads wins; the same card tapped again is the same press).
        val mixBusy = remember { java.util.concurrent.atomic.AtomicReference<String?>(null) }
        val mixPress = remember { java.util.concurrent.atomic.AtomicInteger(0) }
        // One way in and one way out, for taps, buttons, links and actions alike: the same morph (PlayerMorph), and the
        // page the action names (the queue, the lyrics) is what the player opens on. Remembered on stable state holders so
        // the mini player and Navigator don't get a fresh lambda on every root recomposition.
        val openPlayer = remember(motionScope, c, playerOpenState) {
            {
                playerOpenState.value = true
                PlayerMorph.open(motionScope, c.settings.current.reduceMotion)
            }
        }
        val closePlayer = remember(motionScope, c, playerOpenState) {
            {
                if (playerOpenState.value || PlayerMorph.progress > 0f) {
                    playerOpenState.value = false
                    PlayerMorph.close(motionScope, c.settings.current.reduceMotion)
                }
            }
        }
        // Restored already open (a rotation, the task coming back): the player is there, not half-way in. Restored
        // closed, no number is left over from a close cut short by the rotation.
        LaunchedEffect(Unit) { PlayerMorph.snap(if (playerOpen) 1f else 0f) }
        SideEffect {
            // Snapshot writes are no-ops at equal values; guard them anyway so a root recomposition doesn't touch globals.
            if (PlayerMorph.animationsOff != settings.reduceMotion) PlayerMorph.animationsOff = settings.reduceMotion
            // Only with both the flight and its return picked is a closing morph a return.
            val back = settings.view.coverFlight && settings.view.coverFlightBack && !settings.reduceMotion
            if (com.prism.music.ui.player.CoverFlight.backOn != back) com.prism.music.ui.player.CoverFlight.backOn = back
        }
        val navigator = remember(navController) {
            Navigator(navController, openPlayer = {
                playerOpenState.value = true
                PlayerMorph.request(null)
                PlayerMorph.open(motionScope, c.settings.current.reduceMotion)
            }, playMix = { mix ->
                // A mix's songs are a radio lookup (a second or two): the card says it's starting at once, and a second
                // tap on the same card while it does doesn't start a second lookup.
                val busy = mixBusy.getAndSet(mix.id)
                if (busy != mix.id) c.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    var note: Toast? = null
                    val noteJob = launch {
                        kotlinx.coroutines.delay(150)
                        note = Toast.makeText(context, tr(R.string.loading_list, mix.title), Toast.LENGTH_LONG).also { it.show() }
                    }
                    try {
                        val press = mixPress.incrementAndGet()
                        val r = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.radio(mix.seedVideoId, mix.id, mix.params, keepFirst = true) } }.getOrNull()
                        // A newer mix tapped meanwhile wins: this one's songs aren't started over it.
                        if (press != mixPress.get()) return@launch
                        if (r == null || r.songs.isEmpty()) {
                            Toast.makeText(context, tr(R.string.couldn_t_start, mix.title), Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        // Stations like "Listen together" open on a live stream: found out now (the stream is resolved anyway), it starts as one.
                        val first = r.songs.first()
                        val live = first.isLive || kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            kotlinx.coroutines.withTimeoutOrNull(8_000) { runCatching { c.streams.isLive(first.id) }.getOrDefault(false) } ?: false
                        }
                        val songs = if (live && !first.isLive) listOf(first.copy(isLive = true)) + r.songs.drop(1) else r.songs
                        c.queue.counterparts.putAll(r.counterparts)
                        c.player.playQueue(songs, 0, com.prism.music.playback.QueueSource(com.prism.music.playback.QueueKind.RADIO, mix.title))
                    } finally {
                        noteJob.cancel()
                        note?.cancel()
                        mixBusy.compareAndSet(mix.id, null)
                    }
                }
            })
        }
        // Playback that should be watched (a music video picked from a list) brings the player up.
        LaunchedEffect(Unit) { c.player.showPlayer.collect { PlayerMorph.request(null); openPlayer() } }
        com.prism.music.ui.components.LyricsToFilesHost()
        val haze = rememberHazeState()
        val backStackState = navController.currentBackStackEntryAsState()
        // Only the two things the root actually uses are derived from the entry. Reading the whole entry with `by`
        // recomposed the root on every back-stack change, even when the visible route and chrome stayed the same.
        val route by remember(backStackState) { derivedStateOf { backStackState.value?.destination?.route } }
        val onboardingArg by remember(backStackState) { derivedStateOf { backStackState.value?.arguments?.getBoolean("onboarding") == true } }
        // The artist picker right after the welcome screen is part of setting up too: no tab bar or rail over it (its
        // Done button sat under the tab bar on phones, which it measures itself against when it's opened from Settings).
        val onboardingPicker = (route?.startsWith(Routes.PICK_ARTISTS) == true || route?.startsWith(Routes.ACCOUNT) == true) && onboardingArg
        val showChrome = route != Routes.LOGIN && route != Routes.LOGIN_ADD && route != Routes.DISCORD_LOGIN && route != Routes.WELCOME && !onboardingPicker
        // Stable identity: the tab list feeds TabBar, NavRail, swipeBetweenTabs and the NavHost transition specs. A fresh
        // list every root recomposition would restart the pointer input and rebuild all of those.
        val tabs = remember(settings.navTabs, settings.tabNames) { settings.navTabs.map { it.tab(settings.tabNames[it]) } }

        val together by c.together.state.collectAsState()
        val inSession = together is com.prism.music.playback.together.TogetherState.Hosting || together is com.prism.music.playback.together.TogetherState.Guest
        // Wide windows (tablets, foldables, a phone on its side) put the tabs in a rail down the start edge.
        val window = com.prism.music.ui.theme.LocalWindowLayout.current
        // #264 (1.8.0): on a tablet the listener picks bottom or side; phones (compact) always have the tabs at the bottom.
        val useRail = showChrome && window.rail && settings.behavior.tabBarPosition != com.prism.music.data.prefs.TabBarPosition.BOTTOM
        val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
        val cutoutStart = WindowInsets.displayCutout.union(WindowInsets.navigationBars).asPaddingValues().calculateStartPadding(layoutDirection)
        val railInset = if (useRail) cutoutStart + RAIL_MARGIN + RAIL_WIDTH + 4.dp else 0.dp
        // Room the pages leave at the bottom for the bar, the mini player and the system's own bar. The mini player's
        // room grows and shrinks with it, so the page doesn't jump by 72 dp the instant a song starts (or stops).
        val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val chromeEase = com.prism.music.ui.theme.PrismTokens.Motion.Ease
        val slotMs = if (settings.reduceMotion) 0 else com.prism.music.ui.theme.PrismTokens.Motion.STANDARD
        // Kept as states, never read here: a slot growing used to recompose this whole root — the chrome, the NavHost and
        // the page inside it — on every frame of the mini player's 300 ms slide in, which showed as the mini player and the
        // tapped row skipping frames. Each page reads the room where it uses it instead.
        val miniSlot = animateDpAsState(
            if (song != null) miniHeight(settings.miniPlayerStyle) + 8.dp else 0.dp,
            tween(slotMs, easing = chromeEase),
            label = "miniSlot",
        )
        val sessionSlot = animateDpAsState(
            if (inSession) 42.dp else 0.dp,
            tween(slotMs, easing = chromeEase),
            label = "sessionSlot",
        )
        val bottomBase = navInset +
            (if (useRail) 16.dp else navHeight(settings.navStyle, settings.navBarSize, com.prism.music.ui.theme.LocalUi.current.material) + (if (settings.navStyle == NavBarStyle.DOCKED) 0.dp else 16.dp))
        val pageBottom = remember(bottomBase, miniSlot, sessionSlot) {
            derivedStateOf { bottomBase + miniSlot.value + sessionSlot.value }
        }
        // The two ends of the morph, watched as booleans so the app isn't recomposed for every frame of it: the mini
        // player stays with us until the player has wholly taken over, and the player is laid out from the first frame
        // of the drag (its own picture follows the finger inside [NowPlayingScreen]).
        // Both change hands only at the very ends, not a hair before them: swapping the mini player out (or the player)
        // at 0.999 made the last frame of the morph wait on a fresh layout, which read as a short stall just before it
        // finished.
        val morphKeepsMini by remember { derivedStateOf { com.prism.music.ui.player.PlayerMorph.progress < 1f } }
        val morphKeepsPlayer by remember { derivedStateOf { com.prism.music.ui.player.PlayerMorph.progress > 0f } }

        // Decided once: the tab Prism opens on is the root every other tab sits on, and the welcome
        // screen is a real destination, so signing in from it navigates inside a graph that already exists.
        val root = remember { (settings.startTab.takeIf { it in settings.navTabs } ?: settings.navTabs.first()).tab().route }
        val start = remember { if (!settings.onboarded && !settings.isLoggedIn) Routes.WELCOME else root }
        val goRoot: () -> Unit = remember(navController, root) {
            {
                navController.navigate(root) {
                    popUpTo(navController.graph.id) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }

        // The tab bar shrinks while you scroll down a page and grows back as you scroll up. The state is held as an
        // object so only the two writes (scroll threshold, route change) touch it; the per-frame animation lives in
        // TabBar itself, where it can't recompose the whole app on every frame of the scroll.
        val barCompact = remember { mutableStateOf(false) }
        val barScroll = remember(barCompact) {
            object : NestedScrollConnection {
                var acc = 0f
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    val dy = available.y
                    if (dy == 0f || abs(available.x) > abs(dy)) return Offset.Zero
                    acc = if ((acc < 0) == (dy < 0)) acc + dy else dy
                    if (acc < -28f) barCompact.value = true else if (acc > 28f) barCompact.value = false
                    return Offset.Zero
                }
            }
        }
        LaunchedEffect(route) { barCompact.value = false }
        // The chrome (mini player, Together pill, the selection bar) rises into place and leaves the same way, at one
        // speed everywhere; with reduced motion it's there at once.
        val chromeMs = if (settings.reduceMotion) 0 else com.prism.music.ui.theme.PrismTokens.Motion.STANDARD
        // Remembered: a fresh transition object on every recomposition made the visibility transitions restart their
        // specs mid-animation (the mini player's slide could jerk when anything else recomposed it as it came in).
        val chromeEnter = remember(chromeMs, chromeEase) {
            slideInVertically(tween(chromeMs, easing = chromeEase)) { it } +
                fadeIn(tween(chromeMs, easing = chromeEase))
        }
        val chromeExit = remember(chromeMs, chromeEase, settings.reduceMotion) {
            slideOutVertically(tween(chromeMs, easing = chromeEase)) { it } +
                fadeOut(tween(if (settings.reduceMotion) 0 else com.prism.music.ui.theme.PrismTokens.Motion.QUICK))
        }
        // The mini player's own entrance: slide only, no fade. It is already hidden below the screen (or under the tab bar)
        // when it starts, so the fade added nothing to see; it did draw the mini player's glass into a picture of its own
        // every frame, which is most of what a fade costs on a phone's graphics chip (#354, the player's own slide dropped
        // its fade for the same reason). The joined mini player keeps its expand-and-fade, which is its tab bar's height.
        val miniEnter = remember(chromeMs, chromeEase) {
            slideInVertically(tween(chromeMs, easing = chromeEase)) { it }
        }
        val miniExit = remember(chromeMs, chromeEase) {
            slideOutVertically(tween(chromeMs, easing = chromeEase)) { it }
        }
        // A launch's first moments are the app window, the first composition and the restored queue still arriving; a
        // slide there runs at a low frame rate whatever its length. The mini player is simply there with the chrome on
        // a launch (no entrance left to skip frames), and slides in for a song started once the app is up.
        var launchSettled by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(2_000)
            launchSettled = true
        }
        val miniEnterNow = if (launchSettled) miniEnter else androidx.compose.animation.EnterTransition.None
        val joinedMiniEnter = if (launchSettled) {
            expandVertically(tween(com.prism.music.ui.theme.PrismTokens.Motion.STANDARD, easing = chromeEase), expandFrom = Alignment.Bottom) +
                fadeIn(tween(com.prism.music.ui.theme.PrismTokens.Motion.STANDARD, easing = chromeEase))
        } else androidx.compose.animation.EnterTransition.None

        LaunchedEffect(Unit) {
            c.player.errors.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
        }
        LaunchedEffect(Unit) {
            c.together.events.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
        }
        LaunchedEffect(Unit) {
            c.cast.events.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        }
        LaunchedEffect(Unit) {
            c.handoff.events.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        }
        com.prism.music.ui.components.HandoffDialogs()
        // Desk mode (#265, 1.8.0, off unless switched on): the ambient screen while charging and playing.
        com.prism.music.ui.tv.AmbientHost(onTv = false)
        LaunchedEffect(Unit) {
            deepLink.collect { uri -> if (uri != null) { handleDeepLink(uri, navigator, c); deepLink.value = null } }
        }

        val selectTab: (String) -> Unit = remember(navController, navigator, tabs, root, goRoot) { { r ->
            // The tab whose pages are showing: the first tab page above the root, else the root's own.
            val tabRoutes = tabs.map { it.route }.toSet()
            // Optional arguments aside ("liked?genre={genre}" is the Liked songs tab).
            val stack = navController.currentBackStack.value.mapNotNull { it.destination.route?.substringBefore('?') }
            val currentTab = stack.drop(1).firstOrNull { it in tabRoutes } ?: root
            when {
                // The root tab always means its top, from anywhere. Already there, it changes nothing: navigating to
                // the same page again played the "Changing screens" animation for a screen that didn't change.
                r == root -> {
                    val atRoot = navController.currentDestination?.route?.substringBefore('?') == root
                    if (!atRoot && !navController.popBackStack(root, inclusive = false, saveState = true)) goRoot()
                }
                // Tapping the tab you're in takes you back to its top.
                r == currentTab -> navController.popBackStack(r, inclusive = false)
                // Every other tab keeps its own pages: leaving Settings on Appearance comes back to Appearance.
                else -> navController.navigate(r) {
                    popUpTo(root) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
            // Search's tab tapped while Search is already showing (1.8.6): its keyboard comes up. Tapping it to open
            // Search leaves the keyboard down when "Tapping Search again" is picked.
            if (r == Routes.SEARCH && r == currentTab) navigator.requestSearchKeyboard()
        } }

        // "Customize this" (1.7.3): the element tapped and, for a Home section, which one.
        var customizing by remember { mutableStateOf<Pair<com.prism.music.ui.components.CustomizeTarget, String?>?>(null) }
        // What's under the full player holds its purely visual motion still while the player hides it.
        val playerCover = remember { com.prism.music.ui.components.PlayerCover() }
        val hiddenByPlayer = remember(playerCover) { { playerCover.hides } }
        // The playing bars stand still while the song is paused (#302); read where they're drawn.
        val musicPlaying = c.player.isPlaying.collectAsState()
        val playingNow = remember(musicPlaying) { { musicPlaying.value } }
        // What the song rows mark (liked, downloaded, video saved): collected here once, not by every row in view.
        val rowMarks = com.prism.music.ui.components.rememberRowMarks(
            c.library.likedIds, c.downloadState, c.downloadStandIns, c.videoState,
            disliked = c.library.dislikedIds,
        ) { s -> c.downloads.playsFromPhone(s) }
        // Select many (1.8.3): the songs picked in a list, one selection for the app; Back ends it.
        val selection = remember { com.prism.music.ui.components.SongSelection() }
        androidx.activity.compose.BackHandler(selection.selecting && !playerOpen) { selection.clear() }
        CompositionLocalProvider(
            com.prism.music.ui.components.LocalRowMarks provides rowMarks,
            com.prism.music.ui.components.LocalSongSelection provides selection,
            com.prism.music.ui.components.LocalPlayerCover provides playerCover,
            com.prism.music.ui.components.LocalMusicPlaying provides playingNow,
            LocalNavigator provides navigator,
            com.prism.music.ui.theme.LocalAppRunning provides true,
            LocalHazeState provides haze,
            com.prism.music.ui.theme.LocalChromeStart provides railInset,
            com.prism.music.ui.components.LocalCustomize provides { t, k -> customizing = t to k },
        ) {
            // While the full player is open it covers everything else, so TalkBack shouldn't reach the screen, tabs and
            // mini player under it (they're only drawn, not hidden, to keep the player's slide smooth).
            val covered = playerOpen && song != null
            val underPlayer = if (covered) Modifier.semantics { hideFromAccessibility() } else Modifier
            // "Hide it on the player" (Reddit, 1.7.9.1): the status bar goes as the player opens and comes back as it closes.
            LaunchedEffect(covered, settings.hideStatusBarPlayer) {
                val bars = com.prism.music.ui.components.SystemBars
                bars.playerUp = covered
                if (settings.hideStatusBarPlayer) bars.apply(rootView, bars.hidden(c.settings.current))
            }
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Box(
                    Modifier.fillMaxSize().then(underPlayer).nestedScroll(barScroll)
                        // The page is copied only when glass will sample it (#343).
                        .then(if (com.prism.music.ui.theme.glassSamplesThePage(Build.VERSION.SDK_INT, settings.liquidGlass, settings.frostedGlass)) Modifier.hazeSource(haze) else Modifier)
                        .then(if (settings.swipeTabs && !useRail) swipeBetweenTabs(tabs, route, selectTab) else Modifier),
                ) {
                  CompositionLocalProvider(com.prism.music.ui.components.LocalHiddenByPlayer provides hiddenByPlayer) {
                    // The same cross-fade both ways, and for the back gesture too: left to its default,
                    // a predictive back (swipe from the edge) shrinks the page like a card instead.
                    // "Fades between screens" off (#227): pages swap at once, so a tap can't land on the one fading out.
                    // On: a short cross-fade, eased as the rest of Prism moves (it took 700 ms, which read as a delay).
                    val fadeMs = when {
                        !settings.behavior.screenFades -> 0
                        settings.reduceMotion -> com.prism.music.ui.theme.PrismTokens.Motion.QUICK
                        else -> com.prism.music.ui.theme.PrismTokens.Motion.SCREEN
                    }
                    val fadeEase = com.prism.music.ui.theme.PrismTokens.Motion.Ease
                    val fadeSpec = tween<Float>(fadeMs, easing = fadeEase)
                    // Back (and predictive back) uses the quick fade, so the previous page resolves as the gesture
                    // commits. Opening a page keeps the longer cross-fade.
                    val popFadeMs = if (fadeMs == 0) 0 else minOf(fadeMs, com.prism.music.ui.theme.PrismTokens.Motion.QUICK)
                    val popFadeSpec = tween<Float>(popFadeMs, easing = fadeEase)
                    // Between two tabs, the bar has already said what happened: the pages hand over quicker than a page
                    // opening from a list or a card does. (A tab's page with arguments, "liked?genre=…", is still its tab.)
                    val tabRoutesList = tabs.map { it.route.substringBefore('?') }
                    val tabRoutes = tabRoutesList.toSet()
                    fun isTabSwap(from: androidx.navigation.NavBackStackEntry, to: androidx.navigation.NavBackStackEntry): Boolean =
                        bothAreTabs(from.destination.route, to.destination.route, tabRoutes)
                    val tabFadeSpec = tween<Float>(if (settings.reduceMotion) 0 else 180, easing = fadeEase)
                    // "Slide between screens" (#208, 1.7.9.2): a new page comes in from the side and Back takes it
                    // away the same way (from the left in right-to-left languages); the page underneath moves a little.
                    // Moving between tabs follows the bar's places: a page from the tab on the right comes in from the
                    // right, and one from the tab on the left from the left, so the slide matches the tap (#208).
                    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
                    val slideOn = settings.behavior.screenFades && settings.behavior.pageSlide
                    fun slidesOf(from: androidx.navigation.NavBackStackEntry, to: androidx.navigation.NavBackStackEntry) =
                        PageSlides(slideOn, rtl, pageSlideSide(from.destination.route, to.destination.route, tabRoutesList, rtl))
                    NavHost(
                        navController, startDestination = start,
                        enterTransition = {
                            slidesOf(initialState, targetState).enter ?: if (fadeMs == 0) EnterTransition.None else fadeIn(if (isTabSwap(initialState, targetState)) tabFadeSpec else fadeSpec)
                        },
                        exitTransition = {
                            slidesOf(initialState, targetState).exit ?: if (fadeMs == 0) ExitTransition.None else fadeOut(if (isTabSwap(initialState, targetState)) tabFadeSpec else fadeSpec)
                        },
                        popEnterTransition = {
                            val slides = slidesOf(initialState, targetState)
                            // A tab tapped on the bar is a move between two tabs even when Back to it pops: it still
                            // comes from its side of the bar, not from the "back" side.
                            (if (isTabSwap(initialState, targetState)) slides.enter else slides.popEnter)
                                ?: if (fadeMs == 0) EnterTransition.None else fadeIn(if (isTabSwap(initialState, targetState)) tabFadeSpec else popFadeSpec)
                        },
                        popExitTransition = {
                            val slides = slidesOf(initialState, targetState)
                            (if (isTabSwap(initialState, targetState)) slides.exit else slides.popExit)
                                ?: if (fadeMs == 0) ExitTransition.None else fadeOut(if (isTabSwap(initialState, targetState)) tabFadeSpec else popFadeSpec)
                        },
                        predictivePopEnterTransition = {
                            val slides = slidesOf(initialState, targetState)
                            (if (isTabSwap(initialState, targetState)) slides.enter else slides.popEnter)
                                ?: if (fadeMs == 0) EnterTransition.None else fadeIn(if (isTabSwap(initialState, targetState)) tabFadeSpec else popFadeSpec)
                        },
                        predictivePopExitTransition = {
                            val slides = slidesOf(initialState, targetState)
                            (if (isTabSwap(initialState, targetState)) slides.exit else slides.popExit)
                                ?: if (fadeMs == 0) ExitTransition.None else fadeOut(if (isTabSwap(initialState, targetState)) tabFadeSpec else popFadeSpec)
                        },
                    ) {
                        composable(Routes.WELCOME) {
                            WelcomeScreen(
                                onSignIn = { navController.navigate(Routes.LOGIN) },
                                // A Prism Account (on this phone), then a few artists, so the mixes and Release Radar have
                                // something to start from.
                                onAccount = { navController.navigate(Routes.ACCOUNT + "?onboarding=true") },
                                onSkip = { navController.navigate(Routes.PICK_ARTISTS + "?onboarding=true") },
                            )
                        }
                        composable(
                            Routes.ACCOUNT + "?onboarding={onboarding}",
                            listOf(navArgument("onboarding") { type = NavType.BoolType; defaultValue = false }),
                        ) {
                            val onboarding = it.arguments?.getBoolean("onboarding") == true
                            ScreenBackdrop(BackdropScreen.SETTINGS) {
                                com.prism.music.ui.screens.PrismAccountScreen(onboarding, if (onboarding) 0.dp else pageBottom.value) {
                                    if (onboarding) navController.navigate(Routes.PICK_ARTISTS + "?onboarding=true") else navController.popBackStack()
                                }
                            }
                        }
                        composable(
                            Routes.PICK_ARTISTS + "?onboarding={onboarding}",
                            listOf(navArgument("onboarding") { type = NavType.BoolType; defaultValue = false }),
                        ) {
                            val onboarding = it.arguments?.getBoolean("onboarding") == true
                            ScreenBackdrop(BackdropScreen.HOME) {
                                com.prism.music.ui.screens.PickArtistsScreen(onboarding, if (onboarding) 0.dp else pageBottom.value) {
                                    if (onboarding) { c.settings.setOnboarded(true); goRoot() } else navController.popBackStack()
                                }
                            }
                        }
                        composable(Routes.NEW_RELEASES) { ScreenBackdrop(BackdropScreen.HOME) { com.prism.music.ui.screens.NewReleasesScreen(pageBottom.value) } }
                        composable(Routes.EXCLUSIONS) { ScreenBackdrop(BackdropScreen.SETTINGS) { com.prism.music.ui.screens.ExclusionsScreen(pageBottom.value) } }
                        // 1.8.1: Bring your history, and its "Needs a look".
                        composable(Routes.BRING_HISTORY) { ScreenBackdrop(BackdropScreen.SETTINGS) { com.prism.music.ui.screens.BringHistoryScreen(pageBottom.value) } }
                        composable(Routes.IMPORT_REVIEW) { ScreenBackdrop(BackdropScreen.SETTINGS) { com.prism.music.ui.screens.ImportReviewScreen(pageBottom.value) } }
                        composable(
                            Routes.RECOGNIZE + "?start={start}",
                            listOf(navArgument("start") { type = NavType.BoolType; defaultValue = false }),
                        ) {
                            ScreenBackdrop(BackdropScreen.SEARCH) { com.prism.music.ui.screens.RecognizeScreen(it.arguments?.getBoolean("start") == true, pageBottom.value) }
                        }
                        composable(Routes.RECOGNIZED) { ScreenBackdrop(BackdropScreen.LIBRARY) { com.prism.music.ui.screens.RecognizedScreen(pageBottom.value) } }
                        composable(Routes.BLENDS) { ScreenBackdrop(BackdropScreen.LIBRARY) { com.prism.music.ui.screens.BlendsScreen(pageBottom.value) } }
                        composable(Routes.BLEND_SCAN) {
                            com.prism.music.ui.screens.BlendScanScreen { code ->
                                navController.popBackStack()
                                c.blends.pending.value = code
                            }
                        }
                        composable(Routes.LOOK_SCAN) {
                            com.prism.music.ui.screens.BlendScanScreen(tr(R.string.point_at_a_prism_look_s), com.prism.music.data.looks.LookCode::findIn) { code ->
                                navController.popBackStack()
                                c.looks.decode(code)?.let { c.looks.pending.value = it }
                                    ?: Toast.makeText(context, tr(R.string.that_look_code_couldn_t_be), Toast.LENGTH_SHORT).show()
                            }
                        }
                        composable("blend/{who}", listOf(navArgument("who") { type = NavType.StringType })) {
                            ScreenBackdrop(BackdropScreen.LIBRARY) { com.prism.music.ui.screens.BlendScreen(it.arguments?.getString("who")!!, pageBottom.value) }
                        }
                        composable("mix/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
                            Beside { CollectionScreen(CollectionType.MIX, it.arguments?.getString("id")!!, null, pageBottom.value) }
                        }
                        composable(Routes.HOME) { ScreenBackdrop(BackdropScreen.HOME) { HomeScreen(pageBottom.value) } }
                        composable(Routes.SEARCH) { ScreenBackdrop(BackdropScreen.SEARCH) { SearchScreen(pageBottom.value) } }
                        composable(Routes.LIBRARY) { ScreenBackdrop(BackdropScreen.LIBRARY) { LibraryScreen(pageBottom.value) } }
                        composable(Routes.REPLAY) { ScreenBackdrop(BackdropScreen.REPLAY) { ReplayScreen(pageBottom.value) } }
                        composable(Routes.SETTINGS) { ScreenBackdrop(BackdropScreen.SETTINGS) { SettingsScreen(pageBottom.value) } }
                        // The progress notifications' destinations: the sheets themselves, over a matching backdrop.
                        composable(Routes.DEDUP) {
                            com.prism.music.ui.screens.StoredSettings {
                                ScreenBackdrop(BackdropScreen.LIBRARY) {
                                    com.prism.music.ui.screens.DedupSheet { navController.popBackStack() }
                                }
                            }
                        }
                        composable(Routes.ENHANCE) {
                            com.prism.music.ui.screens.StoredSettings {
                                ScreenBackdrop(BackdropScreen.SETTINGS) {
                                    com.prism.music.ui.player.EnhanceSettingsSheet { navController.popBackStack() }
                                }
                            }
                        }
                        composable(
                            Routes.BACKGROUNDS + "?player={player}",
                            listOf(navArgument("player") { type = NavType.BoolType; defaultValue = false }),
                        ) {
                            val player = it.arguments?.getBoolean("player") == true
                            ScreenBackdrop(BackdropScreen.SETTINGS) { com.prism.music.ui.screens.BackgroundsScreen(pageBottom.value, forPlayer = player) }
                        }
                        composable(
                            "settings/{section}?find={find}",
                            listOf(
                                navArgument("section") { type = NavType.StringType },
                                navArgument("find") { type = NavType.StringType; nullable = true; defaultValue = null },
                            ),
                        ) {
                            ScreenBackdrop(BackdropScreen.SETTINGS) {
                                // 1.8.4: a page of the map by its key, or an old page's key (an alias), resolved by Settings itself.
                                SettingsScreen(pageBottom.value, key = it.arguments?.getString("section"), find = it.arguments?.getString("find"))
                            }
                        }
                        composable(Routes.EQ) { ScreenBackdrop(BackdropScreen.SETTINGS) { EqualizerScreen(pageBottom.value) } }
                        composable(Routes.CATALOGUE + "?find={find}", listOf(navArgument("find") { type = NavType.StringType; nullable = true; defaultValue = null })) { ScreenBackdrop(BackdropScreen.HOME) { CatalogueScreen(pageBottom.value) } }
                        composable(Routes.MOODS) { ScreenBackdrop(BackdropScreen.SEARCH) { com.prism.music.ui.screens.MoodsScreen(pageBottom.value) } }
                        composable(Routes.EXPLORE) { ScreenBackdrop(BackdropScreen.SEARCH) { com.prism.music.ui.screens.ExploreScreen(pageBottom.value) } }
                        composable(Routes.IMPORT) { ScreenBackdrop(BackdropScreen.LIBRARY) { com.prism.music.ui.screens.ImportScreen(pageBottom.value) } }
                        composable(Routes.DESIGN_GALLERY) { ScreenBackdrop(BackdropScreen.SETTINGS) { com.prism.music.ui.components.DesignGallery(pageBottom.value) } }
                        composable(Routes.TOGETHER) { ScreenBackdrop(BackdropScreen.LIBRARY) { com.prism.music.ui.screens.TogetherScreen(pageBottom.value) } }
                        composable(Routes.PODCASTS) { ScreenBackdrop(BackdropScreen.LIBRARY) { com.prism.music.ui.screens.PodcastsScreen(pageBottom.value) } }
                        composable(Routes.UPLOADS) { ScreenBackdrop(BackdropScreen.LIBRARY) { com.prism.music.ui.screens.UploadsScreen(pageBottom.value) } }
                        composable(Routes.SAVED) { ScreenBackdrop(BackdropScreen.LIBRARY) { com.prism.music.ui.screens.SavedWhilePlayingScreen(pageBottom.value) } }
                        composable("podcast/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
                            Beside { com.prism.music.ui.screens.PodcastScreen(it.arguments?.getString("id")!!, pageBottom.value) }
                        }
                        composable(Routes.DOWNLOADS) { Beside { CollectionScreen(CollectionType.DOWNLOADS, "downloads", null, pageBottom.value) } }
                        composable("folder/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
                            ScreenBackdrop(BackdropScreen.LIBRARY) { com.prism.music.ui.screens.FolderScreen(it.arguments?.getString("id")!!, pageBottom.value) }
                        }
                        composable(Routes.LOCAL) { ScreenBackdrop(BackdropScreen.LIBRARY) { com.prism.music.ui.screens.LocalMusicScreen(pageBottom.value) } }
                        composable(Routes.LOGIN) {
                            LoginScreen(
                                onDone = goRoot,
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(Routes.DISCORD_LOGIN) {
                            com.prism.music.ui.screens.DiscordLoginScreen(onBack = { navController.popBackStack() })
                        }
                        composable(Routes.LOGIN_ADD) {
                            LoginScreen(
                                onDone = goRoot,
                                onBack = { navController.popBackStack() },
                                addAccount = true,
                            )
                        }
                        composable("album/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
                            Beside { CollectionScreen(CollectionType.ALBUM, it.arguments?.getString("id")!!, null, pageBottom.value) }
                        }
                        composable("playlist/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
                            val id = it.arguments?.getString("id")!!
                            // A playlist made in Prism (kept on the phone) opens on the same page, from the phone.
                            val type = if (com.prism.music.data.PrismLibrary.isPrismList(id)) CollectionType.PRISM else CollectionType.PLAYLIST
                            // A tag found by Search (1.8.3) comes as a playlist: its own page.
                            if (id.startsWith(com.prism.music.ui.screens.TAG_PAGE)) Beside { CollectionScreen(CollectionType.TAG, id.removePrefix(com.prism.music.ui.screens.TAG_PAGE), null, pageBottom.value) }
                            else Beside { CollectionScreen(type, id, null, pageBottom.value) }
                        }
                        composable("tag/{name}", listOf(navArgument("name") { type = NavType.StringType })) {
                            Beside { CollectionScreen(CollectionType.TAG, it.arguments?.getString("name")!!, null, pageBottom.value) }
                        }
                        composable("library-search?q={q}", listOf(navArgument("q") { type = NavType.StringType; nullable = true; defaultValue = null })) {
                            Beside { com.prism.music.ui.screens.LibrarySearchScreen(it.arguments?.getString("q").orEmpty(), pageBottom.value) }
                        }
                        composable("liked?genre={genre}", listOf(navArgument("genre") { type = NavType.StringType; nullable = true; defaultValue = null })) {
                            Beside { CollectionScreen(CollectionType.LIKED, "liked", it.arguments?.getString("genre"), pageBottom.value) }
                        }
                        composable("artist/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
                            Beside { ArtistScreen(it.arguments?.getString("id")!!, pageBottom.value) }
                        }
                        composable("artist-connections/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
                            Beside { ArtistConnectionsScreen(it.arguments?.getString("id")!!, pageBottom.value) }
                        }
                        composable(
                            "browse/{id}?params={params}&title={title}",
                            listOf(
                                navArgument("id") { type = NavType.StringType },
                                navArgument("params") { type = NavType.StringType; defaultValue = "" },
                                navArgument("title") { type = NavType.StringType; defaultValue = "" },
                            ),
                        ) {
                            Beside {
                                BrowseScreen(
                                    it.arguments?.getString("id")!!,
                                    it.arguments?.getString("params")?.takeIf { p -> p.isNotBlank() },
                                    it.arguments?.getString("title") ?: "",
                                    pageBottom.value,
                                )
                            }
                        }
                    }
                  }
                }

                // Wide windows: the tabs in a rail down the start edge, the mini player along the bottom beside it.
                CompositionLocalProvider(com.prism.music.ui.components.LocalHiddenByPlayer provides hiddenByPlayer) {
                if (useRail) {
                    NavRail(
                        tabs, route, settings, selectTab,
                        Modifier.align(Alignment.CenterStart).then(underPlayer).padding(start = cutoutStart + RAIL_MARGIN),
                    )
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().then(underPlayer)
                            .padding(start = railInset).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AnimatedVisibility(inSession && !playerOpen && route != Routes.TOGETHER, enter = chromeEnter, exit = chromeExit) {
                            TogetherPill(together, Modifier.padding(bottom = 8.dp)) { navigator.go(Routes.TOGETHER) }
                        }
                        AnimatedVisibility(song != null && (!playerOpen || morphKeepsMini) && !selection.selecting, enter = miniEnterNow, exit = miniExit) {
                            MiniPlayer(
                                Modifier.widthIn(max = 720.dp).customizable(com.prism.music.ui.components.CustomizeTarget.MINI_PLAYER)
                                    .playerMorphMini(),
                            ) {
                                PlayerMorph.request(null)
                                openPlayer()
                            }
                        }
                    }
                }
                // Select many: its bar takes the place of the mini player and tabs while songs are picked. It's declared
                // after the chrome so, during the hand-over, the bar that's arriving draws over the one leaving.
                val picking = selection.selecting && showChrome
                // Floating chrome: mini player + tab bar
                AnimatedVisibility(
                    visible = showChrome && !useRail && !picking,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = chromeEnter, exit = chromeExit,
                ) {
                    val docked = settings.navStyle == NavBarStyle.DOCKED
                    val keyboard = WindowInsets.ime
                    Column(
                        Modifier.fillMaxWidth().then(underPlayer)
                            .graphicsLayer { alpha = if (com.prism.music.ui.theme.hiddenByKeyboard(keyboard.getBottom(this), size.height)) 0f else 1f }
                            .then(if (docked) Modifier else Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)),
                    ) {
                        AnimatedVisibility(inSession && !playerOpen && route != Routes.TOGETHER, enter = chromeEnter, exit = chromeExit) {
                            TogetherPill(together, Modifier.padding(bottom = 8.dp)) { navigator.go(Routes.TOGETHER) }
                        }
                        // "Joined to the tab bar" (1.7.6): one piece of glass, the mini player on top of the tabs.
                        val joined = settings.miniPlayerJoined && settings.navStyle == NavBarStyle.FLOATING
                        if (!joined) AnimatedVisibility(song != null && (!playerOpen || morphKeepsMini), enter = miniEnterNow, exit = miniExit) {
                            // Spectrum's bar (the Card style over the docked tab bar) sits right on the tab bar, edge to edge.
                            val flush = docked && settings.miniPlayerStyle == MiniPlayerStyle.CARD && !(settings.liquidGlass || settings.frostedGlass) &&
                                !com.prism.music.ui.theme.LocalUi.current.material
                            MiniPlayer(
                                (if (flush) Modifier else Modifier.padding(bottom = 8.dp).then(if (docked) Modifier.padding(horizontal = 10.dp) else Modifier))
                                    .customizable(com.prism.music.ui.components.CustomizeTarget.MINI_PLAYER)
                                    .playerMorphMini(),
                            ) {
                                PlayerMorph.request(null)
                                openPlayer()
                            }
                        }
                        val joinedMini: (@Composable () -> Unit)? = if (!joined) null else ({
                            AnimatedVisibility(
                                song != null && (!playerOpen || morphKeepsMini),
                                enter = joinedMiniEnter,
                                exit = shrinkVertically(tween(com.prism.music.ui.theme.PrismTokens.Motion.STANDARD, easing = chromeEase), shrinkTowards = Alignment.Bottom) +
                                    fadeOut(tween(com.prism.music.ui.theme.PrismTokens.Motion.QUICK)),
                            ) {
                                MiniPlayer(Modifier.customizable(com.prism.music.ui.components.CustomizeTarget.MINI_PLAYER).playerMorphMini(), bare = true) {
                                    PlayerMorph.request(null)
                                    openPlayer()
                                }
                            }
                        })
                        Box(Modifier.customizable(com.prism.music.ui.components.CustomizeTarget.TAB_BAR)) { TabBar(tabs, route, barCompact, settings, joinedMini, selectTab) }
                    }
                }
                AnimatedVisibility(
                    visible = picking,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = chromeEnter, exit = chromeExit,
                ) {
                    com.prism.music.ui.components.SelectionBar(
                        selection,
                        Modifier.then(underPlayer).padding(start = if (useRail) railInset else 0.dp)
                            .navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                }

                // #354: what the player shows for this song, ready before it's opened.
                val artAhead = song?.takeIf { !playerOpen }?.let { c.covers.art(it) }
                if (artAhead != null) {
                    val warmContext = androidx.compose.ui.platform.LocalContext.current
                    LaunchedEffect(artAhead, settings.fullBleedArtwork, settings.playerBackground, settings.playerBlur) {
                        // Let playback start settle, then have the art ready well before the player is opened: waiting
                        // 1.5 s left decode/blur work landing in the middle of a quick open (the art was cold).
                        kotlinx.coroutines.delay(200)
                        runCatching {
                            com.prism.music.ui.player.warmPlayerArt(
                                warmContext, artAhead, settings.fullBleedArtwork,
                                meshToo = settings.playerBackground != com.prism.music.data.prefs.PlayerBackground.GRADIENT,
                                blurStrength = settings.playerBlur,
                            )
                        }
                    }
                }
                // The full player, laid out from the first frame of a swipe (so the morph has somewhere to land) and kept
                // until the last frame of its close: everything on it — the queue's place, the lyrics, its scroll — lives
                // through the whole transition. Its own picture is the same number the mini player's is.
                val playerSettled by remember { derivedStateOf { playerOpen && PlayerMorph.progress >= 1f } }
                val playerLeaving by remember { derivedStateOf { !playerOpen && PlayerMorph.progress > 0f } }
                if (playerOpen || morphKeepsPlayer) {
                    androidx.compose.runtime.SideEffect {
                        if (playerCover.settled != playerSettled) playerCover.settled = playerSettled
                        if (playerCover.leaving != playerLeaving) playerCover.leaving = playerLeaving
                    }
                    androidx.compose.runtime.DisposableEffect(Unit) {
                        onDispose {
                            playerCover.settled = false
                            playerCover.leaving = false
                            // Its cover's place went with it; the next opening finds it again.
                            com.prism.music.ui.player.CoverFlight.player = null
                            com.prism.music.ui.player.PlayerMorph.playerTitle = null
                            com.prism.music.ui.player.PlayerMorph.playerArtist = null
                            com.prism.music.ui.player.PlayerMorph.playerPlay = null
                            com.prism.music.ui.player.PlayerMorph.playerPrev = null
                            com.prism.music.ui.player.PlayerMorph.playerNext = null
                        }
                    }
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            // (Prism 1.1.2): the number is a fraction by contract, but the layer takes no
                            // chances with what reaches it — one float ULP past 1 took the frame down with it
                            // (CornerBasedShape threw on a corner of -8.761883E-6 px = (1 - nextFloat(1)) · 28
                            // dp, both crashes on one phone, the same value). [PlayerMorph] clamps every write;
                            // reading it as a fraction come what may keeps this layer safe whatever feeds it.
                            val t = PlayerMorph.progress.sanely(1f)
                            if (!settings.reduceMotion) {
                                // A card growing into the screen from where the mini player was, not a second screen
                                // sliding over the first.
                                translationY = (1f - t) * 56.dp.toPx()
                                val s = 0.94f + 0.06f * t
                                scaleX = s
                                scaleY = s
                                // The clip stays on until the very end: turning it off a hair early changed the layer
                                // while the last frames were still moving.
                                clip = t < 1f
                                shape = RoundedCornerShape(((1f - t) * 28f).dp)
                                // The page behind (and the tab bar under the mini player) gives way to the player's own
                                // background all the way through, evenly: strictly linear, with no ramp or snap.
                                alpha = t
                            } else {
                                alpha = t
                            }
                        },
                    ) {
                        Box(Modifier.customizable(com.prism.music.ui.components.CustomizeTarget.PLAYER)) {
                            NowPlayingScreen { closePlayer() }
                        }
                    }
                }
                // The cover, title, artist and play control between the two players' places, on the same number.
                val shownMorph = (song ?: lastChromeSong.value)?.let { com.prism.music.data.SongVersions.shownOf(it) }
                com.prism.music.ui.player.CoverFlightLayer((song ?: lastChromeSong.value)?.let { com.prism.music.data.PlaylistCovers.artOf(it) })
                // The overlay reads LocalMusicPlaying itself; reading the play state here would invalidate the whole
                // root — and every screen in the NavHost — on every play/pause.
                if (shownMorph != null) com.prism.music.ui.player.PlayerMorphLayer(
                    shownMorph.title, shownMorph.artistText,
                )

                // Looks (1.7.3): "Undo" after each change to the look, and "Customize this" while it's on.
                // Over the player it sits at the top, under "Customize this": lower down it covered the Play button.
                // Its own scope reads the room the chrome leaves: read here, it recomposed this whole root on every frame
                // of the mini player sliding in.
                BottomBarsAboveChrome(pageBottom, overPlayer = playerOpen && song != null)
                com.prism.music.ui.components.CustomizingPill(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp))
                customizing?.let { (target, key) ->
                    // Going to the whole settings page leaves the player (it would cover the page).
                    com.prism.music.ui.screens.CustomizeSheet(target, key, onLeave = { playerOpen = false }) { customizing = null }
                }

                // The tour (1.7): the whole of it after setting up a new install or the first time a release with tours
                // runs; later releases show only what's new since the last one seen. Settings → About takes it again.
                var tour by remember { mutableStateOf<com.prism.music.ui.tour.TourPlan?>(null) }
                LaunchedEffect(showChrome) {
                    if (showChrome && tour == null) {
                        kotlinx.coroutines.delay(600) // after the first page has drawn
                        // The tour's own SharedPreferences are read off the main thread: the first read waits for the file
                        // to load (170 ms on a V2413), and it landed on the chrome's first entrance.
                        tour = withContext(Dispatchers.IO) { com.prism.music.ui.tour.pendingTour(context, c.returningUser == true) }
                    }
                }
                LaunchedEffect(Unit) { c.tourRequests.collect { playerOpen = false; tour = com.prism.music.ui.tour.fullTour(c.returningUser == true) } }
                tour?.let { plan ->
                    com.prism.music.ui.tour.TourOverlay(plan, onOpen = { r -> playerOpen = false; navigator.go(r) }, onDone = { tour = null })
                }

                // Holiday Mode (1.8.0): once a season, the first time Prism opens in its month after setting up and the
                // tour; never over the welcome screen, the tour, the player or another question.
                var seasonAsk by remember { mutableStateOf<com.prism.music.data.looks.Season?>(null) }
                val lookPending by c.looks.pending.collectAsState()
                val blendPending by c.blends.pending.collectAsState()
                val clear = showChrome && tour == null && !playerOpen && customizing == null && lookPending == null && blendPending == null
                LaunchedEffect(clear) {
                    if (!clear || seasonAsk != null) return@LaunchedEffect
                    kotlinx.coroutines.delay(1_500) // after the tour would have come up
                    if (com.prism.music.ui.tour.pendingTour(context, c.returningUser == true) != null) return@LaunchedEffect
                    val today = com.prism.music.data.looks.Seasons.today()
                    val now = c.settings.current
                    val onLook = com.prism.music.data.looks.Seasons.current(today)?.let { com.prism.music.data.looks.BuiltInLooks.of(it.lookId) }
                        ?.takeIf { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { c.looks.isOn(it) } }?.id
                    seasonAsk = com.prism.music.data.looks.SeasonPrompt.due(today, com.prism.music.data.looks.SeasonPrompt.asked(context), now.onboarded || now.isLoggedIn, onLook)
                }
                seasonAsk?.let { season ->
                    com.prism.music.ui.screens.HolidayModeDialog(season) { yes ->
                        com.prism.music.data.looks.SeasonPrompt.markAsked(context, season, com.prism.music.data.looks.Seasons.today())
                        seasonAsk = null
                        if (yes) com.prism.music.data.looks.BuiltInLooks.of(season.lookId)?.let { look -> c.scope.launch { c.looks.apply(look) } }
                    }
                }
            }
        }
    }
}

/** Above the mini player while a Listen Together session is on: who with, and a way back to it. */
@Composable
private fun TogetherPill(state: com.prism.music.playback.together.TogetherState, modifier: Modifier, onClick: () -> Unit) {
    // Leaving the session empties [state] at once; the pill keeps the last one for its slide away (see MiniPlayer).
    val shown = remember { mutableStateOf<com.prism.music.playback.together.TogetherState?>(null) }
    LaunchedEffect(state) {
        if (state is com.prism.music.playback.together.TogetherState.Hosting || state is com.prism.music.playback.together.TogetherState.Guest) shown.value = state
    }
    val shownState = (state as? com.prism.music.playback.together.TogetherState.Hosting ?: state as? com.prism.music.playback.together.TogetherState.Guest)
        ?: shown.value ?: return
    val (icon, text) = when (shownState) {
        is com.prism.music.playback.together.TogetherState.Hosting ->
            PrismIcons.Radio to tr(R.string.hosting_code, shownState.code) + when (shownState.guests.size) { 0 -> ""; 1 -> tr(R.string.t_1_listening); else -> tr(R.string.listening_3, shownState.guests.size) }
        is com.prism.music.playback.together.TogetherState.Guest ->
            PrismIcons.Groups to tr(R.string.with, shownState.hostName) + when {
                !shownState.listenHere -> tr(R.string.adding_songs)
                !shownState.inSync -> tr(R.string.out_of_step_2)
                else -> ""
            }
        else -> return
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        GlassSurface(Modifier.height(34.dp), shape = RoundedCornerShape(17.dp), elevation = 4.dp) {
            Row(
                Modifier.fillMaxHeight().clickable(onClickLabel = tr(R.string.open_listen_together), onClick = onClick).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

private val RAIL_WIDTH = 76.dp
private val RAIL_MARGIN = 12.dp

/** A page that draws its own background, kept clear of the navigation rail (the [ScreenBackdrop] pages do it themselves). */
@Composable
private fun Beside(content: @Composable () -> Unit) {
    val start = com.prism.music.ui.theme.LocalChromeStart.current
    Box(Modifier.fillMaxSize().padding(start = start)) {
        CompositionLocalProvider(com.prism.music.ui.theme.LocalChromeStart provides 0.dp) { content() }
    }
}

/**
 * The tabs on wide windows: a floating glass column down the start edge, the tab bar's twin (same tabs, icons and
 * label setting), so a tablet or a phone on its side keeps its height for the page.
 */
@Composable
private fun NavRail(tabs: List<Tab>, route: String?, settings: AppSettings, onSelect: (String) -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val glyphTint = Modifier.tabTint(settings.shapes.tabTint)
    GlassSurface(modifier.width(RAIL_WIDTH), shape = RoundedCornerShape(RAIL_WIDTH / 2)) {
        Column(
            Modifier.testTag(NAV_TABS_TAG).padding(vertical = 8.dp, horizontal = 6.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tabs.forEach { tab ->
                val selected = isSelected(tab, route)
                val material = com.prism.music.ui.theme.LocalUi.current.material
                // The bar's own tabs' single clock (see TabItem): the ink, the tint, the capsule and the glyph together.
                val change by animateFloatAsState(
                    if (selected) 1f else 0f,
                    tween(com.prism.music.ui.theme.PrismTokens.Motion.STANDARD, easing = com.prism.music.ui.theme.PrismTokens.Motion.Ease),
                    label = "railTab",
                )
                val quiet = scheme.onSurfaceVariant
                val current = if (material) scheme.onSecondaryContainer else scheme.primary
                val fg = androidx.compose.ui.graphics.lerp(quiet, current, change)
                val lifted = if (material) scheme.secondaryContainer else scheme.primary.copy(alpha = 0.16f)
                val capsule = lifted.copy(alpha = lifted.alpha * change)
                val showLabel = settings.navLabels == NavLabels.ALWAYS || settings.navLabels == NavLabels.WORDS || (settings.navLabels == NavLabels.SELECTED && selected)
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(if (material) 16.dp else 22.dp)).background(capsule)
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            onClickLabel = tab.label,
                        ) { onSelect(tab.route) }
                        .padding(vertical = if (showLabel) 8.dp else 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TabGlyph(tab, change, quiet, current, glyphTint)
                    if (showLabel) Text(
                        tab.label, color = fg, style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * The bar that sits just above the chrome ("Undo" after a look change, a schedule's notice). It reads the room the
 * chrome leaves in its own composition scope: read in [PrismRoot] itself, every frame of the mini player's slide in
 * recomposed the whole app, its nav graph and the page under it.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.BottomBarsAboveChrome(
    pageBottom: androidx.compose.runtime.State<Dp>,
    overPlayer: Boolean,
) {
    val bottom = pageBottom.value
    com.prism.music.ui.components.LookUndoBar(
        if (overPlayer) Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp).statusBarsPadding().padding(top = 56.dp)
        else Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp).padding(bottom = bottom + 8.dp),
        overPlayer = overPlayer,
    )
    if (!overPlayer) com.prism.music.ui.components.ScheduleKeptBar(
        Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp).padding(bottom = bottom + 8.dp),
    )
}

private fun isSelected(tab: Tab, route: String?): Boolean =
    route == tab.route || (tab.route == Routes.SETTINGS && route?.startsWith("settings/") == true) ||
        // A page with optional arguments ("liked?genre={genre}") is its tab's own route.
        (route != null && '?' in route && route.substringBefore('?') == tab.route)

/** The tabs' own row (bar or rail), for in-app tests: a page's chip can carry a tab's name too ("Replay", 1.8.5). */
const val NAV_TABS_TAG = "nav:tabs"

/** @param compactTarget Whether the list is scrolled down; the bar eases between the two heights here, in its own scope. */
@Composable
private fun TabBar(
    tabs: List<Tab>, route: String?, compactTarget: androidx.compose.runtime.State<Boolean>, settings: AppSettings,
    /** The mini player joined on top of a floating bar, in the same glass (1.7.6). */
    joined: (@Composable () -> Unit)? = null,
    onSelect: (String) -> Unit,
) {
    val compact by animateFloatAsState(
        if (compactTarget.value && settings.navHideOnScroll) 1f else 0f,
        spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), label = "bar",
    )
    val barSize = settings.navBarSize
    val tint = navFill(settings.navBarFill)
    val material = com.prism.music.ui.theme.LocalUi.current.material
    when (settings.navStyle) {
        NavBarStyle.FLOATING -> {
            val full = barSize.floating.dp
            val height = lerp(full, full - 14.dp, compact)
            val tabsRow: @Composable () -> Unit = {
                Row(Modifier.testTag(NAV_TABS_TAG).fillMaxWidth().height(height).padding(lerp(6.dp, 4.dp, compact)), verticalAlignment = Alignment.CenterVertically) {
                    tabs.forEach { tab -> TabItem(tab, isSelected(tab, route), settings.navLabels, compact, Modifier.weight(1f).fillMaxHeight()) { onSelect(tab.route) } }
                }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (joined != null) GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), tint = tint) {
                    Column {
                        joined()
                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(0.75.dp).background(MaterialTheme.colorScheme.hairline))
                        tabsRow()
                    }
                } else GlassSurface(Modifier.fillMaxWidth(1f - 0.30f * compact).height(height), shape = RoundedCornerShape(height / 2), tint = tint) {
                    tabsRow()
                }
            }
        }
        // Spectrum's bar (1.7): flat, a hairline on top, the split-light line over the current tab. Glass, if it's on.
        NavBarStyle.DOCKED -> {
            val scheme = MaterialTheme.colorScheme
            val row: @Composable () -> Unit = {
                Row(
                    Modifier.testTag(NAV_TABS_TAG).fillMaxWidth().navigationBarsPadding()
                        .height(if (material) lerp(80.dp, 72.dp, compact) else lerp(barSize.docked.dp, barSize.docked.dp - 12.dp, compact))
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabs.forEach { tab -> TabItem(tab, isSelected(tab, route), settings.navLabels, compact, Modifier.weight(1f).fillMaxHeight(), line = true) { onSelect(tab.route) } }
                }
            }
            if (settings.liquidGlass || settings.frostedGlass) GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(0.dp), elevation = 0.dp, tint = tint, refract = false) { row() }
            else Box(
                Modifier.fillMaxWidth().background(if (tint.isSpecified) tint else if (material) MaterialTheme.colorScheme.surfaceContainer else scheme.background)
                    .drawBehind { drawLine(scheme.hairline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx() * 0.75f) },
            ) { row() }
        }
        NavBarStyle.MINIMAL -> {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                GlassSurface(Modifier.height(barSize.minimal.dp), shape = RoundedCornerShape((barSize.minimal / 2).dp), tint = tint) {
                    Row(Modifier.testTag(NAV_TABS_TAG).fillMaxHeight().padding(5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        tabs.forEach { tab -> MinimalTab(tab, isSelected(tab, route), (barSize.minimal - 10).dp) { onSelect(tab.route) } }
                    }
                }
            }
        }
    }
}

/**
 * Swipe between tabs (1.7.6, off by default): a sideways swipe across a tab's own page that nothing on it took (a
 * carousel, a pager, a slider take theirs) goes to the tab beside it. Watched after the page has had the touch, so it
 * never takes a swipe from what's under the finger.
 */
private fun swipeBetweenTabs(tabs: List<Tab>, route: String?, onSelect: (String) -> Unit): Modifier = Modifier.pointerInput(tabs, route) {
    val here = tabs.indexOfFirst { route == it.route || route?.substringBefore('?') == it.route }
    if (here < 0) return@pointerInput
    val far = 96.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Final)
        var dx = 0f
        var dy = 0f
        var taken = false
        while (true) {
            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Final)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (event.changes.size > 1) taken = true
            if (change.isConsumed) taken = true
            dx += change.position.x - change.previousPosition.x
            dy += change.position.y - change.previousPosition.y
            if (!change.pressed) break
        }
        if (taken || abs(dx) < far || abs(dx) < abs(dy) * 2.5f) return@awaitEachGesture
        val next = here + if (dx < 0) 1 else -1
        tabs.getOrNull(next)?.let { onSelect(it.route) }
    }
}

/** The tab bar's own background (1.7.6), the mini player's too: unspecified keeps the glass as it is. */
@Composable
internal fun navFill(fill: com.prism.music.data.prefs.NavBarFill): Color {
    val scheme = MaterialTheme.colorScheme
    val dark = com.prism.music.ui.theme.LocalIsDark.current
    // Material You's navigation bar is a tonal surface, whatever Prism's Background row says.
    if (com.prism.music.ui.theme.LocalUi.current.material) return scheme.surfaceContainer
    return when (fill) {
        com.prism.music.data.prefs.NavBarFill.GLASS -> Color.Unspecified
        com.prism.music.data.prefs.NavBarFill.LIGHT -> scheme.surfaceContainerHigh.copy(alpha = if (dark) 0.28f else 0.34f)
        com.prism.music.data.prefs.NavBarFill.SOLID -> scheme.surfaceContainerHigh
    }
}

/**
 * One tab: ink when it's the current one, quiet otherwise. [line]: Spectrum's mark, the split-light line along the
 * top of the current tab, instead of a tinted capsule behind it.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TabItem(tab: Tab, selected: Boolean, labels: NavLabels, compact: Float, modifier: Modifier, line: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val material = com.prism.music.ui.theme.LocalUi.current.material
    // One speed, one ease and now one clock for every part of a tab changing: the ink, the current tab's tint, the
    // lifted glass with its fill, the line and the glyph. The tint and the filled glyph used to switch on the tap while
    // the capsule eased in behind them, so a tab arrived as colour first, then its background, then the background's
    // own colour. (Colors used a spring, which could overshoot the accent on its way in.)
    val tabEase = com.prism.music.ui.theme.PrismTokens.Motion.Ease
    val tabMs = com.prism.music.ui.theme.PrismTokens.Motion.STANDARD
    val change by animateFloatAsState(if (selected) 1f else 0f, tween(tabMs, easing = tabEase), label = "tabChange")
    val quietFg = if (material) scheme.onSurfaceVariant else scheme.ink3
    val currentFg = if (material) scheme.onSecondaryContainer else scheme.onSurface
    // Material 3 writes the current tab's name in onSurface, its icon in onSecondaryContainer.
    val labelFg = androidx.compose.ui.graphics.lerp(quietFg, if (material) scheme.onSurface else currentFg, change)
    // Aura × Spectrum: the current tab is lifted on the glass, with a short line of split light under it. Material You:
    // Material 3's secondary-container pill behind the icon, and no line.
    val lifted = if (material) scheme.secondaryContainer else com.prism.music.ui.components.frostFill(2.6f)
    // Its own colour, only its alpha coming up with the rest: fading in from Transparent (black) made the capsule a
    // grey patch first, its colour filling in after it.
    val capsule = lifted.copy(alpha = lifted.alpha * change)
    val mark = if (material) 0f else change
    val dark = com.prism.music.ui.theme.LocalIsDark.current
    // Holding the Settings tab switches "Customize this" on and off (1.7.3).
    val c = LocalContainer.current
    // A tab answers the finger before the page it opens has drawn: the icon (and name) dip a little while held.
    val press = remember { MutableInteractionSource() }
    val down by press.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (down) 0.93f else 1f, tween(90), label = "tabPress")
    val pressedModifier = modifier.graphicsLayer { scaleX = pressScale; scaleY = pressScale }
    val click = Modifier.combinedClickable(
        interactionSource = press, indication = null, onClickLabel = tab.label,
        onLongClickLabel = if (tab.route == Routes.SETTINGS) tr(R.string.customize_this) else null,
        onLongClick = if (tab.route == Routes.SETTINGS) ({ c.customizing.value = !c.customizing.value }) else null,
        onClick = onClick,
    ).semantics { this.selected = selected }
    // The current tab's icon in ink, the accent or the light (1.7.7).
    val tabTint = com.prism.music.ui.theme.LocalAppSettings.current.shapes.tabTint
    val glyphTint = Modifier.tabTint(tabTint)
    val spectrumLine = Modifier.drawWithContent {
        drawContent()
        if (mark > 0f) {
            // Docked: a longer line along the top; floating: a short one under the label.
            val w = (if (line) 28.dp else 16.dp).toPx() * mark
            val x = (size.width - w) / 2
            val y = if (line) 0f else size.height - 5.dp.toPx()
            drawRoundRect(
                com.prism.music.ui.theme.PrismTokens.spectrum(dark, x, x + w),
                Offset(x, y), androidx.compose.ui.geometry.Size(w, 2.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()),
            )
        }
    }
    when (labels) {
        NavLabels.ALWAYS -> Column(
            (if (material) pressedModifier else pressedModifier.clip(RoundedCornerShape(26.dp)).background(capsule)).then(if (material) Modifier else spectrumLine).then(click),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (material) Box(
                Modifier.size(width = 56.dp, height = 32.dp).clip(CircleShape).background(capsule),
                contentAlignment = Alignment.Center,
            ) {
                TabGlyph(tab, change, quietFg, currentFg, glyphTint)
            } else TabGlyph(tab, change, quietFg, currentFg, glyphTint)
            // #237: a long name ("Liked songs") gets smaller rather than cut off, kept clear of the rounded corners, and
            // its line keeps its full height with big text; the bar slimming down only hides it.
            if (compact < 0.98f) {
                val style = MaterialTheme.typography.labelSmall
                val lineHeight = with(androidx.compose.ui.platform.LocalDensity.current) { style.lineHeight.toDp() }.coerceAtLeast(16.dp)
                Box(Modifier.padding(top = 2.dp).height(lerp(lineHeight, 0.dp, compact)).clipToBounds().graphicsLayer { alpha = (1f - compact * 1.6f).coerceIn(0f, 1f) }) {
                    Text(
                        tab.label, color = labelFg, style = style, maxLines = 1, softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        autoSize = com.prism.music.ui.components.FitAutoSize(minFontSize = androidx.compose.ui.unit.TextUnit(8f, androidx.compose.ui.unit.TextUnitType.Sp), maxFontSize = style.fontSize),
                        modifier = Modifier.padding(horizontal = 6.dp).wrapContentHeight(unbounded = true),
                    )
                }
            }
        }
        // The picked tab's name under its icon, as "Always" places it (#291: it grew into a capsule with the name beside
        // the icon, which a long name pushed off the tab); the other tabs show their icons alone.
        NavLabels.SELECTED -> Column(
            pressedModifier.then(spectrumLine).then(click),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(52.dp, 32.dp).clip(CircleShape).background(capsule), contentAlignment = Alignment.Center) {
                // 24 dp as in the other label styles (#272: 22 here made every icon a little smaller with "Selected").
                TabGlyph(tab, change, quietFg, currentFg, glyphTint)
            }
            AnimatedVisibility(
                selected && compact < 0.5f,
                enter = expandVertically(tween(tabMs, easing = tabEase), expandFrom = Alignment.Bottom) + fadeIn(tween(tabMs, easing = tabEase)),
                exit = shrinkVertically(tween(com.prism.music.ui.theme.PrismTokens.Motion.QUICK, easing = tabEase), shrinkTowards = Alignment.Bottom) +
                    fadeOut(tween(com.prism.music.ui.theme.PrismTokens.Motion.QUICK)),
            ) {
                Text(
                    tab.label, color = labelFg, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    autoSize = com.prism.music.ui.components.FitAutoSize(minFontSize = androidx.compose.ui.unit.TextUnit(8f, androidx.compose.ui.unit.TextUnitType.Sp), maxFontSize = MaterialTheme.typography.labelSmall.fontSize),
                    modifier = Modifier.padding(top = 2.dp, start = 6.dp, end = 6.dp),
                )
            }
        }
        NavLabels.NEVER -> Box(pressedModifier.then(spectrumLine).then(click), contentAlignment = Alignment.Center) {
            Box(Modifier.size(52.dp, 40.dp).clip(CircleShape).background(capsule), contentAlignment = Alignment.Center) {
                TabGlyph(tab, change, quietFg, currentFg, glyphTint)
            }
        }
        // Words only (1.7.6): the tabs' names, no icons; the current one bolder.
        // Five or six tabs: the names get smaller rather than cut short.
        NavLabels.WORDS -> Box(pressedModifier.then(spectrumLine).then(click), contentAlignment = Alignment.Center) {
            Box(Modifier.height(40.dp).clip(CircleShape).background(capsule).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                Text(
                    tab.label, color = labelFg, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    autoSize = com.prism.music.ui.components.FitAutoSize(minFontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp), maxFontSize = MaterialTheme.typography.labelLarge.fontSize),
                )
            }
        }
    }
}

/**
 * A tab's glyph: its outline handing over to the filled twin (and the current tab's tint) on the same clock as the
 * capsule, so the tab arrives whole. One name for the tab either way, and mid-way, when both twins are drawn.
 */
@Composable
private fun TabGlyph(tab: Tab, change: Float, quiet: Color, current: Color, currentModifier: Modifier = Modifier, size: Dp = 24.dp) {
    Box(Modifier.size(size).semantics { contentDescription = tab.label }) {
        if (change < 1f) Icon(
            tab.icon, null, tint = quiet,
            modifier = Modifier.matchParentSize().graphicsLayer { alpha = 1f - change },
        )
        if (change > 0f) Icon(
            tab.selectedIcon, null, tint = current,
            modifier = Modifier.matchParentSize().then(currentModifier).graphicsLayer { alpha = change },
        )
    }
}

@Composable
private fun MinimalTab(tab: Tab, selected: Boolean, height: Dp = 44.dp, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val material = com.prism.music.ui.theme.LocalUi.current.material
    // One clock for the pill and the glyph, as the bar's own tabs (see TabItem).
    val change by animateFloatAsState(
        if (selected) 1f else 0f,
        tween(com.prism.music.ui.theme.PrismTokens.Motion.STANDARD, easing = com.prism.music.ui.theme.PrismTokens.Motion.Ease),
        label = "minTab",
    )
    val pill = if (material) scheme.secondaryContainer else scheme.onSurface
    val bg = pill.copy(alpha = pill.alpha * change)
    val press = remember { MutableInteractionSource() }
    val down by press.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (down) 0.9f else 1f, tween(90), label = "minTabPress")
    Box(
        Modifier.size(54.dp, height).graphicsLayer { scaleX = pressScale; scaleY = pressScale }.clip(CircleShape).background(bg)
            .clickable(
                interactionSource = press,
                indication = null,
                onClickLabel = tab.label, onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        TabGlyph(
            tab, change,
            quiet = scheme.onSurfaceVariant,
            current = if (material) scheme.onSecondaryContainer else scheme.surface,
            size = 23.dp,
        )
    }
}

/** Opens music.youtube.com links shared into the app. */
private fun handleDeepLink(uri: Uri, nav: Navigator, c: AppContainer) {
    val v = uri.getQueryParameter("v")
    val list = uri.getQueryParameter("list")
    val path = uri.pathSegments
    when {
        // A Prism TV's QR code, read by the phone's camera (1.8.0): pairs with that TV (and offers it the sign-in).
        uri.scheme == "prism" && uri.host == "pair" -> c.handoff.pairFromLink(uri)
        // Back from Last.fm's page in the browser, with the token it gave for this listener (#133).
        uri.scheme == "prism" && uri.host == "lastfm" -> {
            // Last.fm's own page (Devices & connections), where its sign-in started (1.8.0: no longer on Recommendations).
            nav.go(Routes.settings(com.prism.music.ui.screens.SettingsSection.LASTFM.key))
            uri.getQueryParameter("token")?.takeIf { it.isNotBlank() }?.let { token ->
                c.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    val ok = c.lastFm.finishSignIn(token)
                    android.widget.Toast.makeText(
                        c.app, if (ok) tr(R.string.connected_to_last_fm_as, c.lastFm.user.value) else tr(R.string.last_fm_didn_t_connect_try),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        // Back from Spotify's authorize page (1.8.1): the code already reached Prism on the phone's loopback address, so
        // this only brings its page back (where the connection shows).
        uri.scheme == "prism" && uri.host == "spotify" -> nav.go(Routes.settings(com.prism.music.ui.screens.SettingsSection.SPOTIFY.key))
        // A widget's cover or title: the player.
        uri.scheme == "prism" && uri.host == "player" -> nav.openPlayer()
        // Release Radar's notification.
        uri.scheme == "prism" && uri.host == "releases" -> nav.go(Routes.NEW_RELEASES)
        uri.scheme == "prism" && uri.host == "blends" -> nav.go(Routes.BLENDS)
        // Bring your history's matching notification (1.8.1).
        uri.scheme == "prism" && uri.host == "bring" -> nav.go(Routes.BRING_HISTORY)
        // A Look shared to Prism: Appearance → Looks, which shows it first.
        uri.scheme == "prism" && uri.host == "looks" -> nav.go(Routes.settings(com.prism.music.ui.screens.SettingsSection.LOOKS.key))
        // The Quick Settings tile: straight to listening.
        uri.scheme == "prism" && uri.host == "recognize" -> nav.go(Routes.RECOGNIZE + "?start=true")
        uri.scheme == "prism" && uri.host == "recognized" -> nav.go(Routes.RECOGNIZED)
        // App icon shortcuts (AppShortcuts).
        uri.scheme == "prism" && uri.host == "search" -> nav.go(Routes.SEARCH)
        uri.scheme == "prism" && uri.host == "liked" -> nav.go(Routes.liked())
        uri.scheme == "prism" && uri.host == "downloads" -> nav.go(Routes.DOWNLOADS)
        // The persistent media controls' Open Tab buttons (1.8.5).
        uri.scheme == "prism" && uri.host == "home" -> nav.go(Routes.HOME)
        uri.scheme == "prism" && uri.host == "library" -> nav.go(Routes.LIBRARY)
        uri.scheme == "prism" && uri.host == "replay" -> nav.go(Routes.REPLAY)
        // A progress notification's tap: the menu it came from. The sheets have their own destinations; an option in
        // Settings is opened at the group that holds it (the translated title arrives URI-encoded by the notifier).
        uri.scheme == "prism" && uri.host == "dedup" -> nav.go(Routes.DEDUP)
        uri.scheme == "prism" && uri.host == "enhance" -> nav.go(Routes.ENHANCE)
        uri.scheme == "prism" && uri.host == "settings" && path.isNotEmpty() ->
            nav.go(Routes.settings(path[0], uri.getQueryParameter("find")))
        uri.scheme == "prism" && uri.host == "settings" -> nav.go(Routes.SETTINGS)
        uri.scheme == "prism" && uri.host == "local" -> nav.go(Routes.LOCAL)
        uri.scheme == "prism" && uri.host == "podcasts" -> nav.go(Routes.PODCASTS)
        // The 1 December notification: that year's Rewind.
        uri.scheme == "prism" && uri.host == "rewind" -> {
            path.firstOrNull()?.toIntOrNull()?.let { c.rewindRequest.value = it }
            nav.go(Routes.REPLAY)
        }
        // The monthly recap's notification and Home's card (1.8.5): that month's story; a milestone's notice: Replay.
        uri.scheme == "prism" && uri.host == "recap" -> {
            path.firstOrNull()?.let { runCatching { java.time.YearMonth.parse(it) }.getOrNull() }?.let { c.recapRequest.value = it }
            nav.go(Routes.REPLAY)
        }
        uri.scheme == "prism" && uri.host == "replay" -> nav.go(Routes.REPLAY)
        // A handoff pairing code's notification: Prism Connect's settings, where the code shows.
        uri.scheme == "prism" && uri.host == "connect" -> nav.go(Routes.settings(com.prism.music.ui.screens.SettingsSection.CONNECT.key))
        uri.scheme == "prism" && uri.host == "mix" && path.isNotEmpty() -> nav.go(Routes.mix(path[0]))
        v != null -> {
            nav.openPlayer()
            launchSingle(c, v)
        }
        list != null -> nav.go(Routes.playlist(list))
        path.firstOrNull() == "browse" && path.size > 1 -> {
            val id = path[1]
            if (id.startsWith("MPRE")) nav.go(Routes.album(id))
            else if (id.startsWith("MPSP")) nav.go(Routes.podcast(id))
            else nav.go(Routes.browse(id, null, ""))
        }
        path.firstOrNull() == "channel" && path.size > 1 -> nav.go(Routes.artist(path[1]))
    }
}

private fun launchSingle(c: AppContainer, videoId: String) = c.scope.launch {
    val r = runCatching { c.ytm.next(videoId) }.getOrNull()
    val song = r?.songs?.firstOrNull { it.id == videoId } ?: Song(videoId, tr(R.string.loading_2))
    withContext(Dispatchers.Main) { c.player.playSingle(song) }
}
