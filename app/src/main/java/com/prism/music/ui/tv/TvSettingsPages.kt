package com.prism.music.ui.tv

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.prism.music.AppLanguage
import com.prism.music.BuildConfig
import com.prism.music.R
import com.prism.music.data.prefs.AudioQuality
import com.prism.music.playback.EqPresets
import com.prism.music.ui.screens.Crossfade
import com.prism.music.ui.screens.QueueEnd
import com.prism.music.ui.screens.SoundChoices
import com.prism.music.ui.screens.TrimSilence
import com.prism.music.ui.screens.VideoPolicy
import com.prism.music.ui.screens.settings.LS_TRANSLATE_LANGUAGES
import com.prism.music.ui.screens.settings.LS_TRANSLATE_OFF
import com.prism.music.ui.screens.settings.romanizeLabel
import com.prism.music.ui.screens.settings.romanizeOptions
import com.prism.music.ui.screens.settings.romanizeToMerged
import com.prism.music.ui.screens.settings.romanizeToStored
import com.prism.music.ui.screens.settings.translateLabel
import com.prism.music.ui.screens.settings.translateToMerged
import com.prism.music.tr
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.launch

/** One setting's action on the TV: a switch flips, a choice opens its list. */
private sealed interface TvSettingAction {
    data class Flip(val set: (Boolean) -> Unit, val now: Boolean) : TvSettingAction
    data class Choose(val options: List<Pair<String, () -> Unit>>) : TvSettingAction
    data class Run(val run: () -> Unit) : TvSettingAction
}

/** A setting with what OK does on it. */
private class TvSettingRow(val setting: TvSetting, val action: TvSettingAction)

/** Choices offered for the lyrics' size and Prism's text size (as on the phone's Text size). */
private val SIZES = listOf(0.85f, 1f, 1.15f, 1.3f)
private val LYRICS_SIZES = listOf(0.75f, 1f, 1.25f, 1.5f)
private val AMBIENT_MINUTES = listOf(1, 3, 5, 10)
/** The music video heights offered (as the phone's Sound & quality). */
private val TV_VIDEO_HEIGHTS = listOf(480, 720, 1080, 1440)
/** The sleep timer's steps on a TV (the phone types any number). */
private val TV_SLEEP_MINUTES = listOf(15, 30, 45, 60, 90, 120)
/** The background's steps, lighter to darkest ([com.prism.music.data.prefs.TvPrefs.backdropDim]). */
internal val TV_BACKDROP_DIMS = (com.prism.music.data.prefs.TvPrefs.DIM_LIGHTER..com.prism.music.data.prefs.TvPrefs.DIM_DARKEST).toList()

internal fun tvBackdropLabel(dim: Int): String = tr(
    when (dim) {
        -1 -> R.string.tv_backdrop_lighter
        1 -> R.string.tv_backdrop_darker
        2 -> R.string.tv_backdrop_much_darker
        3 -> R.string.tv_backdrop_darkest
        else -> R.string.tv_backdrop_as_it_comes
    },
)

/**
 * Settings on the TV (1.8.0, plan §2): the short TV set, on the same settings as the phone (one Prism). Groups on the
 * left, a group's settings on the right; OK flips a switch or opens a choice's list (a panel at the right); a
 * setting's explanation is behind its ⓘ, never shown as a note.
 */
