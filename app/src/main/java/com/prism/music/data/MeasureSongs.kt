package com.prism.music.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Settings → Sound & quality's "Measure songs now" (1.1.3): a set of the listener's songs walked through the whole-song
 * scan Normalize measures with ([com.prism.music.playback.MixTempo]), one song after another, so that they play at their
 * own level from the first play instead of the first time each is heard. Unlike a smart playlist's "Measure now"
 * ([SmartMeasure]) this is the listener's own doing, on the songs they chose, so nothing is capped.
 *
 * Each song is measured as the recording that actually plays ([formOf]: the version picked for it, a file on the phone),
 * the id its figure is filed under ([com.prism.music.playback.Loudness.formId]), and [sourceSongId] is the song it plays
 * for: where the scan may find that recording's audio on the phone when the form's own copy isn't here. Songs already
 * measured with the current scan ([measured]) aren't walked again, so [pending] is the real remaining work, which is
 * what the page counts before a tap.
 *
 * [progress]: done of all, while it runs; null when it isn't. A walk stopped part-way just leaves it null.
 */
class MeasureSongs(
    private val scope: CoroutineScope,
    /** The scan itself: the form to measure, the song it plays for (when that's another id), and whether to use the network. */
    private val measure: suspend (formId: String, sourceSongId: String?, network: Boolean) -> Unit,
    /** Whether the form's figure is from the current scan ([com.prism.music.playback.MixTempo.loudnessCurrent]). */
    private val measured: (formId: String) -> Boolean,
    /** The recording a song plays as ([com.prism.music.playback.Loudness.formId], with no music video armed). */
    private val formOf: (songId: String) -> String,
    /** Whether the network is unmetered now (Wi-Fi): songs not on the phone may then be fetched to scan them. */
    private val unmetered: () -> Boolean,
) {
    val progress = MutableStateFlow<Pair<Int, Int>?>(null)
    private var job: Job? = null
    /** Which walk is the current one: an older walk's last step and end are not its to show ([start]). */
    private var walk = 0

    /**
     * Whether a walk now may fetch songs not on the phone from the network: an unmetered network, or [cellular] (the
     * listener's "Measure songs on mobile data", shared with the scan's own queue) allowing mobile data.
     */
    fun network(cellular: Boolean): Boolean = unmetered() || cellular

    /**
     * The pairs a walk over [songIds] would measure: each song's [formOf] with the song it plays for, once per form (two
     * songs that play as one recording are one scan of it), leaving out what's measured already ([measured]).
     */
    fun pending(songIds: List<String>): List<Pair<String, String>> =
        songIds.map { formOf(it) to it }.distinctBy { it.first }.filter { !measured(it.first) }

    /**
     * Walks [pairs] ([pending]'s, as form to song) on [network] (whether songs not on the phone are fetched), one at a
     * time: the scan has one song at a time anyway, and the count the page shows must be of one walk.
     *
     * A walk already running is stopped first, so two taps never leave two walks interleaving their counts.
     */
    fun start(pairs: List<Pair<String, String>>, network: Boolean) {
        job?.cancel()
        if (pairs.isEmpty()) return
        val mine = ++walk
        job = scope.launch(Dispatchers.IO) {
            // This walk's to show, only while it is the current one: a newer start cancels it, and the count is then the
            // newer walk's (this one's last step landing on it would take the newer one's place).
            fun note(done: Int?) { if (mine == walk) progress.value = done?.let { it to pairs.size } }
            note(0)
            try {
                pairs.forEachIndexed { i, (form, song) ->
                    // Stopped (or taken over) part-way: nothing more is measured.
                    if (mine != walk || !isActive) return@launch
                    runCatching { measure(form, song.takeIf { it != form }, network) }
                    note(i + 1)
                }
            } finally { note(null) }
        }
    }

    fun stop() { job?.cancel() }
}
