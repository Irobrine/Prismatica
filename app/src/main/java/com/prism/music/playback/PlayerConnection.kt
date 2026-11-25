package com.prism.music.playback

import com.prism.music.R
import com.prism.music.tr
import android.content.ComponentName
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.prism.music.AppContainer
import com.prism.music.data.Backup
import com.prism.music.data.currentOutput
import com.prism.music.data.db.VideoSongEntity
import com.prism.music.data.outputOptions
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class QueueEntry(val index: Int, val song: Song, val uid: String)

/** Repeat's next state, the player's and the lock screen's (1.8.3, D47) alike: off → all → one → off. */
fun nextRepeatMode(mode: Int): Int = when (mode) {
    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
    else -> Player.REPEAT_MODE_OFF
}

/** Shuffle as the listener has it: off, on, or Smart Shuffle (on, with recommendations mixed in). */
enum class ShuffleMode { OFF, SHUFFLE, SMART }

/**
 * Shuffle's button (the player's, the TV's, the browser remote's): off → shuffle → Smart Shuffle (where the queue
 * allows it) → off. With Settings → Playback → Shuffle button set to "Shuffle only" (1.8.4, D57) it's off → on → off,
 * and someone still in Smart Shuffle goes to off with the next tap.
 */
fun nextShuffle(now: ShuffleMode, button: com.prism.music.data.prefs.ShuffleButton, smartAllowed: Boolean): ShuffleMode = when (now) {
    ShuffleMode.OFF -> ShuffleMode.SHUFFLE
    ShuffleMode.SHUFFLE -> if (button == com.prism.music.data.prefs.ShuffleButton.SHUFFLE_AND_SMART && smartAllowed) ShuffleMode.SMART else ShuffleMode.OFF
    ShuffleMode.SMART -> ShuffleMode.OFF
}

/** What a new queue is, for [carryShuffle]. */
enum class StartKind {
    /** A list a song was tapped in, or played from the top: a playlist, an album, the library, Replay, a shelf. */
    LIST,
    /** One song (Search, a card): Autoplay follows it. */
    SINGLE,
    RADIO,
    /** "Play as a DJ set": DJ mode decides the order. */
    DJ_SET,
    /** Started as its music video. */
    VIDEO,
    /** A podcast episode or a live stream. */
    SPOKEN,
}

/** [StartKind] of the queue [songs] starting at [start]; [asVideo]: the first one plays as its video; [dj]: as a DJ set. */
fun startKind(source: QueueSource, songs: List<Song>, start: Int, asVideo: Boolean, dj: Boolean): StartKind {
    val first = songs.getOrNull(start)
    return when {
        dj -> StartKind.DJ_SET
        source.kind == QueueKind.RADIO -> StartKind.RADIO
        asVideo -> StartKind.VIDEO
        first?.isEpisode == true || first?.isLive == true -> StartKind.SPOKEN
        source.kind == QueueKind.SINGLE || songs.size < 2 -> StartKind.SINGLE
        else -> StartKind.LIST
    }
}

/**
 * The shuffle a new queue starts with. As always: on when its own Shuffle was pressed ([asked]), else off. With
 * Settings → Playback → "Keep shuffle on for the next list" ([keep]; 1.8.4, #416) a [StartKind.LIST] keeps what the
 * last queue had ([was]): the song it starts on plays first and the rest is shuffled. Smart Shuffle stays Smart Shuffle
 * where the new queue allows it ([smartAllowed]) and the button still offers it ([button]), plain shuffle otherwise.
 * Radios, single songs, DJ sets, videos and episodes are left alone.
 */
fun carryShuffle(
    was: ShuffleMode, keep: Boolean, kind: StartKind, asked: Boolean,
    button: com.prism.music.data.prefs.ShuffleButton, smartAllowed: Boolean,
): ShuffleMode = when {
    !keep || kind != StartKind.LIST -> if (asked) ShuffleMode.SHUFFLE else ShuffleMode.OFF
    !asked && was == ShuffleMode.OFF -> ShuffleMode.OFF
    was == ShuffleMode.SMART && button == com.prism.music.data.prefs.ShuffleButton.SHUFFLE_AND_SMART && smartAllowed -> ShuffleMode.SMART
    else -> ShuffleMode.SHUFFLE
}

/**
 * Added to the playhead to get the lyrics' time: the listener's nudge for this song, plus Settings → Lyrics → timing
 * for every song (1.8.4, #404; both positive = lyrics earlier), minus how far a music video runs behind its song.
 */
fun lyricShiftOf(songOffsetMs: Long, settingMs: Int, videoLagMs: Long): Long = songOffsetMs + settingMs - videoLagMs

/** A long list's later pages join the queue this many songs at a time (#356). */
private const val FILL_BATCH = 400
/** With this little of the song left, the song after it stays where it is while a list fills in (#356). */
private const val SOON_MS = 30_000L
/**
 * A list longer than this starts on a window round the song picked, rather than building the whole queue on the main
 * thread first (a Downloads page can hold thousands; one tap waited a second on all of them). The rest joins behind it.
 * A shuffled start uses the same window (1.1.1): building 800 songs' media items before the first sound was the wait on
 * Shuffle, not the shuffle itself.
 */
private const val FAST_START_AT = 400
/** A few songs after the one picked start with it (enough for Play next); the rest joins once it's under way. */
private const val FAST_START_AFTER = 4
/** How often the library's music videos are got ready by themselves ([PlayerConnection]'s once-a-day run). */
private const val VIDEO_PREPARE_EVERY_MS = 24L * 60 * 60 * 1000

/** Outputs that take over when they connect (not microphones, call channels or the phone's own parts). */
private val NEW_OUTPUTS = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG,
    AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE, AudioDeviceInfo.TYPE_DOCK,
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY,
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HEARING_AID,
    AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
)

sealed interface VideoSyncState {
    data object Off : VideoSyncState
    data object Measuring : VideoSyncState
    /** Video time minus song time. */
    data class Synced(val lagMs: Long) : VideoSyncState
    data object Unmatched : VideoSyncState
}

/**
 * UI-facing handle on playback. The service runs in-process, so once bound we
 * drive its ExoPlayer directly and mirror its state into flows for Compose.
 */