@Composable
fun TvSettingsPlace(nav: TvNav) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.flow.collectAsState()
    val sing by TvSing.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { TvSing.load(context) }
    var group by rememberSaveable { mutableIntStateOf(0) }
    var choosing by remember { mutableStateOf<Pair<String, List<Pair<String, () -> Unit>>>?>(null) }
    var info by remember { mutableStateOf<TvSetting?>(null) }
    var pickingLight by remember { mutableStateOf(false) }
    var pickingLyricsStyle by remember { mutableStateOf(false) }
    val say: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    // For an answer that may come after Settings has closed.
    val app = context.applicationContext
    val sayLater: (String) -> Unit = { Toast.makeText(app, it, Toast.LENGTH_LONG).show() }
    fun percent(v: Float) = tr(R.string.tv_percent, (v * 100).toInt())
    // The sleep timer's deadline (0: none), and whether it waits for the song to end.
    val sleepAt by c.player.sleepAt.collectAsState()
    val sleepEndOfSong by c.player.sleepEndOfSong.collectAsState()
    // On the app's scope: a backup or restore goes on if Settings is left meanwhile.
    fun backUp(parts: Set<com.prism.music.data.BackupPart>) {
        sayLater(tr(R.string.backing_up))
        c.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            val r = runCatching { TvBackup.backUp(c, parts) }
            sayLater(r.exceptionOrNull()?.let { tr(R.string.backup_failed, it.message) } ?: tr(R.string.tv_backup_saved_to, r.getOrThrow()))
        }
    }
    // A restore puts the backup in place and restarts Prism; it only comes back here if it failed.
    fun restore(uri: android.net.Uri) {
        c.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            // A password can't be typed well with a remote: a protected backup is restored on a phone.
            if (runCatching { c.backup.isEncrypted(uri) }.getOrDefault(false)) { sayLater(tr(R.string.tv_backup_has_password)); return@launch }
            sayLater(tr(R.string.restoring))
            runCatching { c.backup.restore(uri) }.onFailure { sayLater(tr(R.string.couldn_t_restore, it.message)) }
        }
    }
    val pickBackup = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { restore(it) }
    }

    val groups: List<Pair<TvSettingGroup, List<TvSettingRow>>> = listOf(
        TvSettingGroup(tr(R.string.account), PrismIcons.Person, emptyList()) to buildList {
            if (settings.isLoggedIn) add(TvSettingRow(TvSetting(tr(R.string.tv_signed_in_as, settings.accountName.ifBlank { settings.accountEmail }), settings.accountEmail.takeIf { it.isNotBlank() }), TvSettingAction.Run {}))
            // Not signed in: OK signs in with the phone (it isn't a row that does nothing).
            else add(TvSettingRow(TvSetting(tr(R.string.tv_not_signed_in), tr(R.string.tv_sign_in_with_phone)), TvSettingAction.Run { nav.go(TvRoute.Pair(signIn = true)) }))
            add(TvSettingRow(TvSetting(tr(R.string.tv_sign_in_with_phone)), TvSettingAction.Run { nav.go(TvRoute.Pair(signIn = true)) }))
            add(TvSettingRow(TvSetting(tr(R.string.tv_phone_as_remote)), TvSettingAction.Run { nav.go(TvRoute.Pair(signIn = false)) }))
            if (settings.isLoggedIn) add(TvSettingRow(TvSetting(tr(R.string.sign_out)), TvSettingAction.Choose(listOf(tr(R.string.sign_out) to { tvSignOut(c) }))))
        },
        TvSettingGroup(tr(R.string.language), PrismIcons.Translate, emptyList()) to listOf(
            TvSettingRow(
                TvSetting(tr(R.string.language), AppLanguage.picked(context).takeIf { it.isNotBlank() }?.let(AppLanguage::nativeName) ?: tr(R.string.tv_language_tv)),
                TvSettingAction.Choose(listOf(tr(R.string.tv_language_tv) to { AppLanguage.pick(context, AppLanguage.PHONE) }) + AppLanguage.available.map { tag -> AppLanguage.nativeName(tag) to { AppLanguage.pick(context, tag) } }),
            ),
        ),
        TvSettingGroup(tr(R.string.sound), PrismIcons.Equalizer, emptyList()) to listOf(
            TvSettingRow(
                TvSetting(tr(R.string.tv_sound_quality), settings.audioQuality.label),
                TvSettingAction.Choose(AudioQuality.entries.map { q -> q.label to { c.settings.setAudioQuality(q) } }),
            ),
            TvSettingRow(TvSetting(tr(R.string.tv_same_loudness), on = settings.normalize, info = tr(R.string.tv_same_loudness_info)), TvSettingAction.Flip({ c.settings.setNormalize(it) }, settings.normalize)),
            // The phone's merged choices (SoundChoices), so the two read and write the same stored settings.
            TvSettingRow(
                TvSetting(tr(R.string.crossfade), SoundChoices.crossfade(settings).label, info = tr(R.string.crossfade_choice_info)),
                TvSettingAction.Choose(Crossfade.entries.map { x -> x.label to { SoundChoices.setCrossfade(c.settings, x) } }),
            ),
            TvSettingRow(
                TvSetting(tr(R.string.skip_silence), SoundChoices.trimSilence(settings).label, info = tr(R.string.skip_silence_choice_info)),
                TvSettingAction.Choose(TrimSilence.entries.map { t -> t.label to { SoundChoices.setTrimSilence(c.settings, t) } }),
            ),
            // The equalizer's presets (the bands themselves are the phone's screen): a pick turns it on, as there.
            TvSettingRow(
                TvSetting(tr(R.string.equalizer), if (settings.eq.enabled) tr(R.string.on, EqPresets.label(settings.eq.preset)) else tr(R.string.off)),
                TvSettingAction.Choose(
                    listOf(tr(R.string.off) to { c.settings.setEq(settings.eq.copy(enabled = false)) }) +
                        EqPresets.presets.map { (name, bands) -> EqPresets.label(name) to { c.settings.setEq(settings.eq.copy(enabled = true, preset = name, bands = bands)) } },
                ),
            ),
            TvSettingRow(
                TvSetting(tr(R.string.music_video_quality), "${settings.videoMaxHeight}p"),
                TvSettingAction.Choose(TV_VIDEO_HEIGHTS.map { h -> "${h}p" to { c.settings.setVideoMaxHeight(h) } }),
            ),
        ),
        TvSettingGroup(tr(R.string.lyrics), PrismIcons.Lyrics, emptyList()) to listOf(
            TvSettingRow(
                TvSetting(tr(R.string.tv_lyrics_size), percent(settings.tv.lyricsScale)),
                TvSettingAction.Choose(LYRICS_SIZES.map { v -> percent(v) to { c.settings.setTvLyricsScale(v) } }),
            ),
            // The phone's own lyrics style, with a live example of each (TvLyricsStyleChoice).
            TvSettingRow(TvSetting(tr(R.string.lyrics_style), settings.lyricsLook.style.label), TvSettingAction.Run { pickingLyricsStyle = true }),
            // How dark the background is behind the player and lyrics (1.8.1, Reddit); as it comes unless picked.
            TvSettingRow(
                TvSetting(tr(R.string.tv_backdrop), tvBackdropLabel(settings.tv.backdropDim), info = tr(R.string.tv_backdrop_info)),
                TvSettingAction.Choose(TV_BACKDROP_DIMS.map { d -> tvBackdropLabel(d) to { c.settings.setTvBackdropDim(d) } }),
            ),
            TvSettingRow(
                TvSetting(tr(R.string.lyrics_source), settings.lyricsSources.firstOrNull()?.label),
                TvSettingAction.Choose(settings.lyricsSources.map { s -> s.label to { c.settings.setLyricsOrder(listOf(s) + settings.lyricsOrder.filter { it != s }) } }),
            ),
            // The TV draws translations, romanized lines and furigana already: these are the phone's own switches.
            TvSettingRow(
                TvSetting(tr(R.string.translate_into), translateLabel(translateToMerged(settings.lyricsTranslate, settings.lyricsTranslateTo)), info = tr(R.string.for_songs_in_another_language_machine)),
                TvSettingAction.Choose(
                    (listOf(LS_TRANSLATE_OFF) + LS_TRANSLATE_LANGUAGES.map { it.first }).map { code ->
                        translateLabel(code) to {
                            if (code == LS_TRANSLATE_OFF) c.settings.setLyricsTranslate(false)
                            else { c.settings.setLyricsTranslateTo(code); c.settings.setLyricsTranslate(true) }
                        }
                    },
                ),
            ),
            TvSettingRow(
                TvSetting(tr(R.string.romanize_lyrics), romanizeLabel(romanizeToMerged(settings.romanize, settings.romanizeAll))),
                TvSettingAction.Choose(romanizeOptions(settings.romanizeAll).map { p -> romanizeLabel(p) to { romanizeToStored(p).let { (mode, all) -> c.settings.setRomanize(mode); c.settings.setRomanizeAll(all) } } }),
            ),
            TvSettingRow(
                TvSetting(tr(R.string.show_furigana), on = settings.lyricsFurigana, info = tr(R.string.hiragana_readings_above_kanji)),
                TvSettingAction.Flip({ c.settings.setLyricsFurigana(it) }, settings.lyricsFurigana),
            ),
        ),
        TvSettingGroup(tr(R.string.sing), PrismIcons.Mic, emptyList()) to listOf(
            TvSettingRow(
                TvSetting(tr(R.string.tv_sing_on_tv), sing.label(), info = tr(R.string.tv_sing_info)),
                // On the app's scope: leaving Settings doesn't cut the check short (it switches Sing back off itself).
                TvSettingAction.Run { c.scope.launch(kotlinx.coroutines.Dispatchers.Main) { sayLater(TvSing.check(c)) } },
            ),
        ),
        TvSettingGroup(tr(R.string.tv_ambient_screen), PrismIcons.Moon, emptyList()) to listOf(
            TvSettingRow(
                TvSetting(tr(R.string.tv_ambient_screen), if (settings.tv.ambientMinutes == 0) tr(R.string.never) else tr(R.string.tv_ambient_after, settings.tv.ambientMinutes), info = tr(R.string.tv_ambient_info)),
                TvSettingAction.Choose(AMBIENT_MINUTES.map { m -> tr(R.string.tv_ambient_after, m) to { c.settings.setTvAmbientMinutes(m) } } + (tr(R.string.never) to { c.settings.setTvAmbientMinutes(0) })),
            ),
        ),
        // Home's rows: shown or hidden, in what order (also from the end of Home itself); the editor is TvHomeEditor.
        TvSettingGroup(tr(R.string.home), PrismIcons.Home, emptyList()) to listOf(
            TvSettingRow(
                TvSetting(tr(R.string.customize_home), TvHomeLayout.hiddenCount(settings.tv.homeLayout, settings.homeLayout).takeIf { it > 0 }?.let { tr(R.string.tv_home_hidden_count, it) }),
                TvSettingAction.Run { nav.go(TvRoute.HomeEditor) },
            ),
        ),
        TvSettingGroup(tr(R.string.text_size), PrismIcons.Visible, emptyList()) to listOf(
            TvSettingRow(TvSetting(tr(R.string.text_size), percent(settings.textScale)), TvSettingAction.Choose(SIZES.map { v -> percent(v) to { c.settings.setTextScale(v) } })),
        ),
        // The highlights' light: Spectrum or White light (monochrome), the phone's own Color setting.
        TvSettingGroup(tr(R.string.colors), PrismIcons.Palette, emptyList()) to listOf(
            TvSettingRow(TvSetting(tr(R.string.colors), settings.lightStyle.label), TvSettingAction.Run { pickingLight = true }),
        ),
        // Added after the groups that were there (before About), so each earlier group keeps its place in the list.
        TvSettingGroup(tr(R.string.playback), PrismIcons.Play, emptyList()) to listOf(
            TvSettingRow(
                TvSetting(tr(R.string.when_the_queue_ends), SoundChoices.queueEnd(settings).label, info = tr(R.string.when_the_queue_ends_info)),
                TvSettingAction.Choose(QueueEnd.entries.map { q -> q.label to { SoundChoices.setQueueEnd(c.settings, q) } }),
            ),
            TvSettingRow(
                TvSetting(tr(R.string.music_videos_label), SoundChoices.videoPolicy(settings).label, info = tr(R.string.music_videos_label_info)),
                TvSettingAction.Choose(VideoPolicy.entries.map { v -> v.label to { SoundChoices.setVideoPolicy(c.settings, v) } }),
            ),
            // The phone's sleep timer, in the steps a remote picks easily (its fade and volume are the phone's choices).
            TvSettingRow(
                TvSetting(
                    tr(R.string.sleep_timer),
                    when {
                        sleepAt <= 0L -> tr(R.string.off)
                        sleepEndOfSong && sleepAt <= System.currentTimeMillis() -> tr(R.string.stops_when_this_song_ends)
                        else -> tr(R.string.stops_in_min, ((sleepAt - System.currentTimeMillis()).coerceAtLeast(0) / 60_000 + 1).toString())
                    },
                ),
                TvSettingAction.Choose(
                    listOf(tr(R.string.off) to { c.player.cancelSleepTimer() }) +
                        TV_SLEEP_MINUTES.map { m -> tr(R.string.min, m.toString()) to { c.player.setSleepTimer(m) } } +
                        (tr(R.string.end_of_song) to { c.player.setSleepEndOfSong(true) }),
                ),
            ),
        ),
        TvSettingGroup(tr(R.string.downloads), PrismIcons.Download, emptyList()) to listOf(
            TvSettingRow(TvSetting(tr(R.string.download_on_wi_fi_only), on = settings.downloadWifiOnly), TvSettingAction.Flip({ c.settings.setDownloadWifiOnly(it) }, settings.downloadWifiOnly)),
            TvSettingRow(
                TvSetting(tr(R.string.download_songs_when_you_play_them), on = settings.downloadOnStream, info = tr(R.string.download_songs_on_stream_detail)),
                TvSettingAction.Flip({ c.settings.setDownloadOnStream(it) }, settings.downloadOnStream),
            ),
            TvSettingRow(
                TvSetting(tr(R.string.download_songs_ahead), settings.downloadQueueAhead.coerceIn(0, 5).let { if (it == 0) tr(R.string.off) else it.toString() }, info = tr(R.string.download_songs_ahead_info)),
                TvSettingAction.Choose((0..5).map { n -> (if (n == 0) tr(R.string.off) else n.toString()) to { c.settings.setDownloadQueueAhead(n) } }),
            ),
        ),
        // Backups (TvBackup): to the TV's Downloads folder, and back from it (or a file picked where the TV has a picker).
        TvSettingGroup(tr(R.string.backup), PrismIcons.History, emptyList()) to listOf(
            TvSettingRow(
                TvSetting(tr(R.string.back_up_prism), info = tr(R.string.tv_backup_info)),
                TvSettingAction.Choose(
                    listOf(
                        tr(R.string.tv_backup_plain) to { backUp(TvBackup.PLAIN) },
                        tr(R.string.tv_backup_with_downloads) to { backUp(TvBackup.WITH_DOWNLOADS) },
                    ),
                ),
            ),
            TvSettingRow(
                TvSetting(tr(R.string.restore_from_a_backup)),
                TvSettingAction.Run {
                    scope.launch {
                        val found = TvBackup.found(c)
                        choosing = tr(R.string.restore_from_a_backup) to (
                            found.map { f -> f.name to { restore(f.uri) } } +
                                (tr(R.string.tv_backup_choose_file) to { runCatching { pickBackup.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }.onFailure { say(tr(R.string.tv_backup_no_picker)) } })
                            )
                    }
                },
            ),
        ),
        TvSettingGroup(tr(R.string.about_3), PrismIcons.Info, emptyList()) to listOfNotNull(
            // Force TV mode (a phone showing these screens as a TV): the way back to the phone's screens. A real TV has
            // none, so the row is only there when the mode is on (or was restored from a backup).
            if (com.prism.music.ui.tv.TvDevice.isRealTv(context) && !settings.tv.forceTv) null else TvSettingRow(
                TvSetting(tr(R.string.force_tv_mode), on = settings.tv.forceTv, info = tr(R.string.force_tv_mode_info)),
                TvSettingAction.Run {
                    // Set before the phone's screen starts: it asks at once, and the setting is written after.
                    com.prism.music.ui.tv.TvDevice.forced = false
                    c.settings.setForceTv(false)
                    // A real TV stays here: the phone's screen would send itself straight back to these. A phone forced
                    // as a TV goes back to its own screens.
                    if (!com.prism.music.ui.tv.TvDevice.isRealTv(context)) {
                        context.startActivity(
                            android.content.Intent(context, com.prism.music.PrismActivity::class.java)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        context.activityOrNull()?.finish()
                    }
                },
            ),
            TvSettingRow(TvSetting(tr(R.string.tv_version, BuildConfig.VERSION_NAME)), TvSettingAction.Run {}),
            TvSettingRow(TvSetting(tr(R.string.check_for_updates)), TvSettingAction.Run { scope.launch { say(runCatching { c.updates.checkNow()?.let { tr(R.string.tv_update_ready, it) } ?: tr(R.string.you_re_up_to_date) }.getOrElse { it.message ?: tr(R.string.you_re_up_to_date) }) } }),
        ),
    )
    val rows = groups.getOrNull(group)?.second.orEmpty()
    Box(Modifier.fillMaxSize()) {
        TvSettingsScreen(
            groups.map { (g, r) -> g.copy(settings = r.map { it.setting }) },
            group, { group = it },
            onSetting = { i ->
                when (val a = rows.getOrNull(i)?.action) {
                    is TvSettingAction.Flip -> a.set(!a.now)
                    is TvSettingAction.Choose -> choosing = rows[i].setting.title to a.options
                    is TvSettingAction.Run -> a.run()
                    null -> Unit
                }
            },
            onInfo = { i -> info = rows.getOrNull(i)?.setting },
        )
        choosing?.let { (title, options) ->
            BackHandler { choosing = null }
            TvPanelBox(LocalTvScreen.current + "/choice") {
                TvActionPanel(title, options.map { (label, pick) -> TvAction(label, PrismIcons.Check) { pick(); choosing = null } })
            }
        }
        if (pickingLight) {
            BackHandler { pickingLight = false }
            TvPanelBox(LocalTvScreen.current + "/light") { TvLightChoice(settings.lightStyle) { c.settings.setLightStyle(it); pickingLight = false } }
        }
        if (pickingLyricsStyle) {
            BackHandler { pickingLyricsStyle = false }
            // As the phone's "Start from": the style with its own parts (motion, glow, weight).
            TvPanelBox(LocalTvScreen.current + "/lyricsstyle") {
                TvLyricsStyleChoice(settings.lyricsLook.style) { c.settings.setLyricsStartFrom(it); pickingLyricsStyle = false }
            }
        }
        info?.let { s ->
            BackHandler { info = null }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                TvPanelBox(LocalTvScreen.current + "/info") {
                    TvInfoCard(s.title, s.info.orEmpty(), Modifier.align(Alignment.Center).tvItem("$TAG_SETTINGS/infoclose", entry = true).clickable { info = null })
                }
            }
        }
    }
}

/** A panel's own focus memory (a screen key of its own). */
@Composable
private fun TvPanelBox(screen: String, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val memory = LocalTvFocusMemory.current
    remember(screen) { memory.opening(screen); true }
    // Focus stays in the panel (Settings' rows behind it can't be reached and pressed unseen).
    androidx.compose.runtime.CompositionLocalProvider(LocalTvScreen provides screen) { Box(Modifier.fillMaxSize().tvHoldsFocus(), content = content) }
}