class PlayerConnection(private val context: Context, private val c: AppContainer) : Player.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var service: PlaybackService? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val pending = mutableListOf<(ExoPlayer) -> Unit>()

    val player: ExoPlayer? get() = service?.player ?: testPlayer

    /** Tests only: a player to drive when the playback service isn't running (it has no notification, autoplay or effects). */
    @androidx.annotation.VisibleForTesting
    internal var testPlayer: ExoPlayer? = null

    private val _current = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _current
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying
    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering
    private val _queue = MutableStateFlow<List<QueueEntry>>(emptyList())
    val queue: StateFlow<List<QueueEntry>> = _queue
    private val _index = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _index
    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle
    /** Queue indices in the order they'll actually play after the current one (follows shuffle). */
    private val _upNext = MutableStateFlow<List<Int>>(emptyList())
    val upNext: StateFlow<List<Int>> = _upNext
    private val _played = MutableStateFlow<List<Int>>(emptyList())
    /** Queue indices already played (before the current song in play order), oldest first. */
    val played: StateFlow<List<Int>> = _played
    private val _repeat = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeat
    private val _videoMode = MutableStateFlow(false)
    val videoMode: StateFlow<Boolean> = _videoMode
    private val _videoAvailable = MutableStateFlow(false)
    val videoAvailable: StateFlow<Boolean> = _videoAvailable
    private val _hasNext = MutableStateFlow(false)
    val hasNext: StateFlow<Boolean> = _hasNext
    private val _hasPrevious = MutableStateFlow(false)
    val hasPrevious: StateFlow<Boolean> = _hasPrevious
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors = _errors.asSharedFlow()
    /** The sleep timer's deadline as wall-clock time (0 when none runs), for the countdown the UI shows. */
    val sleepAt: StateFlow<Long> get() = sleepTimer.wallDeadline
    private val _output = MutableStateFlow<AudioDeviceInfo?>(null)
    /** The output picked in Prism's device sheet; null follows the system (e.g. newly connected headphones). */
    val preferredOutput: StateFlow<AudioDeviceInfo?> = _output

    /** Picks the music up when the Bluetooth output a becoming-noisy pause lost comes back (Settings → Playback). */
    private val outputReturn = OutputReturn()

    init {
        context.getSystemService(AudioManager::class.java)?.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            private var primed = false
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                if (primed) AudioDiagnostics.log("device", "added: ${addedDevices.joinToString { AudioDiagnostics.describe(it) }}")
                // The callback reports existing devices once on registration; after that, a new output (headphones
                // plugged in, earbuds connected) takes over like it would elsewhere, which following the system does by
                // itself. So only an output the listener picked is let go, and only for a real new output: earbuds also
                // add their microphone and call channels, and AirPods add and drop entries with ear detection and the
                // case. Each of those used to re-point the player at "follow the system" (when it already was), and on
                // Bluetooth every such re-route is a moment's gap.
                if (primed && _output.value != null && addedDevices.any { it.isSink && it.type in NEW_OUTPUTS }) {
                    setOutput(null, "a new output was connected")
                }
                if (!primed || addedDevices.any { it.isSink }) rememberOutput()
                // The same pair connecting again after a pause the lost output caused: the music carries on by itself
                // (off, Settings → Playback → "Resume when Bluetooth comes back").
                if (primed) {
                    if (!c.settings.current.behavior.resumeOnReturn) outputReturn.played()
                    else if (canPlayReturnedOutput() &&
                        outputReturn.returned(addedDevices.filter { it.isSink }.map { it.toSink() }, android.os.SystemClock.elapsedRealtime())
                    ) {
                        resumeOnReturnedOutput()
                    }
                }
                primed = true
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                AudioDiagnostics.log("device", "removed: ${removedDevices.joinToString { AudioDiagnostics.describe(it) }}")
                outputReturn.removed(removedDevices.filter { it.isSink }.map { it.toSink() }, android.os.SystemClock.elapsedRealtime())
                // The pause came before the device list: the output it lost may already be back (a quick mode switch).
                if (outputReturn.waitingFor) checkOutputBack()
                if (removedDevices.any { it.id == _output.value?.id }) setOutput(null, "the picked output went away")
            }
        }, android.os.Handler(android.os.Looper.getMainLooper()))
    }

    // ------------------------------------------------------------ Lyric timing

    private val _videoSync = MutableStateFlow<VideoSyncState>(VideoSyncState.Off)
    /** How the lyrics are lined up with the current music video. */
    val videoSync: StateFlow<VideoSyncState> = _videoSync
    private val _lyricOffset = MutableStateFlow(0L)
    /** The listener's own nudge for this song (and mode), in ms; positive shows lyrics earlier. */
    val lyricOffset: StateFlow<Long> = _lyricOffset
    private val _lyricShift = MutableStateFlow(0L)
    /** Added to the playhead to get the lyrics' time ([lyricShiftOf]: this song's offset, Settings' for all, less any video lag). */
    val lyricShift: StateFlow<Long> = _lyricShift
    /** Settings → Lyrics → timing (#404), as last read: for every song, on top of [lyricOffset]. */
    private var lyricsSettingMs = 0

    init {
        // A change applies at once, while the song plays: every reader of the lyrics' time goes through [lyricShift].
        scope.launch {
            c.settings.flow.map { it.behavior.lyricsOffsetMs }.distinctUntilChanged().collect { lyricsSettingMs = it; updateShift() }
        }
    }
    private var syncKey: String? = null
    private var syncJob: kotlinx.coroutines.Job? = null

    /** The playhead in the song's (lyrics') timeline. */
    val lyricPosition: Long get() = position + _lyricShift.value

    fun seekToLyric(ms: Long) = seekTo((ms - _lyricShift.value).coerceAtLeast(0))

    private fun offsetKey(): String? {
        val song = _current.value ?: return null
        return song.id + if (player?.currentMediaItem?.videoModeId != null) ":video" else ""
    }

    fun setLyricOffset(ms: Long) {
        val key = offsetKey() ?: return
        c.lyricOffsets.edit().apply { if (ms == 0L) remove(key) else putLong(key, ms) }.apply()
        _lyricOffset.value = ms
        updateShift()
    }

    private fun updateLyricTiming() {
        val song = _current.value
        val vid = player?.currentMediaItem?.videoModeId
        _lyricOffset.value = offsetKey()?.let { c.lyricOffsets.getLong(it, 0L) } ?: 0L
        val key = if (song != null && vid != null && vid != song.id) "${song.id}|$vid" else null
        if (key != syncKey) {
            syncKey = key
            syncJob?.cancel()
            if (key == null || song == null || vid == null) _videoSync.value = VideoSyncState.Off
            else {
                val known = c.videoSync.cached(song.id, vid)
                if (known != null) _videoSync.value = VideoSyncState.Synced(known)
                else {
                    _videoSync.value = VideoSyncState.Measuring
                    syncJob = scope.launch {
                        val lag = c.videoSync.lagMs(song.id, vid)
                        if (syncKey == key) {
                            _videoSync.value = lag?.let { VideoSyncState.Synced(it) } ?: VideoSyncState.Unmatched
                            updateShift()
                        }
                    }
                }
            }
        }
        updateShift()
    }

    /** Measures the current music video against the song again. */
    fun resyncVideo() {
        val song = _current.value ?: return
        val vid = player?.currentMediaItem?.videoModeId ?: return
        c.videoSync.forget(song.id, vid)
        syncKey = null
        updateLyricTiming()
    }

    private fun updateShift() {
        val lag = (_videoSync.value as? VideoSyncState.Synced)?.lagMs ?: 0L
        _lyricShift.value = lyricShiftOf(_lyricOffset.value, lyricsSettingMs, lag)
    }

    private val _episodeSpeed = MutableStateFlow(1f)
    /** Podcast episodes' playback speed (music always plays at 1×). */
    val episodeSpeed: StateFlow<Float> = _episodeSpeed

    fun setEpisodeSpeed(speed: Float) {
        _episodeSpeed.value = speed
        service?.episodeSpeed = speed
    }

    private val _musicSpeed = MutableStateFlow(1f)
    /**
     * Songs' playback speed (1.7.4, the Speed button under the player; 1.7.8's Speed & pitch sheet): for this session,
     * starting from Settings' default each time Prism starts.
     */
    val musicSpeed: StateFlow<Float> = _musicSpeed

    fun setMusicSpeed(speed: Float) {
        speedTouched = true
        _musicSpeed.value = speed.coerceIn(0.5f, 2f)
        service?.musicSpeed = _musicSpeed.value
    }

    private val _musicPitch = MutableStateFlow(0)
    /** Songs' pitch in semitones (1.7.8, R12), apart from Sing's key; for this session, as [musicSpeed]. */
    val musicPitch: StateFlow<Int> = _musicPitch

    fun setMusicPitch(semitones: Int) {
        speedTouched = true
        _musicPitch.value = semitones.coerceIn(-12, 12)
        service?.musicPitch = _musicPitch.value
    }

    /** The sheet was used before Settings' defaults were read: those don't overwrite the listener's pick. */
    private var speedTouched = false

    init {
        scope.launch {
            val s = c.settings.flow.first().sound
            if (!speedTouched) {
                _musicSpeed.value = s.musicSpeed; _musicPitch.value = s.musicPitch
                service?.let { it.musicSpeed = s.musicSpeed; it.musicPitch = s.musicPitch }
            }
        }
    }

    fun setOutput(device: AudioDeviceInfo?, reason: String = "picked in the device sheet") {
        // Picking one of the phone's own outputs while casting brings the music back here, playing on.
        if (device != null && c.cast.casting) c.cast.disconnect(keepPlaying = true)
        // Already there: nothing to re-route (a re-route is a gap on Bluetooth).
        if (device?.id == _output.value?.id) return
        AudioDiagnostics.log("route", "player pointed at ${AudioDiagnostics.describe(device)} ($reason; was ${AudioDiagnostics.describe(_output.value)})")
        _output.value = device
        player?.setPreferredAudioDevice(device)
        if (device != null) {
            // A read started before the pick must not land after it.
            outputRead++
            outputReturn.current(device.toSink())
            // A pick of its own ends any wait for the output that went: the listener chose where the sound goes.
            outputReturn.played()
        }
    }

    /** The last output read off the main thread, newest wins (several reads can be in flight at once). */
    @Volatile private var outputRead = 0

    /**
     * Notes the output playing now, for [outputReturn] (a becoming-noisy pause asks which output it was). Android is
     * asked off the main thread: these are audio-service calls, and earbuds add and drop entries constantly.
     */
    private fun rememberOutput() {
        if (player == null) return
        val read = ++outputRead
        val preferred = _output.value
        scope.launch(Dispatchers.IO) {
            val sink = runCatching { currentOutput(context, preferred) }.getOrNull()?.toSink()
            withContext(Dispatchers.Main) { if (read == outputRead) outputReturn.current(sink) }
        }
    }

    /** Whether the music may start again by itself now: a queue is ready, nothing else is playing it, no call. */
    private fun canPlayReturnedOutput(): Boolean {
        if (!c.settings.current.behavior.resumeOnReturn) return false
        val p = player ?: return false
        if (c.cast.casting) return false
        if (p.mediaItemCount == 0) return false
        if (p.playbackState != Player.STATE_READY && p.playbackState != Player.STATE_BUFFERING) return false
        // Not while a call is coming, ringing or being talked on: the focus request would either be refused or play under
        // the call, both a surprise. The wait stays as it is (and runs out), so nothing starts later by itself.
        val mode = context.getSystemService(AudioManager::class.java)?.mode
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE) return false
        if (sleepAt.value > 0 && sleepAt.value <= System.currentTimeMillis()) return false
        return true
    }

    /**
     * A becoming-noisy pause whose output is already back when the pause lands (the device was added before the
     * broadcast reached us) has no add left to act on: look at the route once it has settled, and pick the music up.
     */
    private fun checkOutputBack() {
        if (!outputReturn.waitingFor) return
        scope.launch(Dispatchers.IO) {
            delay(400)
            val sinks = runCatching { outputOptions(context) }.getOrNull().orEmpty().map { it.toSink() }
            withContext(Dispatchers.Main) {
                if (canPlayReturnedOutput() && outputReturn.returned(sinks, android.os.SystemClock.elapsedRealtime())) {
                    resumeOnReturnedOutput()
                }
            }
        }
    }

    /**
     * A Bluetooth output came back after a becoming-noisy pause took the music: play on where it stopped. The service's
     * own gentle play fades it in when "Fade on pause and play" is on.
     */
    private fun resumeOnReturnedOutput() {
        if (!canPlayReturnedOutput()) return
        val p = player ?: return
        AudioDiagnostics.log("route", "the output came back: playing again")
        if (service?.gentle(true) != true) p.play()
    }

    fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackServices.target(context)))
        controllerFuture = MediaController.Builder(context, token).buildAsync()
    }

    internal fun attach(s: PlaybackService) {
        service = s
        s.episodeSpeed = _episodeSpeed.value
        s.musicSpeed = _musicSpeed.value
        s.musicPitch = _musicPitch.value
        s.player.addListener(this)
        refresh(rebuildQueue = true)
        rememberOutput()
        pending.toList().forEach { it(s.player) }
        pending.clear()
    }

    internal fun detach() {
        // A recreated service (or a stopped one) has no playback for a timer to belong to: end it. Always put the volume
        // back, even between a timer's natural end and its silent tail, so a recreated service never comes back silent.
        outputReturn.played()
        cancelSleepTimer()
        service?.player?.removeListener(this)
        service = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }

    /**
     * Starts the playback service (not just binds to it) so it keeps running as
     * a media service — with its notification and system media controls — after
     * the app leaves the screen.
     */
    private fun ensureStarted() {
        runCatching { context.startService(android.content.Intent(context, PlaybackServices.target(context))) }
    }

    private fun withPlayer(block: (ExoPlayer) -> Unit) {
        val p = player
        if (p != null) block(p) else {
            pending += block
            connect()
        }
    }

    fun reportError(msg: String) {
        _errors.tryEmit(msg)
    }

    // ------------------------------------------------------------ Commands

    private val _showPlayer = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Asks the UI to bring the full player up. */
    val showPlayer = _showPlayer.asSharedFlow()

    /** Songs found for music videos / live performances this session (video id -> song). */
    private val videoSongs = java.util.concurrent.ConcurrentHashMap<String, Song>()
    private var playToken = 0

    /**
     * [rest]: for a long list started from its first page (#356), gives the next page's songs each time it's asked, null
     * when there are no more. They join the queue behind the songs playing ([fillQueue]).
     */
    fun playQueue(songs: List<Song>, startIndex: Int = 0, source: QueueSource, shuffle: Boolean = false, rest: (suspend () -> List<Song>?)? = null) {
        if (songs.isEmpty()) return
        // "No repeats this session" (#159): a radio starts without what's been heard, on the song it starts on.
        if (source.kind == QueueKind.RADIO && c.settings.current.behavior.autoplayNoRepeats) {
            val (kept, at) = c.sessionHeard.radioStart(songs, startIndex)
            if (kept.size < songs.size) { startFiltered(kept, at, source, shuffle, rest); return }
        }
        startFiltered(songs, startIndex, source, shuffle, rest)
    }

    private fun startFiltered(songs: List<Song>, startIndex: Int, source: QueueSource, shuffle: Boolean, rest: (suspend () -> List<Song>?)?) {
        // #216: with the explicit filter on, explicit songs don't go in (a tapped one gives way to the next that may play).
        val mode = c.settings.current.behavior.explicitContent
        if (mode != com.prism.music.data.prefs.ExplicitContent.SHOW && songs.any { it.explicit }) {
            val (kept, at) = com.prism.music.data.ExplicitFilter.queue(mode, songs, startIndex) ?: run { reportError(tr(R.string.explicit_none_left)); return }
            if (!shuffle && songs.getOrNull(startIndex)?.explicit == true) reportError(tr(R.string.explicit_skipped))
            startFiltered(kept, at, source, shuffle, rest)
            return
        }
        source.page?.let { c.recentCollections.played(it) }
        val token = ++playToken
        val first = if (shuffle) songs.indices.random() else startIndex.coerceIn(0, songs.lastIndex)
        val tapped = songs[first]
        val everywhere = songsEverywhere()
        // #383: a playlist's videos, as YouTube pairs them with their songs (one request), for when each comes up.
        val pairsFrom = playlistToPair(everywhere, source, c.settings.current.isLoggedIn)
        // Live streams play as they are: they have no "song version" to swap in. A shuffled start does too: it's picked
        // to be played at once, so it never waits on a video's song (with "Songs, not videos" everywhere as well, the
        // random pick landing on a music video was the wait on Shuffle sometimes).
        if (shuffle || !tapped.isVideo || tapped.isLive || tapped.isEpisode) {
            if (pairsFrom != null) scope.launch { pairPlaylist(pairsFrom, tapped.id, source.title) }
            startQueue(songs, first, source, shuffle, null, rest = rest)
            return
        }
        // With the Song / Video switch off the player is audio only (#48): a music video plays as its song, or as
        // the video's sound when it has no song, never with its picture (there'd be no switch to turn it off).
        // "Songs, not videos" everywhere (#80) plays it as its song too, and so does a shuffled start.
        // On mobile data with "No videos on mobile data" (#281) too, unless the picture's kept on the phone.
        val audioOnly = !c.settings.current.showVideoSwitch || everywhere || shuffle || (VideoOnData.off(c) && !c.downloads.videoSaved(tapped.id))
        // A music video or live performance opens as the song it's of, already showing the video (the Song button then
        // plays the track itself). With no song to match, the video plays as it is. The match is kept ([videoSongs]), so
        // the next play of it starts at once.
        if (!audioOnly) _showPlayer.tryEmit(Unit)
        // Known to have no separate song: it plays as the video it is, at once (no search waited on).
        if (tapped.id in noSongFor) {
            if (pairsFrom != null) scope.launch { pairPlaylist(pairsFrom, tapped.id, source.title) }
            startQueue(songs, first, source, shuffle, tapped.id.takeUnless { audioOnly }, rest = rest)
            return
        }
        scope.launch {
            // The matches kept from before: a video known to be of no separate song starts as itself at once, and one
            // whose song is known skips the search (the wait is only on the first tap after a start).
            if (!matchesLoaded.isCompleted) matchesLoaded.await()
            if (tapped.id in noSongFor) {
                if (pairsFrom != null) pairPlaylist(pairsFrom, tapped.id, source.title)
                if (token == playToken) startQueue(songs, first, source, shuffle, tapped.id.takeUnless { audioOnly }, rest = rest)
                return@launch
            }
            // The playlist's own pairing first (a playlist from Home: Prism's title search missed half of #383's).
            val started = android.os.SystemClock.elapsedRealtime()
            if (pairsFrom != null) pairPlaylist(pairsFrom, tapped.id, source.title)
            val song = songFor(tapped, (4_000 - (android.os.SystemClock.elapsedRealtime() - started)).coerceAtLeast(500))
            if (token != playToken) return@launch
            val list = if (song == null) songs else songs.toMutableList().also { it[first] = song }
            startQueue(list, first, source, shuffle, tapped.id.takeUnless { audioOnly }, rest = rest)
            if (song == null) scope.launch {
                // It wasn't matched in time, so this one plays as the video. Look again once it's under way, only to
                // remember the song: the next play of it then starts at once instead of waiting on the same search.
                kotlinx.coroutines.withTimeoutOrNull(2_000) { isPlaying.first { it } }
                if (token == playToken) songFor(tapped, 8_000)?.let { c.warmUp(it.id, cellular = c.settings.current.preloadCellular) }
            }
        }
    }

    /**
     * Holds a place for a queue still being looked up (a long press's Play or Shuffle, #356). [stillStarting] says no
     * once something else was played or the queue stopped meanwhile: that wins, and the lookup's songs aren't played.
     * The queue playing carries on as it was until there's a new one ([fillQueue] included): the lookup may come to nothing.
     */
    fun queueStarting(): Int = ++playToken
    fun stillStarting(token: Int) = token == playToken

    /** The job adding a long list's later pages to the queue (#356), or a shuffled queue's rest (1.1.1). */
    private var fill: kotlinx.coroutines.Job? = null

    /** What the last queue start built before playback could begin (tests read it; see [startQueue]). */
    internal data class QueueStart(val songs: Int, val builtBeforePlay: Int, val shuffled: Boolean, val ms: Long)

    @androidx.annotation.VisibleForTesting
    internal var lastQueueStart: QueueStart? = null

    private fun stopFill() {
        fill?.cancel()
        fill = null
        c.queue.filling = false
    }

    /**
     * Adds what [rest] gives to the queue behind the playing song: the rest of a long list, started from its first page
     * so nothing waits on the whole of it (#356). Songs the queue started with ([have]) aren't added twice and the
     * explicit filter applies as it did to them. Stops by itself when the queue is replaced, whoever replaces it: what's
     * started here, a car, a watch or a spoken request. Added a batch at a time, as each one makes the player rebuild
     * its timeline and the UI re-read the whole queue.
     */
    private fun fillQueue(rest: suspend () -> List<Song>?, source: QueueSource, have: List<Song>) {
        stopFill()
        c.queue.filling = true
        val mode = c.settings.current.behavior.explicitContent
        val seen = have.mapTo(HashSet()) { it.id }
        var job: kotlinx.coroutines.Job? = null
        // Not by [playToken], which moves as soon as anything is asked for, even what then fails to load: Prism's own
        // new queues end this with [stopFill], and what replaces the queue from outside changes its source.
        fun wanted() = job?.isActive == true && c.queue.source.value == source
        job = scope.launch {
            try {
                val waiting = ArrayList<Song>()
                while (true) {
                    val page = try {
                        withContext(Dispatchers.IO) { rest() }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // What came so far stays; the queue just ends sooner than the list.
                        AudioDiagnostics.log("queue", "the rest of \"${source.title}\" didn't load: ${e.message}")
                        null
                    } ?: break
                    if (!wanted()) return@launch
                    page.filterTo(waiting) {
                        com.prism.music.data.ExplicitFilter.plays(mode, it) && !c.snoozes.isSnoozed(it) && seen.add(it.id)
                    }
                    if (waiting.size >= FILL_BATCH) { addFilled(waiting, ::wanted); waiting.clear() }
                }
                if (wanted() && waiting.isNotEmpty()) addFilled(waiting, ::wanted)
            } finally {
                if (fill === job) { fill = null; c.queue.filling = false }
            }
            // The list is all in: only now can autoplay (and Smart Shuffle) tell the queue is nearly over.
            if (wanted()) service?.afterQueueFilled()
        }
        fill = job
    }

    /** [songs] at the end of the queue (before autoplay's suggestions), and shuffled in among what's still to come. */
    private suspend fun addFilled(songs: List<Song>, still: () -> Boolean = { true }) {
        val items = withContext(Dispatchers.Default) { songs.map { it.toMediaItem() } }
        if (!still()) return
        val p = player ?: return
        val auto = c.queue.autoplayStart.value
        val at = if (auto in 0..p.mediaItemCount) auto else p.mediaItemCount
        p.addMediaItems(at, items)
        if (auto >= 0) c.queue.autoplayStart.value = auto + items.size
        if (!p.shuffleModeEnabled) return
        val order = p.shuffleOrder as? HistoryShuffleOrder ?: return
        val playing = p.currentMediaItemIndex
        val ahead = playOrder(p).let { o -> o.drop(o.indexOf(playing) + 1) }
        // What the listener lined up with Play next stays next; so does a next song that's about to start (its stream
        // is ready, and a crossfade may be under way).
        val queued = ahead.takeWhile { p.getMediaItemAt(it).mediaId in c.queue.userQueued.value }.size
        val soon = p.duration > 0 && p.duration - p.currentPosition < SOON_MS
        p.setShuffleOrder(order.scattered((at until at + items.size).toList(), playing, keep = queued + if (soon) 1 else 0))
    }

    /**
     * A shuffled start's rest (1.1.1): the songs either side of the window that started ([startQueue]'s fast shuffle),
     * made into media items off the main thread and shuffled in among what's still to come. The first song plays while
     * this runs, so a library's 800 songs aren't built before its first sound.
     */
    private fun fillShuffled(all: List<Song>, from: Int, to: Int, token: Int) {
        val rest = ArrayList<Song>(all.size - (to - from))
        if (from > 0) rest.addAll(all.subList(0, from))
        if (to < all.size) rest.addAll(all.subList(to, all.size))
        if (rest.isEmpty()) {
            c.queue.filling = false
            return
        }
        c.queue.filling = true
        var job: kotlinx.coroutines.Job? = null
        job = scope.launch {
            try {
                // Built beside the first song, never before it: the call that started it has already prepared and
                // played, and this runs on the main thread only to put each batch in.
                for (chunk in rest.chunked(FILL_BATCH)) {
                    if (token != playToken) return@launch
                    addFilled(chunk) { token == playToken }
                }
            } finally {
                if (fill === job) { fill = null; c.queue.filling = false }
            }
            if (token == playToken) service?.afterQueueFilled()
        }
        fill = job
    }

    /** Settings → Playback → "Songs, not videos" → Everywhere (#80). */
    fun songsEverywhere(): Boolean = c.settings.current.let { it.radioSongsOnly && it.songsEverywhere }

    /**
     * #383 (1.8.3): the playlist's watch queue, asked once, with YouTube's own song for each of its music videos: kept
     * with the songs found this session, so each video plays as its song when it comes up (signed out YouTube pairs
     * nothing there, and the title search alone missed many: Telugu film songs whose videos are a label's uploads).
     */
    private suspend fun pairPlaylist(playlistId: String, startId: String, title: String) {
        val pairs = kotlinx.coroutines.withTimeoutOrNull(2_500) {
            runCatching { withContext(Dispatchers.IO) { c.ytm.next(startId, playlistId).videoSongs() } }.getOrNull()
        } ?: return
        // Kept like a search's answer, so a playlist's videos aren't paired again after a restart.
        pairs.forEach { (videoId, song) -> if (videoSongs.putIfAbsent(videoId, song) == null) rememberVideoSong(videoId, song) }
        AudioDiagnostics.log("queue", "\"$title\": YouTube pairs ${pairs.size} of its videos with their songs")
    }

    companion object {
        /** The playlist whose pairing is asked for ([pairPlaylist]): Everywhere, signed in, a playlist queue. */
        internal fun playlistToPair(everywhere: Boolean, source: QueueSource, signedIn: Boolean): String? =
            source.originId?.removePrefix("VL")?.takeIf { everywhere && signedIn && source.kind == QueueKind.PLAYLIST && it.isNotBlank() }
    }

    /** Lookups running right now, by video id, so a tap and a screen's prefetch don't search twice. */
    private val matchingSongs = java.util.concurrent.ConcurrentHashMap<String, Deferred<Song?>>()

    /**
     * Videos with no separate song of their own (the lookup answered, and answered nothing): they play as the video
     * they are, and a tap doesn't wait on the same search again. Not kept when the lookup itself failed (no network).
     */
    private val noSongFor = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Whether [videoId] is known to have no separate song (a video that plays as itself). */
    fun hasNoSongVersion(videoId: String): Boolean = videoId in noSongFor

    /** When the library's videos were last got ready by the once-a-day run ([prepareLibraryVideosByItself]). */
    private val videoPreparePrefs = context.getSharedPreferences("video_prepare", Context.MODE_PRIVATE)

    /**
     * Videos the table already answers for, with the song they're of ([knownSongs]) or that they're of none
     * ([noSongFor]): the ids alone, read once at start, so a tap after a restart doesn't search what's already known.
     * The songs themselves aren't decoded here: a library's worth of them at every start was tens of MB of memory the
     * phone needed for the player (and for a backup), so [songFor] reads the one row it's asked about.
     */
    private val knownSongs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Which videos the table answers for, and the id of each song's own video, read at start; the songs' text is read
     * as they're played. Kept as a [Deferred] so [songFor] can wait for it rather than searching what's already known.
     */
    private val matchesLoaded: Deferred<Unit> = scope.async(Dispatchers.IO) {
        // A restore's matches (they travel with the library, apart from the database): put in first, read back below.
        Backup.readPendingVideoSongs(context.filesDir)?.let { rows -> runCatching { c.db.videoSongs().putAll(rows) } }
        runCatching { c.db.videoSongs().ids() }.getOrNull().orEmpty().forEach { row ->
            // A song's own video is what its "Video" button offers: the pair itself is two ids, so it's kept for all.
            row.songId?.let { songId -> c.queue.counterparts.putIfAbsent(songId, row.videoId) }
            if (row.songId == null) noSongFor.add(row.videoId) else knownSongs.add(row.videoId)
        }
    }

    init {
        // Once a day by itself, after the matches are in so it reuses them; it waits for the library to be read.
        scope.launch(Dispatchers.IO) {
            matchesLoaded.await()
            prepareLibraryVideosByItself()
        }
    }

    /**
     * Remembers (and keeps for next time) what [videoId] was found to be of: [song], or null for a video that's of no
     * separate song. Suspends for the database write: call it off the main thread.
     */
    private suspend fun rememberVideoSong(videoId: String, song: Song?) {
        knownSongs.remove(videoId)
        if (song == null) noSongFor.add(videoId) else {
            noSongFor.remove(videoId)
            videoSongs[videoId] = song
            c.queue.counterparts[song.id] = videoId
        }
        runCatching {
            c.db.videoSongs().put(
                VideoSongEntity(
                    videoId = videoId,
                    songId = song?.id,
                    songJson = song?.let { InnerTube.json.encodeToString(Song.serializer(), it) },
                    checkedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /** Preparing every video in the library (Settings → Downloads & storage). */
    var preparingVideos by androidx.compose.runtime.mutableStateOf(false)
        private set
    var videosPrepared by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var videosToPrepare by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    private var prepareVideosJob: Job? = null

    /**
     * Looks up the song of every music video in [videos] and resolves its stream address (and the video's own for one
     * with no song), ahead of time. It doesn't download any sound: the tap then only has to open an address it knows.
     * Two at a time, and never beside a song that's loading; the listener can leave the page and it carries on.
     */
    fun prepareAllVideos(videos: List<Song>) {
        if (preparingVideos) return
        val targets = videos.filter { it.isVideo && !it.isLive && !it.isEpisode && !it.isLocal && !it.isRss }.distinctBy { it.id }
        if (targets.isEmpty()) return
        prepareVideosJob = scope.launch {
            preparingVideos = true
            videosPrepared = 0
            videosToPrepare = targets.size
            val limited = Semaphore(2)
            try {
                kotlinx.coroutines.coroutineScope {
                    targets.forEach { video ->
                        launch(Dispatchers.IO) {
                            limited.withPermit {
                                val song = songFor(video, 20_000)
                                val id = song?.id ?: video.id
                                // Downloaded, a file on the phone, or a whole cached song: the player reads it from the
                                // phone, so there's no address to get and nothing of it to expire.
                                if (!c.availableOffline(id)) runCatching { c.yieldToPlaying(id); c.streams.resolve(id) }
                                withContext(Dispatchers.Main) { videosPrepared++ }
                            }
                        }
                    }
                }
            } finally {
                preparingVideos = false
            }
        }
    }

    /**
     * Gets the library's music videos ready by itself once a day ([prepareAllVideos]): the matches are kept anyway, this
     * re-resolves their stream addresses, which YouTube only honours for about an hour. On mobile data only with
     * Settings → Load ahead on mobile data, as that's the part that spends data. Waits for the library to be read.
     */
    private suspend fun prepareLibraryVideosByItself() {
        val videos = c.library.liked.first { it.isNotEmpty() }
            .filter { it.isVideo && !it.isLive && !it.isEpisode && !it.isLocal && !it.isRss }
        if (videos.isEmpty() || preparingVideos) return
        val metered = context.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered != false
        if (metered && !c.settings.current.preloadCellular) return
        if (System.currentTimeMillis() - videoPreparePrefs.getLong("preparedAt", 0L) < VIDEO_PREPARE_EVERY_MS) return
        videoPreparePrefs.edit().putLong("preparedAt", System.currentTimeMillis()).apply()
        withContext(Dispatchers.Main) { prepareAllVideos(videos) }
    }

    /**
     * Gets a music video's song ready while its row is on screen (Settings → Load ahead's mobile-data choice applies),
     * so a tap starts as the song at once rather than waiting on the search. Not beside a song that's loading.
     */
    fun prefetchSongFor(video: Song) {
        if (!video.isVideo || video.isLive || video.isEpisode || video.isLocal) return
        if (videoSongs.containsKey(video.id) || matchingSongs.containsKey(video.id)) return
        scope.launch(Dispatchers.IO) {
            // The match alone doesn't make the sound ready: its stream is resolved too (and its start saved on Wi-Fi, or
            // on mobile data when Load ahead allows it). [warmUp] keeps off the song that's loading, and on mobile data
            // stops after the address is known, so this costs no audio data there. A video with no song of its own gets
            // its own sound ready, so it can play at once too.
            val song = songFor(video, 8_000)
            val id = song?.id ?: video.id
            // Downloaded (or a whole cached song): the player reads it from the phone, so there's nothing to get ready.
            if (c.availableOffline(id)) return@launch
            c.warmUp(id, cellular = c.settings.current.preloadCellular)
        }
    }

    /**
     * "Prepare this video" (a video's menu): finds the song it's of and gets its sound ready now, on any network, so
     * the next tap plays it at once. [done] says whether both the match and its stream were got.
     */
    fun prepareVideoSong(video: Song, done: (Boolean) -> Unit) {
        if (!video.isVideo || video.isLive || video.isEpisode || video.isLocal) { done(false); return }
        scope.launch {
            // No separate song: the video plays as itself, so get its own sound ready instead.
            val id = songFor(video, 20_000)?.id ?: video.id
            // Downloaded, a file on the phone, or a whole cached song: it already starts at once, with no address.
            if (withContext(Dispatchers.IO) { c.availableOffline(id) }) { done(true); return@launch }
            // An explicit prepare may cache the start even on mobile data; it's the listener asking.
            val ok = runCatching { withContext(Dispatchers.IO) { c.streams.resolve(id) } }.isSuccess
            if (ok) runCatching { withContext(Dispatchers.IO) { c.warmUp(id, cellular = true) } }
            done(ok)
        }
    }

    /**
     * The song a music video is of ([YouTubeMusic.songForVideo]), remembered for the session; the video becomes the
     * song's counterpart (its Video button). Null when there's none, or it took longer than [timeoutMs]. The lookup
     * carries on (and is kept) when a [prefetchSongFor] or an earlier tap already started it.
     */
    suspend fun songFor(video: Song, timeoutMs: Long = 8_000): Song? {
        videoSongs[video.id]?.let { return it }
        // The matches kept from before, so one already answered isn't searched again (a wait only on the first tap).
        if (!matchesLoaded.isCompleted) matchesLoaded.await()
        videoSongs[video.id]?.let { return it }
        // The search answered nothing before: the video plays as itself, and not another search on every tap.
        if (video.id in noSongFor) return null
        // Answered before: its one row is read and kept for this session, rather than the whole table at every start.
        if (knownSongs.contains(video.id)) {
            val row = runCatching { kotlinx.coroutines.withContext(Dispatchers.IO) { c.db.videoSongs().get(video.id) } }.getOrNull()
            knownSongs.remove(video.id)
            if (row != null) {
                val saved = row.songJson?.let { runCatching { InnerTube.json.decodeFromString(Song.serializer(), it) }.getOrNull() }
                if (saved != null) {
                    videoSongs[video.id] = saved
                    c.queue.counterparts[saved.id] = video.id
                    return saved
                }
                // A row that holds no song (or one that won't read): the video plays as itself, as it did before.
                noSongFor.add(video.id)
                return null
            }
            // The row has gone (a table pruned, say): search as if it had never answered.
        }
        val running = matchingSongs[video.id] ?: run {
            val started = scope.async(Dispatchers.IO) {
                val attempt = runCatching { c.ytm.songForVideo(video) }
                val found = attempt.getOrNull()
                // Only an answer (a song, or none) is kept; a lookup that failed (no network) is asked again later.
                if (attempt.isSuccess) rememberVideoSong(video.id, found)
                found
            }
            started.invokeOnCompletion { matchingSongs.remove(video.id, started) }
            matchingSongs.putIfAbsent(video.id, started)?.also { if (it !== started) started.cancel() } ?: started
        }
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { running.await() }
    }

    private fun startQueue(songs: List<Song>, startIndex: Int, source: QueueSource, shuffle: Boolean, startVideo: String?, startMs: Long = 0, rest: (suspend () -> List<Song>?)? = null) {
        stopFill()
        // Before the player starts: autoplay would otherwise see a short first page as a queue nearly over.
        c.queue.filling = rest != null
        val token = playToken
        // DJ mode is for the queue it was turned on in; "Play as a DJ set" turns it on for the new one.
        val dj = djNext && !shuffle
        djNext = false
        c.dj.setOn(false)
        // A new queue starts as plain shuffle (or none); Smart Shuffle is turned on per queue. With "Keep shuffle on for
        // the next list" (#416) a list takes over what the queue before it had, Smart Shuffle too ([carryShuffle], below).
        val wasSmart = c.queue.smartShuffle.value
        c.queue.smartShuffle.value = false
        c.queue.smartPicks.value = emptySet()
        c.queue.source.value = source
        c.queue.autoplayContinuation = null
        c.queue.autoplayStart.value = -1
        c.queue.userQueued.value = emptySet()
        ensureStarted()
        withPlayer { p ->
            // Whatever Autoplay was still finding was for the queue going now: this one asks for its own, below.
            service?.dropAutoplay()
            val start = startIndex.coerceIn(0, songs.lastIndex)
            val was = when { !p.shuffleModeEnabled -> ShuffleMode.OFF; wasSmart -> ShuffleMode.SMART; else -> ShuffleMode.SHUFFLE }
            val prefs = c.settings.current.behavior
            val mode = carryShuffle(
                was, prefs.keepShuffle, startKind(source, songs, start, asVideo = startVideo != null, dj = dj), asked = shuffle,
                prefs.shuffleButton, c.queue.smartAllowed(),
            )
            // 1.8.5: what Prism picks by itself leaves snoozed tracks out. The song a queue starts on is the listener's
            // (a tap, a song just played), so it stays even when snoozed; a shuffle's start wasn't picked, so it gives
            // way too — to another random song: its fallback was the top of the queue, so with tracks snoozed a
            // library's shuffles kept starting near the top, once per snoozed pick. A list with nothing left plays as
            // it is rather than not at all.
            val starting = songs[start]
            val kept = c.snoozes.allowed(songs, keep = starting.takeUnless { shuffle })
            val queue = kept.ifEmpty { songs }
            val first = queue.indexOfFirst { it.id == starting.id }.let { at ->
                when {
                    at >= 0 -> at
                    // The shuffle's pick was a snoozed track: another random song, never the first one there.
                    shuffle -> queue.indices.random()
                    else -> 0
                }
            }
            val shuffled = mode != ShuffleMode.OFF
            // A long list (Downloads can hold thousands, the library hundreds) isn't made into media items on the main
            // thread before the first sound: the picked song and a few after it start, and the rest joins once it's
            // under way. A shuffled queue did build every item first (1.1.0), which is what made Shuffle wait on 800
            // songs: it now starts on a window too, the window's order random and the rest shuffled in behind it.
            val fastStart = !shuffled && rest == null && queue.size > FAST_START_AT
            val fastShuffle = shuffled && rest == null && queue.size > FAST_START_AT
            val fast = fastStart || fastShuffle
            val from = if (fast) first else 0
            val to = if (fast) (first + FAST_START_AFTER + 1).coerceAtMost(queue.size) else queue.size
            val at = first - from
            c.queue.filling = rest != null || fastShuffle
            p.shuffleModeEnabled = shuffled
            val built = android.os.SystemClock.elapsedRealtime()
            val items = queue.subList(from, to).mapIndexed { i, s -> s.toMediaItem(if (i == at) startVideo else null) }
            p.setMediaItems(items, at, startMs.coerceAtLeast(0))
            lastQueueStart = QueueStart(queue.size, items.size, shuffled, android.os.SystemClock.elapsedRealtime() - built)
            AudioDiagnostics.log(
                "queue",
                (if (fastShuffle) "shuffle " else "") + "${queue.size} songs: ${items.size} items in ${lastQueueStart!!.ms} ms before play",
            )
            // The song it starts on first, the rest in a new order.
            if (shuffled) reshuffle(p)
            p.prepare()
            p.play()
            if (fastShuffle) fillShuffled(queue, from, to, token)
            else if (fastStart) fillAround(queue, first, to - first)
            if (mode == ShuffleMode.SMART) {
                c.queue.smartShuffle.value = true
                // Its picks now: the service's Smart Shuffle collector never saw it go off and on again above.
                c.queue.smartStarted.value++
            }
            service?.maybeAutoplay()
            if (dj) c.dj.setOn(true)
            if (rest != null && token == playToken) fillQueue(rest, source, queue)
        }
    }

    /**
     * The songs either side of a long list's first few ([startQueue]'s fast start): made into media items off the main
     * thread once the picked song is playing (so the filling never slows its start), then put before and after it. Its
     * own place doesn't move.
     */
    private fun fillAround(all: List<Song>, start: Int, started: Int) {
        if (start <= 0 && start + started >= all.size) return
        val token = playToken
        val headEnd = start.coerceIn(0, all.size)
        val tailFrom = (start + started).coerceIn(headEnd, all.size)
        scope.launch {
            // The first sound first: eight hundred songs made into items beside it was time it spent waiting.
            kotlinx.coroutines.withTimeoutOrNull(2_000) { isPlaying.first { it } }
            val head = if (headEnd > 0) withContext(Dispatchers.Default) { all.subList(0, headEnd).map { it.toMediaItem() } } else emptyList()
            val tail = if (tailFrom < all.size) withContext(Dispatchers.Default) { all.subList(tailFrom, all.size).map { it.toMediaItem() } } else emptyList()
            if (token != playToken) return@launch
            val p = player ?: return@launch
            // A few at a time, so the timeline isn't rebuilt and the queue re-read all at once.
            head.chunked(FILL_BATCH).asReversed().forEach { if (token == playToken) runCatching { p.addMediaItems(0, it) } }
            tail.chunked(FILL_BATCH).forEach { if (token == playToken) runCatching { p.addMediaItems(p.mediaItemCount, it) } }
        }
    }

    private var djNext = false

    /** Plays [songs] as a DJ set (DJ mode on): from the first, then in the order that blends best (album/playlist menus). */
    fun playDjSet(songs: List<Song>, source: QueueSource) {
        djNext = true
        playQueue(songs, 0, source)
    }

    /** DJ mode on or off for the queue playing (the queue's switch). */
    fun setDjMode(on: Boolean) = withPlayer { p ->
        if (on && p.shuffleModeEnabled) p.shuffleModeEnabled = false
        c.dj.setOn(on)
    }

    /** Plays a lone song; autoplay then continues with similar music. */
    fun playSingle(song: Song) = playQueue(listOf(song), 0, QueueSource(QueueKind.SINGLE, tr(R.string.autoplay), seedId = song.id))

    /** [playSingle] from [startMs] in (a recognized song carrying on from what was heard, #215); 0 is the start. */
    fun playSingleFrom(song: Song, startMs: Long) {
        // The explicit filter (#216) has its say the usual way.
        if (startMs <= 0 || (song.explicit && c.settings.current.behavior.explicitContent != com.prism.music.data.prefs.ExplicitContent.SHOW)) { playSingle(song); return }
        ++playToken
        startQueue(listOf(song), 0, QueueSource(QueueKind.SINGLE, tr(R.string.autoplay), seedId = song.id), shuffle = false, startVideo = null, startMs = startMs)
    }

    /**
     * Plays [song] from its best part (its most replayed stretch, or its chorus), then on as [playSingle] would.
     * Without one it plays from the start, and says so.
     */
    fun playFromBestPart(song: Song) {
        val token = ++playToken
        scope.launch {
            val start = kotlinx.coroutines.withTimeoutOrNull(5_000) { runCatching { c.bestParts.startMs(song) }.getOrNull() }
            if (token != playToken) return@launch
            if (start == null) reportError(tr(R.string.prism_doesn_t_know_a_best, song.title))
            startQueue(listOf(song), 0, QueueSource(QueueKind.SINGLE, tr(R.string.autoplay), seedId = song.id), shuffle = false, startVideo = null, startMs = start ?: 0)
        }
    }

    /**
     * Plays [song] and then a radio of songs like it (YouTube Music's), whether or not Autoplay is on. The song playing
     * now carries on where it is, the radio lined up after it (#292: it started again from the top).
     */
    fun playRadio(song: Song) {
        val source = QueueSource(QueueKind.RADIO, tr(R.string.radio, song.title), seedId = song.id)
        val p = player
        if (p == null || p.mediaItemCount == 0 || p.currentMediaItem?.mediaId != song.id) { playQueue(listOf(song), 0, source); return }
        ++playToken
        stopFill()
        // As a new queue would be: just this song (what came before stays in Played), in order, the radio to follow.
        djNext = false
        c.dj.setOn(false)
        c.queue.smartShuffle.value = false
        c.queue.smartPicks.value = emptySet()
        c.queue.source.value = source
        c.queue.autoplayContinuation = null
        c.queue.autoplayStart.value = -1
        c.queue.userQueued.value = emptySet()
        p.shuffleModeEnabled = false
        val at = p.currentMediaItemIndex
        if (at + 1 < p.mediaItemCount) p.removeMediaItems(at + 1, p.mediaItemCount)
        if (at > 0) p.removeMediaItems(0, at)
        if (!p.isPlaying) { ensureStarted(); if (p.playbackState == Player.STATE_IDLE) p.prepare(); p.play() }
        // An autoplay load still out for the old queue is dropped: the radio asks now rather than wait on it.
        service?.dropAutoplay()
        service?.maybeAutoplay()
    }

    fun playNext(song: Song) = playNext(listOf(song), null)

    fun addToQueue(song: Song) = addToQueue(listOf(song), null)

    /** Songs the listener adds go in at [at]; autoplay's suggestions (after them) move along. */
    private fun insertQueued(p: ExoPlayer, at: Int, songs: List<Song>) {
        p.addMediaItems(at, songs.map { it.toMediaItem() })
        val auto = c.queue.autoplayStart.value
        if (auto >= at) c.queue.autoplayStart.value = auto + songs.size
        c.queue.userQueued.value = c.queue.userQueued.value + songs.map { it.id }
    }

    /**
     * #216: [songs] without the explicit ones when the filter is on (saying so when some were left out); null, having
     * said so, when none is left.
     */
    private fun playable(songs: List<Song>): List<Song>? {
        val mode = c.settings.current.behavior.explicitContent
        val kept = songs.filter { com.prism.music.data.ExplicitFilter.plays(mode, it) }
        if (kept.isEmpty()) { reportError(tr(R.string.explicit_none_left)); return null }
        if (kept.size < songs.size) reportError(tr(R.string.explicit_skipped))
        return kept
    }

    /**
     * With nothing on, [songs] become the queue: one song as a single (its radio follows), several (songs picked with
     * Select many, which come with no [source], #476: only the first used to play) in their order.
     */
    private fun startWith(songs: List<Song>, source: QueueSource?) {
        if (source == null && songs.size == 1) playSingle(songs.first())
        else playQueue(songs, 0, source ?: QueueSource(QueueKind.OTHER, ""))
    }

    /**
     * Songs (one, or a whole album or playlist) straight after the current song, or played, with nothing on. With
     * "Append to Play Next" on they go at the end of "Next in queue" instead ([appendAfterQueued]).
     */
    fun playNext(songs: List<Song>, source: QueueSource?) {
        if (songs.isEmpty()) return
        val allowed = playable(songs) ?: return
        if (allowed.size < songs.size) return playNext(allowed, source)
        val append = c.settings.current.behavior.appendToPlayNext
        withPlayer { p ->
            if (p.mediaItemCount == 0) {
                startWith(songs, source)
            } else if (append) {
                appendAfterQueued(p, songs)
            } else {
                // #368: a song already coming up moves up (it used to go in a second time). Next already: nothing moves,
                // so a stream or a blend being got ready for it carries on.
                val order = playOrder(p)
                val ahead = order.drop(order.indexOf(p.currentMediaItemIndex) + 1)
                val aheadIds = ahead.map { p.getMediaItemAt(it).mediaId }
                val ids = songs.map { it.id }
                if (aheadIds.take(ids.size) == ids) {
                    c.queue.userQueued.value = c.queue.userQueued.value + ids
                    return@withPlayer
                }
                QueueEdit.copiesMovingUp(aheadIds, ids).map { ahead[it] }.sortedDescending().forEach { removeAt(it) }
                insertQueued(p, p.currentMediaItemIndex + 1, songs)
            }
        }
    }

    /**
     * "Append to Play Next": [songs] go at the end of "Next in queue", after the listener's own songs and before the
     * list's, instead of straight after the one playing, so several Play nexts keep the order they were tapped in.
     * #368 still holds: a copy already coming up moves there rather than going in twice. With nothing queued they are
     * still next, so the first Play next lands where it always did.
     */
    private fun appendAfterQueued(p: ExoPlayer, songs: List<Song>) {
        val ids = songs.map { it.id }
        val order = playOrder(p)
        val cur = order.indexOf(p.currentMediaItemIndex)
        val ahead = if (cur >= 0) order.drop(cur + 1) else emptyList()
        val aheadIds = ahead.map { p.getMediaItemAt(it).mediaId }
        // Already where they'd go — the end of Next in queue, or (none queued yet) right after the playing song:
        // nothing moves, so a stream or a blend being got ready for them carries on (#368).
        val run = queuedEnd(p).second
        if (run.size >= ids.size && run.subList(run.size - ids.size, run.size) == ids ||
            aheadIds.drop(run.size).take(ids.size) == ids
        ) {
            c.queue.userQueued.value = c.queue.userQueued.value + ids
            return
        }
        if (cur >= 0) {
            QueueEdit.copiesMovingUp(aheadIds, ids).map { ahead[it] }.sortedDescending().forEach { removeAt(it) }
        }
        val at = queuedEnd(p).first + 1
        val shuffle = p.shuffleOrder as? HistoryShuffleOrder
        insertQueued(p, at, songs)
        // Media3's shuffle slots a song added anywhere but straight after the playing one at a random place: while
        // shuffled, put these after the song they follow in play order instead.
        if (p.shuffleModeEnabled && shuffle != null) p.setShuffleOrder(shuffle.insertedAfter(at, songs.size, at - 1))
    }

    /**
     * Where "Next in queue" ends: the media index of its last song (the playing song when none of the listener's own
     * are coming up), and those songs' ids in play order.
     */
    private fun queuedEnd(p: ExoPlayer): Pair<Int, List<String>> {
        var anchor = p.currentMediaItemIndex
        val run = ArrayList<String>()
        val order = playOrder(p)
        val cur = order.indexOf(p.currentMediaItemIndex)
        if (cur < 0) return anchor to run
        val queued = c.queue.userQueued.value
        for (i in cur + 1 until order.size) {
            val at = order[i]
            val id = p.getMediaItemAt(at).mediaId
            if (id !in queued) break
            anchor = at
            run += id
        }
        return anchor to run
    }

    /**
     * Songs after what's queued, before autoplay's suggestions (they used to land after those, so a song added
     * to the queue could be a long way off). Played, with nothing on.
     */
    fun addToQueue(songs: List<Song>, source: QueueSource?) {
        if (songs.isEmpty()) return
        val allowed = playable(songs) ?: return
        if (allowed.size < songs.size) return addToQueue(allowed, source)
        withPlayer { p ->
            if (p.mediaItemCount == 0) {
                startWith(songs, source)
            } else {
                val auto = c.queue.autoplayStart.value
                val cur = p.currentMediaItemIndex
                val at = when {
                    auto < 0 -> p.mediaItemCount
                    auto > cur -> minOf(auto, p.mediaItemCount)
                    // Already into autoplay's songs: after anything else queued, before the next suggestion.
                    else -> {
                        var i = cur + 1
                        while (i < p.mediaItemCount && p.getMediaItemAt(i).mediaId in c.queue.userQueued.value) i++
                        i
                    }
                }
                insertQueued(p, at, songs)
            }
        }
    }

    /**
     * Listen Together, and a TV playing along with a phone: plays [songs] in order from the one at [index] (the current
     * one first by default), from [positionMs].
     */
    fun follow(songs: List<Song>, positionMs: Long, title: String, playing: Boolean, index: Int = 0) {
        if (songs.isEmpty()) return
        ++playToken
        stopFill()
        c.queue.source.value = QueueSource(QueueKind.OTHER, title)
        c.queue.autoplayContinuation = null
        c.queue.autoplayStart.value = -1
        c.queue.userQueued.value = emptySet()
        ensureStarted()
        withPlayer { p ->
            p.shuffleModeEnabled = false
            p.repeatMode = Player.REPEAT_MODE_OFF
            p.setMediaItems(songs.map { it.toMediaItem() }, index.coerceIn(0, songs.lastIndex), positionMs.coerceAtLeast(0))
            p.prepare()
            p.playWhenReady = playing
        }
    }

    /**
     * Prism Connect handoff: another device's playback continues here, the whole queue from the same song and place,
     * in the same play order ([order]: queue indices as they play; null when not shuffled) and repeat mode.
     */
    fun takeOver(songs: List<Song>, index: Int, positionMs: Long, playing: Boolean, order: List<Int>?, repeat: Int, title: String) {
        if (songs.isEmpty()) return
        ++playToken
        stopFill()
        djNext = false
        c.dj.setOn(false)
        c.queue.smartShuffle.value = false
        c.queue.smartPicks.value = emptySet()
        c.queue.source.value = QueueSource(QueueKind.OTHER, title)
        c.queue.autoplayContinuation = null
        c.queue.autoplayStart.value = -1
        c.queue.userQueued.value = emptySet()
        ensureStarted()
        withPlayer { p ->
            val start = index.coerceIn(0, songs.lastIndex)
            p.setMediaItems(songs.map { it.toMediaItem() }, start, positionMs.coerceAtLeast(0))
            p.repeatMode = repeat.coerceIn(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL)
            val shuffled = order != null && order.size == songs.size && order.sorted() == songs.indices.toList()
            if (shuffled) p.setShuffleOrder(HistoryShuffleOrder(order!!.toIntArray()) { p.currentMediaItemIndex })
            p.shuffleModeEnabled = shuffled
            p.prepare()
            p.playWhenReady = playing
            service?.maybeAutoplay()
        }
    }

    /** Listen Together: makes what plays after the current song match the host's. */
    fun replaceUpcoming(songs: List<Song>) = withPlayer { p ->
        val idx = p.currentMediaItemIndex
        val have = (idx + 1 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }
        if (have == songs.map { it.id }) return@withPlayer
        if (idx + 1 < p.mediaItemCount) p.removeMediaItems(idx + 1, p.mediaItemCount)
        p.addMediaItems(songs.map { it.toMediaItem() })
    }

    /** The listener wants it playing (it may still be buffering). */
    val playWhenReady: Boolean get() = player?.playWhenReady ?: false

    fun setPlaying(playing: Boolean) = withPlayer { p ->
        if (playing) {
            ensureStarted()
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
        } else p.pause()
    }

    /** Takes everything after the current song out of the queue. */
    fun clearUpcoming() = withPlayer { p ->
        stopFill()
        val i = p.currentMediaItemIndex
        if (i + 1 < p.mediaItemCount) p.removeMediaItems(i + 1, p.mediaItemCount)
        c.queue.autoplayStart.value = -1
        c.queue.userQueued.value = emptySet()
    }

    /** Stops and empties the queue, so the mini player goes (#59: swiped down). */
    fun stop() = withPlayer { p ->
        ++playToken
        stopFill()
        p.stop()
        p.clearMediaItems()
        c.queue.autoplayStart.value = -1
        c.queue.autoplayContinuation = null
        c.queue.userQueued.value = emptySet()
    }

    fun togglePlay() = withPlayer { p ->
        if (!p.isPlaying) ensureStarted()
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0, 0)
        val play = !p.isPlaying
        // With "Fade on pause and play" (A5) the service fades it; else at once.
        if (service?.gentle(play) != true) { if (play) p.play() else p.pause() }
    }

    fun next() = withPlayer { it.seekToNext() }
    /**
     * Previous: the song before near the start of this one, or this one's start again once past it. Settings → Gestures
     * & buttons → "Previous always goes back" goes to the song before wherever the playhead is (and starts this one
     * over only when there's nothing before it).
     */
    fun previous() = withPlayer { p ->
        if (c.settings.current.behavior.previousAlwaysBack) previousToSongBefore(p) else p.seekToPrevious()
    }
    /** The song before, wherever this one is (#481: Previous held): the player's own previous item, so shuffle's order. */
    fun previousSong() = withPlayer { it.seekToPreviousMediaItem() }
    /** The song before if there is one (the player's own, so shuffle's order), else this one from its start. */
    private fun previousToSongBefore(p: Player) {
        if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem() else p.seekToDefaultPosition()
    }

    /** Calls [done] once the player is reached (after commands asked for before it, such as a widget's), at once if it is. */
    fun afterPending(done: () -> Unit) = withPlayer { done() }
    fun seekTo(ms: Long) = withPlayer { it.seekTo(ms) }
    /** Moves the playhead by [ms] (negative goes back), kept inside the song. */
    fun seekBy(ms: Long) = withPlayer { p ->
        val end = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + ms).coerceIn(0, (end - 1_000).coerceAtLeast(0)))
    }
    fun skipTo(index: Int) = withPlayer { p ->
        // Shuffled, the song picked plays next in the order, so Previous comes back to the one playing now.
        if (p.shuffleModeEnabled) (p.shuffleOrder as? HistoryShuffleOrder)?.let { p.setShuffleOrder(it.playingNext(p.currentMediaItemIndex, index)) }
        p.seekToDefaultPosition(index)
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        p.play()
    }
    fun removeAt(index: Int) = withPlayer { p ->
        val id = p.getMediaItemAt(index).mediaId
        p.removeMediaItem(index)
        val auto = c.queue.autoplayStart.value
        if (auto > index) c.queue.autoplayStart.value = auto - 1
        forgetQueued(p, id)
    }

    /**
     * A song taken out of the queue is no longer one the listener queued ("Next in queue"), unless another copy of it
     * stays: the queue's heading and "Append to Play Next"'s end are read from this set, and a copy left behind used
     * to keep a song that's gone marking both.
     */
    private fun forgetQueued(p: ExoPlayer, id: String) {
        if (id !in c.queue.userQueued.value) return
        if ((0 until p.mediaItemCount).any { p.getMediaItemAt(it).mediaId == id }) return
        c.queue.userQueued.value = c.queue.userQueued.value - id
    }

    /**
     * Swaps the media item now playing for [song] without dropping the rest of the queue or the place in the song
     * (duplicate hiding uses this when the playing version becomes hidden). Direct playback never goes through here.
     */
    fun swapCurrentWith(song: Song) = withPlayer { p ->
        val at = p.currentMediaItemIndex
        val position = p.currentPosition
        if (at < 0 || at >= p.mediaItemCount) return@withPlayer
        val wasPlaying = p.isPlaying
        val items = (0 until p.mediaItemCount).map { i -> p.getMediaItemAt(i) }.toMutableList()
        items[at] = song.toMediaItem()
        p.setMediaItems(items, at, position)
        p.prepare()
        if (wasPlaying) p.play()
    }

    /**
     * Takes the song at [index] out (a swipe in the queue, #149) and returns how to put it back where it was, or null
     * when there's nothing there. The item itself goes back, so it keeps everything it was queued with.
     */
    fun removeForUndo(index: Int): (() -> Unit)? {
        val p = player ?: return null
        if (index !in 0 until p.mediaItemCount) return null
        val item = p.getMediaItemAt(index)
        val wasAuto = c.queue.autoplayStart.value.let { it in 0..index }
        // The queue shows the play order (shuffled, the shuffled one): Undo puts the song back in its place there, after
        // the song it followed (or before the one that followed it), on its side of the playing song, as the queue is
        // when Undo is tapped ([QueueEdit.undoSpot]; Reddit, 1.8.0 and 1.8.1.1). The songs are known by their ids.
        val order = playOrder(p)
        val pos = order.indexOf(index)
        val ahead = pos > order.indexOf(p.currentMediaItemIndex)
        val playedAfter = order.getOrNull(pos - 1)?.takeIf { pos > 0 }?.let { p.getMediaItemAt(it).mediaId }
        val playedBefore = order.getOrNull(pos + 1)?.takeIf { pos >= 0 }?.let { p.getMediaItemAt(it).mediaId }
        // Out of the queue, and out of "Next in queue" with it (unless another copy stays); Undo puts both back.
        val wasQueued = item.mediaId in c.queue.userQueued.value
        removeAt(index)
        return {
            withPlayer { q ->
                val now = playOrder(q)
                val spot = QueueEdit.undoSpot(
                    now.map { q.getMediaItemAt(it).mediaId }, now.indexOf(q.currentMediaItemIndex), playedAfter, playedBefore, ahead,
                    near = pos.coerceAtLeast(0),
                )
                val shuffled = q.shuffleModeEnabled
                // In order, its place in the play order is its place in the list; shuffled, it goes back where it was in
                // the list and its place in the play order is set below.
                val at = if (shuffled) index.coerceAtMost(q.mediaItemCount) else now.getOrNull(spot) ?: q.mediaItemCount
                q.addMediaItem(at, item)
                if (wasQueued) c.queue.userQueued.value = c.queue.userQueued.value + item.mediaId
                val auto = c.queue.autoplayStart.value
                // Back among the songs it was with: before autoplay's suggestions, or one of them.
                if (auto >= 0 && (auto > at || auto == at && !wasAuto)) c.queue.autoplayStart.value = auto + 1
                if (shuffled) {
                    val placed = now.map { if (it >= at) it + 1 else it }.toMutableList().apply { add(spot.coerceIn(0, size), at) }
                    q.setShuffleOrder(HistoryShuffleOrder(placed.toIntArray()) { q.currentMediaItemIndex })
                }
            }
        }
    }
    fun move(from: Int, to: Int) = withPlayer { it.moveMediaItem(from, to) }

    // ---- Editing several songs at once (R11, #159): what the queue's selection and its rows' ⋯ menus do.

    /** [indices] in the order they play now (the queue lists them that way, shuffled or not). */
    private fun inPlayOrder(p: ExoPlayer, indices: Collection<Int>): List<Int> {
        val order = playOrder(p)
        return indices.distinct().filter { it in 0 until p.mediaItemCount }.sortedBy { order.indexOf(it).let { at -> if (at < 0) Int.MAX_VALUE else at } }
    }

    /**
     * Moves the songs at [indices] one after another into place, [to] giving each one's place in a running copy of the
     * queue's order ([list], the songs' original indices) once it's out. The songs moved are the listener's from then on;
     * autoplay's suggestions start again at the first of theirs that stayed.
     */
    private fun shift(p: ExoPlayer, indices: List<Int>, to: (MutableList<Int>, Int) -> Int) {
        val plan = QueueEdit.plan(p.mediaItemCount, indices, c.queue.autoplayStart.value, to)
        plan.moves.forEach { (from, at) -> p.moveMediaItem(from, at) }
        c.queue.autoplayStart.value = plan.autoplayStart
    }

    /** The songs at [indices] play straight after the current one, in the order they're in now. */
    fun moveNext(indices: Collection<Int>) = withPlayer { p ->
        val cur = p.currentMediaItemIndex
        val picked = inPlayOrder(p, indices).filter { it != cur }
        if (picked.isEmpty()) return@withPlayer
        c.queue.userQueued.value = c.queue.userQueued.value + picked.map { p.getMediaItemAt(it).mediaId }
        if (p.shuffleModeEnabled) {
            // Each one straight after the current song, last first, so they end up in their order.
            picked.asReversed().forEach { moveInPlayOrder(it, cur, after = true) }
            return@withPlayer
        }
        var anchor = cur
        shift(p, picked) { list, s -> (list.indexOf(anchor) + 1).also { anchor = s } }
    }

    /** The songs at [indices] go to the end of what's queued, before autoplay's suggestions. */
    fun moveToEnd(indices: Collection<Int>) = withPlayer { p ->
        val cur = p.currentMediaItemIndex
        val picked = inPlayOrder(p, indices).filter { it != cur }
        if (picked.isEmpty()) return@withPlayer
        if (p.shuffleModeEnabled) {
            picked.forEach { s -> playOrder(p).lastOrNull { it != s }?.let { last -> moveInPlayOrder(s, last, after = true) } }
            return@withPlayer
        }
        val auto = c.queue.autoplayStart.value
        shift(p, picked) { list, _ ->
            // Before autoplay's first suggestion that stays one (its place in the running copy), or the very end.
            if (auto < 0) list.size else list.indices.firstOrNull { i -> list[i] >= auto && list[i] !in picked } ?: list.size
        }
    }

    /** Takes the songs at [indices] out (never the current one). */
    fun removeMany(indices: Collection<Int>) = withPlayer { p ->
        val cur = p.currentMediaItemIndex
        indices.distinct().filter { it != cur && it in 0 until p.mediaItemCount }.sortedDescending().forEach { removeAt(it) }
    }

    /** Takes out every song that plays after the one at [index] (the queue's "Clear everything after this"). */
    fun clearAfter(index: Int) = withPlayer { p ->
        val order = playOrder(p)
        val at = order.indexOf(index)
        if (at < 0) return@withPlayer
        stopFill()
        removeMany(order.drop(at + 1))
    }

    /** The songs at [indices], for adding them to a playlist. */
    fun songsAt(indices: Collection<Int>): List<Song> = player?.let { p ->
        inPlayOrder(p, indices).mapNotNull { p.getMediaItemAt(it).toSong() }
    } ?: emptyList()

    /**
     * Shuffled, a song dragged in the queue: the queue shows the play order then, so the song moves there, to just after
     * (or before) [beside], and the songs themselves stay where they are in the list behind it.
     */
    fun moveInPlayOrder(index: Int, beside: Int, after: Boolean) = withPlayer { p ->
        if (!p.shuffleModeEnabled) return@withPlayer
        val order = p.shuffleOrder as? HistoryShuffleOrder ?: HistoryShuffleOrder(playOrder(p).toIntArray()) { p.currentMediaItemIndex }
        p.setShuffleOrder(order.placed(index, beside, after))
    }

    /** The queue's indices in the order they'll play (shuffled or not). */
    private fun playOrder(p: ExoPlayer): List<Int> {
        val t = p.currentTimeline
        if (t.isEmpty) return emptyList()
        val out = mutableListOf<Int>()
        var i = t.getFirstWindowIndex(p.shuffleModeEnabled)
        while (i != C.INDEX_UNSET && out.size < t.windowCount) {
            out += i
            i = t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
        }
        return out
    }
    /**
     * Shuffle's button (the player's, the TV's and the browser remote's all come here): off → shuffle → Smart Shuffle
     * (where the queue allows it) → off, or off → on → off with "Shuffle only" (D57): [nextShuffle].
     */
    fun toggleShuffle() = withPlayer {
        // #374: in DJ mode Shuffle deals a new DJ set; DJ mode stays on and its order stays the order (no Media3 shuffle).
        if (c.dj.onNow) {
            if (it.shuffleModeEnabled) it.shuffleModeEnabled = false
            c.dj.newSet()
            return@withPlayer
        }
        val now =when { !it.shuffleModeEnabled -> ShuffleMode.OFF; c.queue.smartShuffle.value -> ShuffleMode.SMART; else -> ShuffleMode.SHUFFLE }
        when (nextShuffle(now, c.settings.current.behavior.shuffleButton, c.queue.smartAllowed())) {
            ShuffleMode.SHUFFLE -> { reshuffle(it); it.shuffleModeEnabled = true }
            ShuffleMode.SMART -> c.queue.smartShuffle.value = true
            ShuffleMode.OFF -> { c.queue.smartShuffle.value = false; it.shuffleModeEnabled = false }
        }
    }

    /**
     * A fresh random order every time shuffle is pressed (the player otherwise keeps reusing its old one),
     * with the current song first so everything else is still ahead of it.
     */
    private fun reshuffle(p: ExoPlayer) {
        val n = p.mediaItemCount
        if (n < 2) return
        val cur = p.currentMediaItemIndex.coerceIn(0, n - 1)
        p.setShuffleOrder(HistoryShuffleOrder.shuffled(n, cur) { p.currentMediaItemIndex })
    }
    fun cycleRepeat() = withPlayer { it.repeatMode = nextRepeatMode(it.repeatMode) }

    private val _videoLoading = MutableStateFlow(false)
    /** True from tapping the video button until its first frame shows. */
    val videoLoading: StateFlow<Boolean> = _videoLoading

    /**
     * Runs [meanwhile] with [songId] stopped, if it's the song playing now, then starts it again where it was: its
     * sound changed (another version was picked), and what the player had loaded of the old one mustn't play on.
     */
    suspend fun reloadIfCurrent(songId: String, meanwhile: suspend () -> Unit) {
        val p = player
        if (p == null || p.currentMediaItem?.mediaId != songId) { meanwhile(); return }
        val at = p.currentPosition
        val play = p.playWhenReady
        p.stop()
        meanwhile()
        if (p.currentMediaItem?.mediaId != songId) return
        p.seekTo(at)
        p.prepare()
        p.playWhenReady = play
        // Its sound changed under it (another version picked): its own loudness figure, at once (1.1.3).
        service?.formChanged()
    }

    fun setVideoMode(enabled: Boolean) {
        val s = service ?: return
        if (enabled && c.cast.casting) {
            _errors.tryEmit(tr(R.string.videos_play_on_this_phone_while))
            return
        }
        // #281: asked for some way other than the button (a gesture, the web remote).
        if (enabled && _videoOffOnData.value) {
            _errors.tryEmit(tr(R.string.videos_are_off_on_mobile_data))
            return
        }
        _videoLoading.value = enabled
        // An episode's picture is that episode's own choice; only music turns "keep playing videos" on.
        if (s.player.currentMediaItem?.toSong()?.isEpisode != true || !enabled) s.videoWanted = enabled
        scope.launch {
            s.setVideoMode(enabled)?.let { _errors.tryEmit(it); _videoLoading.value = false }
            if (!enabled) _videoLoading.value = false
            refresh()
        }
    }

    override fun onRenderedFirstFrame() {
        _videoLoading.value = false
    }

    /** #305: a cover was picked (or put back) for a song or an album: the queue's songs it shows on take it up. */
    fun refreshCovers(shows: (Song) -> Boolean) = withPlayer { service?.refreshCovers(shows) }

    suspend fun videoIdFor(song: Song): String? = service?.videoIdFor(song)?.id ?: if (song.isVideo) song.id else null

    // ------------------------------------------------------------ Sleep timer

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private val sleepAudio = object : SleepAudio {
        override val itemKey: String?
            get() {
                val p = player ?: return null
                val item = p.currentMediaItem ?: return null
                // The song's own id, not the form's: a song ↔ video swap is the same listen, not a new song.
                return "${p.currentMediaItemIndex}:${item.toSong()?.id ?: item.mediaId}"
            }
        override val isPlaying: Boolean get() = player?.isPlaying == true
        override val positionMs: Long get() = position
        override val durationMs: Long get() = duration
        override val speed: Float get() = player?.playbackParameters?.speed ?: 1f

        override fun pause() { player?.pause() }
        override fun setPauseAtEnd(on: Boolean) { player?.pauseAtEndOfMediaItems = on }
        override fun setFade(gain: Float) { NormalizerProcessor.fadeVolume = gain }
        override fun setSessionVolume(volume: Float) { NormalizerProcessor.sleepVolume = NormalizerProcessor.volumeFor(volume) }
        override fun restoreVolume() { NormalizerProcessor.fadeVolume = 1f; NormalizerProcessor.sleepVolume = 1f }
        override fun reportNoEligibleSongs(maxBpm: Int) { reportError(tr(R.string.sleep_bpm_none_left, maxBpm)) }
    }

    /** The one running sleep session: its clock, fade and BPM gate (see [SleepTimerEngine]). */
    private val sleepTimer = SleepTimerEngine(
        audio = sleepAudio,
        bpmOf = { id -> c.mixTempo.djBpm(id) },
        onEnded = { onSleepSessionEnded() },
    )
    private var sleepJob: Job? = null
    private var sleepRestoreJob: Job? = null

    /** The track the timer handed over to when it finished (null: none), and its fade-in. */
    private var afterTimerSongId: String? = null
    private var afterTimerLoop = false
    private var afterTimerStarted = false
    private var afterTimerRestarting = false
    private var afterTimerPendingAt = 0L
    private var afterTimerFadeJob: Job? = null
    /** What played before the setup menu's volume preview, to put it back when the menu closes. */
    private var volumeBeforePreview: Float? = null

    /**
     * The BPM filter belonged to the session that just ran out: it goes back off for what plays next. Cancelling keeps
     * the listener's saved choices (the same as the timer's other settings). If the menu chose a track to play after the
     * timer, that starts here, fading in to the bedtime volume, which stays for as long as that track plays.
     */
    private fun onSleepSessionEnded() {
        pausedBySleepBpm = false
        c.sleepPrefs.setMaxBpm(0)
        val choices = c.sleepPrefs.choices.value
        val song = choices.afterSong ?: return
        afterTimerFadeJob?.cancel()
        afterTimerSongId = song.id
        afterTimerLoop = choices.loopAfter
        afterTimerStarted = false
        afterTimerPendingAt = android.os.SystemClock.elapsedRealtime()
        // The after-track keeps the bedtime volume (that's the point of the sleep-timer slider): it starts silent and
        // fades up to it. The listener's own volume comes back when this track ends or something else plays.
        NormalizerProcessor.sleepVolume = NormalizerProcessor.volumeFor(choices.volume)
        NormalizerProcessor.fadeVolume = if (choices.fadeInSeconds > 0) 0f else 1f
        scope.launch { startAfterTimer(song, choices.fadeInSeconds * 1_000L) }
    }

    /** Starts [song] alone (the timer's work is done) and fades it in over [fadeInMs]. */
    private fun startAfterTimer(song: Song, fadeInMs: Long) {
        if (afterTimerSongId != song.id) return
        playSingle(song)
        afterTimerFadeJob?.cancel()
        if (fadeInMs <= 0L) {
            NormalizerProcessor.fadeVolume = 1f
            return
        }
        val from = android.os.SystemClock.elapsedRealtime()
        afterTimerFadeJob = scope.launch {
            while (afterTimerSongId == song.id) {
                val progress = ((android.os.SystemClock.elapsedRealtime() - from).toFloat() / fadeInMs).coerceIn(0f, 1f)
                NormalizerProcessor.fadeVolume = SleepFade.gainIn(progress)
                if (progress >= 1f) break
                kotlinx.coroutines.delay(40)
            }
            if (afterTimerSongId == song.id) NormalizerProcessor.fadeVolume = 1f
        }
    }

    /** Leaves the after-timer track's special handling (its fade, its loop) and puts normal volume back. */
    private fun stopAfterTimer() {
        afterTimerFadeJob?.cancel()
        afterTimerFadeJob = null
        afterTimerSongId = null
        afterTimerLoop = false
        afterTimerStarted = false
        afterTimerRestarting = false
        afterTimerPendingAt = 0L
        volumeBeforePreview = null
        NormalizerProcessor.fadeVolume = 1f
        NormalizerProcessor.sleepVolume = 1f
    }

    /**
     * The after-timer track is one song: when it ends it starts over if "Loop this track" is on (without changing the
     * player's own repeat setting), and the moment anything else plays the special handling is over.
     */
    private fun handleAfterTimer() {
        val id = afterTimerSongId ?: return
        val p = player ?: return
        val current = p.currentMediaItem?.toSong()?.id
        if (!afterTimerStarted) {
            if (current == id) afterTimerStarted = true
            else if (afterTimerPendingAt > 0 && android.os.SystemClock.elapsedRealtime() - afterTimerPendingAt > 5_000) stopAfterTimer()
            return
        }
        if (current != id) {
            stopAfterTimer()
            return
        }
        if (p.playbackState != Player.STATE_ENDED) return
        if (!afterTimerLoop) {
            stopAfterTimer()
            return
        }
        if (afterTimerRestarting) return
        afterTimerRestarting = true
        try {
            p.seekTo(0)
            p.play()
        } finally {
            afterTimerRestarting = false
        }
    }

    /** The timer's own pause, so switching the BPM limit off can bring the music back (a listener's pause stays). */
    private var pausedBySleepBpm = false

    /** "Finish current song": the timer lets the song playing at the deadline end instead of stopping at it. */
    val sleepEndOfSong: StateFlow<Boolean> = sleepTimer.finishSong

    /** The temporary "allow every BPM" override of the running session (false when none runs). */
    val sleepBpmDisabled: StateFlow<Boolean> = sleepTimer.bpmDisabled

    /** The limit the running session was started with (0 when none). */
    val sleepMaxBpm: StateFlow<Int> = sleepTimer.maxBpm

    /**
     * Starts the one sleep session from the menu's saved choices and the fade set in Settings. Any running session is
     * replaced, and the BPM limit (if one was picked) applies to the song playing now at once.
     */
    fun startSleepTimer(fadeSeconds: Int? = null) {
        stopAfterTimer()
        val choices = c.sleepPrefs.choices.value
        sleepTimer.start(
            SleepConfig(
                minutes = choices.minutes,
                // The menu's slider value is passed in: the settings store writes off the main thread, so a Start right
                // after a drag must still use what was shown, not the value before the write landed.
                fadeSeconds = fadeSeconds ?: c.settings.current.sound.sleepFadeSeconds,
                maxBpm = choices.maxBpm,
                finishSong = choices.finishSong,
                volume = choices.volume,
            ),
        )
        runSleepLoop()
        enforceSleepBpm()
    }

    /** The old one-tap API (a chip's minutes): the same session with the saved choices, the time picked here. */
    fun setSleepTimer(minutes: Int) {
        if (minutes <= 0) { cancelSleepTimer(); return }
        stopAfterTimer()
        val choices = c.sleepPrefs.choices.value
        sleepTimer.start(
            SleepConfig(
                minutes = minutes,
                fadeSeconds = c.settings.current.sound.sleepFadeSeconds,
                maxBpm = choices.maxBpm,
                finishSong = choices.finishSong,
                volume = choices.volume,
            ),
        )
        runSleepLoop()
        enforceSleepBpm()
    }

    /** The old end-of-song API: a session that finishes the current song over the saved countdown. */
    fun setSleepEndOfSong(on: Boolean) {
        if (!on) { cancelSleepTimer(); return }
        stopAfterTimer()
        val choices = c.sleepPrefs.choices.value
        sleepTimer.start(
            SleepConfig(
                minutes = choices.minutes,
                fadeSeconds = c.settings.current.sound.sleepFadeSeconds,
                maxBpm = choices.maxBpm,
                finishSong = true,
                volume = choices.volume,
            ),
        )
        runSleepLoop()
        enforceSleepBpm()
    }

    /** Turns the timer off and puts the listener's own volume back (the saved choices stay as they are). */
    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        sleepRestoreJob?.cancel()
        sleepRestoreJob = null
        pausedBySleepBpm = false
        stopAfterTimer()
        sleepTimer.cancel()
    }

    /** -5 / +5 on the active timer: the deadline and the fade schedule move at once. */
    fun adjustSleepTimer(minutes: Int) {
        sleepTimer.adjust(minutes)
        if (sleepTimer.active.value && sleepJob?.isActive != true) runSleepLoop()
    }

    /**
     * The active timer's volume slider: heard at once. [remember] also makes it the menu's saved choice for next time
     * (the slider saves when the finger lifts, so a drag doesn't write to the store at every step).
     */
    fun setSleepVolume(volume: Float, remember: Boolean = false) {
        sleepTimer.setVolume(volume)
        if (remember) c.sleepPrefs.setVolume(volume)
    }

    /**
     * The configuration menu's volume while it's being set up: heard at once, though no timer runs yet. Closing the menu
     * calls [clearSleepVolumePreview], so the listener's own volume comes back (the chosen value stays remembered).
     */
    fun previewSleepVolume(volume: Float) {
        if (sleepTimer.active.value) return
        if (volumeBeforePreview == null) volumeBeforePreview = NormalizerProcessor.sleepVolume
        NormalizerProcessor.fadeVolume = 1f
        NormalizerProcessor.sleepVolume = NormalizerProcessor.volumeFor(volume)
    }

    /** Ends the configuration menu's volume preview, unless a timer started meanwhile (its own volume stays). */
    fun clearSleepVolumePreview() {
        if (sleepTimer.active.value) {
            volumeBeforePreview = null
            return
        }
        NormalizerProcessor.fadeVolume = 1f
        // What played before the menu: the listener's own volume, or the bedtime volume an after-timer track is using.
        NormalizerProcessor.sleepVolume = volumeBeforePreview
            ?: if (afterTimerSongId != null) NormalizerProcessor.volumeFor(c.sleepPrefs.choices.value.volume) else 1f
        volumeBeforePreview = null
    }

    /**
     * The active menu's temporary "allow every BPM" switch: off, songs the limit held back play again; on again, the
     * current song and the queue are checked immediately. The saved limit is never changed.
     */
    fun setSleepBpmDisabled(disabled: Boolean) {
        sleepTimer.setBpmDisabled(disabled)
        if (disabled) {
            if (pausedBySleepBpm) {
                pausedBySleepBpm = false
                player?.play()
            }
        } else {
            enforceSleepBpm()
        }
    }

    /** Whether the running session's limit rules [songId] out (false when no timer runs, or the override is on). */
    fun sleepBlocksBpm(songId: String?): Boolean = sleepTimer.blocksBpm(songId)

    /** Called by the service as an item starts: a finished song in overtime ends the session; either way, recalculate. */
    internal fun onSleepItemChanged() = sleepTimer.onItemChanged()

    /** A play, pause or seek changes when the current song ends: work the stop time out again at once. */
    internal fun onSleepPlaybackChanged() = sleepTimer.onPlaybackChanged()

    /**
     * Applies the BPM limit to what plays right now: the song itself is skipped for the next allowed one (in play order,
     * never wrapping), and when none is left the music stops instead of skipping for ever. Called when the timer starts,
     * when the restriction is switched back on, and when something starts while the limit holds.
     */
    fun enforceSleepBpm() {
        val p = player ?: return
        if (c.queue.followingHost) return
        service?.let {
            pausedBySleepBpm = it.enforceSleepBpm()
            return
        }
        val song = p.currentMediaItem?.toSong()
        if (!sleepTimer.blocksBpm(song?.id) || p.mediaItemCount == 0) {
            pausedBySleepBpm = false
            return
        }
        val next = nextSleepAllowed(p)
        if (next != C.INDEX_UNSET) {
            pausedBySleepBpm = false
            p.seekTo(next, 0)
            return
        }
        pausedBySleepBpm = true
        p.pause()
        sleepAudio.reportNoEligibleSongs(sleepTimer.maxBpm.value)
    }

    internal fun reportSleepBpmExhausted() = sleepAudio.reportNoEligibleSongs(sleepTimer.maxBpm.value)

    /** The first item after the one playing (in play order) the limit allows, or [C.INDEX_UNSET] when none is left. */
    internal fun nextSleepAllowed(p: Player): Int {
        val tl = p.currentTimeline
        var i = tl.getNextWindowIndex(p.currentMediaItemIndex, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
        var seen = 0
        while (i != C.INDEX_UNSET && seen < tl.windowCount) {
            if (!sleepTimer.blocksBpm(p.getMediaItemAt(i).toSong()?.id)) return i
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
            seen++
        }
        return C.INDEX_UNSET
    }

    /**
     * The session's clock: every tick reads the (monotonic) clock and works the fade out from it rather than stepping a
     * volume down, so a late tick lands on the right gain. At the end, what's already queued for the speaker plays out
     * silent before the listener's volume comes back; a new session started meanwhile wins.
     */
    private fun runSleepLoop() {
        sleepRestoreJob?.cancel()
        sleepJob?.cancel()
        sleepJob = scope.launch {
            while (true) {
                // The limit holds for the whole session, not only at a song change: a song whose tempo is measured late,
                // a queue edited under the current song, or one added by a car are checked here too.
                if (sleepTimer.active.value && player?.isPlaying == true) {
                    val song = player?.currentMediaItem?.toSong()
                    if (sleepTimer.blocksBpm(song?.id)) enforceSleepBpm()
                }
                val wait = sleepTimer.tick()
                if (!sleepTimer.active.value) {
                    // A chosen after-timer track takes the volume over from here; otherwise let the buffer play out silent.
                    if (afterTimerSongId == null) {
                        sleepRestoreJob = scope.launch {
                            kotlinx.coroutines.delay(400)
                            if (!sleepTimer.active.value && afterTimerSongId == null) sleepAudio.restoreVolume()
                        }
                    }
                    return@launch
                }
                kotlinx.coroutines.delay(wait)
            }
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        // The after-timer track is one item: its end is where "Loop this track" starts it over (or the special handling
        // ends). Events also pass through refresh, but this is the one moment it matters.
        if (afterTimerSongId != null && playbackState == Player.STATE_ENDED) handleAfterTimer()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {        // Output return (Settings → Playback): remember only a pause the audio output itself caused, so the same
        // Bluetooth output coming back can pick the music up; any other pause, and any play, ends the wait.
        if (playWhenReady) {
            outputReturn.played()
            rememberOutput()
        } else if (c.settings.current.behavior.resumeOnReturn) {
            outputReturn.paused(reason, android.os.SystemClock.elapsedRealtime())
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
                AudioDiagnostics.log("route", "paused because the output went away: waiting for it to come back")
                checkOutputBack()
            }
        } else outputReturn.played()
        // Paused at the end of the song for the sleep timer: the finish-current-song overtime is done (its BPM filter, if
        // any, was only for this session, so it goes back off).
        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleepEndOfSong.value) {
            sleepTimer.finish()
            return
        }
        if (playWhenReady) onSleepPlaybackChanged()
    }

    // The player may only be asked on its own thread (main). Asked from anywhere else (Compose's test clock can run a frame
    // on a worker, which made QuickPicksPlayingTest fail now and then), these give the last value read on that thread.
    @Volatile private var lastPosition = 0L
    @Volatile private var lastDuration = 0L
    @Volatile private var lastBuffered = 0L

    val position: Long get() {
        val p = player ?: return 0
        return if (android.os.Looper.myLooper() == p.applicationLooper) p.currentPosition.also { lastPosition = it } else lastPosition
    }
    val duration: Long get() {
        val p = player
        val d = when {
            p == null -> 0L
            android.os.Looper.myLooper() == p.applicationLooper -> p.duration.also { lastDuration = it }
            else -> lastDuration
        }
        return d.takeIf { it > 0 } ?: ((_current.value?.durationSec ?: 0) * 1000L)
    }
    val bufferedPosition: Long get() {
        val p = player ?: return 0
        return if (android.os.Looper.myLooper() == p.applicationLooper) p.bufferedPosition.also { lastBuffered = it } else lastBuffered
    }

    // ------------------------------------------------------------ State mirroring

    /** The queue's songs, being read off the main thread (null: none to read). */
    private var queueJob: Job? = null
    private var queueVersion = 0

    private fun refresh(rebuildQueue: Boolean = false) {
        val p = player ?: return
        val item = p.currentMediaItem
        val song = item?.toSong()
        val changed = song?.id != _current.value?.id
        val wasLive = _current.value?.isLive == true
        if (changed) _videoFailedFor.value = null
        _current.value = song
        _isPlaying.value = p.isPlaying
        _buffering.value = p.playbackState == Player.STATE_BUFFERING
        _index.value = p.currentMediaItemIndex
        _shuffle.value = p.shuffleModeEnabled
        _repeat.value = p.repeatMode
        _videoMode.value = item?.videoModeId != null
        _hasNext.value = p.hasNextMediaItem()
        _hasPrevious.value = p.hasPreviousMediaItem()
        // The queue itself changes only with its timeline, not with every play, pause or seek. Keys stay stable when
        // items move (id + occurrence), so a dragged row keeps its gesture. Read off the main thread: a Downloads queue
        // can hold thousands, and decoding them all held up the start.
        if (rebuildQueue) {
            val items = if (p.mediaItemCount == 0) emptyList() else (0 until p.mediaItemCount).map { p.getMediaItemAt(it) }
            val version = ++queueVersion
            queueJob?.cancel()
            if (items.isEmpty()) _queue.value = emptyList() else {
                queueJob = scope.launch {
                    val entries = withContext(Dispatchers.Default) {
                        val seen = HashMap<String, Int>()
                        items.mapIndexedNotNull { i, mi ->
                            val n = seen.merge(mi.mediaId, 1, Int::plus) ?: 1
                            mi.toSong()?.let { QueueEntry(i, it, "${mi.mediaId}#$n") }
                        }
                    }
                    if (version == queueVersion) _queue.value = entries
                }
            }
        }
        _upNext.value = buildList {
            val tl = p.currentTimeline
            if (tl.isEmpty || p.currentMediaItemIndex !in 0 until tl.windowCount) return@buildList
            var i = tl.getNextWindowIndex(p.currentMediaItemIndex, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
            while (i != C.INDEX_UNSET && size < tl.windowCount) { add(i); i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled) }
        }
        // Before the current song in play order, oldest first (#68: the queue only went forward).
        _played.value = buildList {
            val tl = p.currentTimeline
            if (tl.isEmpty || p.currentMediaItemIndex !in 0 until tl.windowCount) return@buildList
            var i = tl.getPreviousWindowIndex(p.currentMediaItemIndex, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
            while (i != C.INDEX_UNSET && size < tl.windowCount) { add(i); i = tl.getPreviousWindowIndex(i, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled) }
        }.asReversed()
        if (song != null && (changed || song.isLive != wasLive)) checkVideo(song)
        updateLyricTiming()
        // A seek, a pause or a queue change moves where the current song ends: the running timer works its stop out again.
        if (sleepTimer.active.value) sleepTimer.tick()
        if (afterTimerSongId != null) handleAfterTimer()
    }

    /** Called when a music video failed to load; the button stays usable so it can be tried again. */
    internal fun onVideoFailed(songId: String) {
        _videoLoading.value = false
        _videoFailedFor.value = songId
    }

    private val _videoFailedFor = MutableStateFlow<String?>(null)
    /**
     * The song (playing now) whose music video didn't load, so its song plays instead; null once another song plays.
     * The TV's video screen says so rather than showing only the cover.
     */
    val videoFailedFor: StateFlow<String?> = _videoFailedFor

    private val _videoOffOnData = MutableStateFlow(false)
    /** #281: the song playing has no video for now, because videos are off on mobile data ([VideoOnData]). */
    val videoOffOnData: StateFlow<Boolean> = _videoOffOnData

    /** #281: the network or the setting changed, so the Video button may have too. */
    internal fun recheckVideo() {
        _current.value?.let { checkVideo(it) }
    }

    /**
     * Finds the song's music video as it starts (and warms it up), so the
     * button is ready. Greyed out only when there definitely isn't a video;
     * if YouTube couldn't be reached, it stays tappable to try again.
     */
    private fun checkVideo(song: Song) {
        _videoLoading.value = false
        val saved = c.downloads.downloadedVideoId(song.id)
        // #281: nothing's looked up or warmed up either: that's data too.
        _videoOffOnData.value = VideoOnData.stops(VideoOnData.off(c), saved != null)
        if (_videoOffOnData.value) { _videoAvailable.value = false; return }
        // A live stream carries its own picture (the same HLS stream), so there's nothing to look up.
        if (song.isLive) { _videoAvailable.value = true; return }
        if (saved == null && song.id in c.queue.noVideo) { _videoAvailable.value = false; return }
        val known = if (song.isVideo) song.id else saved ?: c.queue.counterparts[song.id]
        _videoAvailable.value = known != null
        val s = service ?: return
        if (known != null) { s.prefetchVideo(song, known); return }
        scope.launch {
            val found = s.videoIdFor(song)
            if (_current.value?.id != song.id) return@launch
            _videoAvailable.value = found.id != null || found.lookupFailed
            found.id?.let { s.prefetchVideo(song, it) }
        }
    }

    private val _jumps = MutableStateFlow(0)
    /** Moves on with every seek or jump of the playhead (the web remote sends its new place at once). */
    val jumps: StateFlow<Int> = _jumps

    override fun onEvents(player: Player, events: Player.Events) {
        refresh()
        if (events.contains(Player.EVENT_POSITION_DISCONTINUITY)) _jumps.value++
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        refresh(rebuildQueue = true)
    }
}
