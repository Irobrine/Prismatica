package com.prism.music.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Settings → Sound & quality's "Measure songs now" (1.1.3): a set of the listener's songs walked through the whole-song
 * scan Normalize measures with ([com.prism.music.playback.MixTempo]), one song after another — two at once where the
 * phone can spare them ([width]) — so that they play at their own level from the first play instead of the first time
 * each is heard. Unlike a smart playlist's "Measure now"
 * ([SmartMeasure]) this is the listener's own doing, on the songs they chose, so nothing is capped.
 *
 * Each song is measured as the recording that actually plays ([formOf]: the version picked for it, a file on the phone),
 * the id its figure is filed under ([com.prism.music.playback.Loudness.formId]), and [sourceSongId] is the song it plays
 * for: where the scan may find that recording's audio on the phone when the form's own copy isn't here. Songs already
 * measured with the current scan ([measured]) aren't walked again, so [pending] is the real remaining work, which is
 * what the page counts before a tap.
 *
 * [progress]: done of all, while a walk runs and after one is stopped part-way — [running] says which. A stop keeps the
 * count ([stop]), and [resume] carries on with the same set from where it stopped: what's been measured isn't walked
 * again, so a second tap never starts the work over. Null when no walk is under way, or when the last one measured
 * everything.
 */
class MeasureSongs(
    private val scope: CoroutineScope,
    /** The scan itself: the form to measure, the song it plays for (when that's another id), and whether to use the network. */
    private val measure: suspend (formId: String, sourceSongId: String?, network: Boolean) -> Unit,
    /** Whether the form's figure is from the current scan ([com.prism.music.playback.MixTempo.loudnessCurrent]). */
    private val measured: (formId: String) -> Boolean,
    /** The recording a song plays as ([com.prism.music.playback.Loudness.formId], with no music video armed). */
    private val formOf: (songId: String) -> String,
    /**
     * How many songs the walk may measure at once: 1, or 2 where the phone can spare the cores
     * ([com.prism.music.playback.MixTempo.mayScanTwice]). Read when a walk starts; a phone that comes under load
     * part-way through it still gets one scan at a time, as the scan itself takes the cores it may have.
     */
    private val width: () -> Int = { 1 },
    /** Whether the network is unmetered now (Wi-Fi): songs not on the phone may then be fetched to scan them. */
    private val unmetered: () -> Boolean,
) {
    val progress = MutableStateFlow<Pair<Int, Int>?>(null)

    /** Whether a walk is moving [progress] now: a stopped one keeps its count still, for [resume] to carry on. */
    val running = MutableStateFlow(false)

    private var job: Job? = null
    /** Which walk is the current one: an older walk's last step and end are not its to show ([start]). */
    private var walk = 0
    /** The set the last walk was of, and the network it was given: a stop keeps both, so [resume] carries on with them. */
    private var walkSet: List<Pair<String, String>> = emptyList()
    private var network = true

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
     * Walks [pairs] ([pending]'s, as form to song) on [network] (whether songs not on the phone are fetched), one song at
     * a time (or two, where [width] says the phone can spare them): the count the page shows must be of one walk.
     *
     * A walk already running is stopped first, so two taps never leave two walks interleaving their counts — and a set
     * started this way sets aside whatever a stopped one was part-way through.
     */
    fun start(pairs: List<Pair<String, String>>, network: Boolean) {
        job?.cancel()
        walkSet = pairs
        this.network = network
        if (pairs.isEmpty()) {
            progress.value = null
            running.value = false
            return
        }
        walk(pairs, done = 0)
    }

    /**
     * Carries on with the set a stop left part-measured ([stop]): only what isn't measured is walked, and the count runs
     * on from where it stopped instead of starting again. Nothing left — everything measured meanwhile, by a play or
     * another walk — means the row is done.
     */
    fun resume() {
        if (running.value) return
        val left = walkSet.filter { !measured(it.first) }
        if (left.isEmpty()) {
            progress.value = null
            return
        }
        walk(left, done = walkSet.size - left.size)
    }

    private fun walk(items: List<Pair<String, String>>, done: Int) {
        val mine = ++walk
        running.value = true
        job = scope.launch(Dispatchers.IO) {
            var finished = false
            // This walk's to show, only while it is the current one: a newer start cancels it, and the count is then the
            // newer walk's (this one's last step landing on it would take the newer one's place). Two scans finishing at
            // once can land in either order, so the count only ever moves forward.
            val shown = Any()
            var at = -1
            fun note(step: Int) = synchronized(shown) {
                if (mine != walk || step <= at) return
                at = step
                progress.value = step to walkSet.size
            }
            note(done)
            try {
                // Two songs at once where the phone may spare them ([MixTempo.mayScanTwice]): one decodes while the other
                // is analysed, and the walk's own scans share the processor instead of queueing on one. Their order is
                // still the set's (each worker takes the next song when it's free), and the count is still songs walked:
                // one is counted only when its scan has finished.
                val counted = java.util.concurrent.atomic.AtomicInteger()
                val next = java.util.concurrent.atomic.AtomicInteger()
                val workers = width().coerceIn(1, items.size.coerceAtLeast(1))
                coroutineScope {
                    repeat(workers) {
                        launch {
                            while (true) {
                                // Stopped (or taken over) part-way: nothing more is measured.
                                if (mine != walk || !isActive) return@launch
                                val i = next.getAndIncrement()
                                if (i >= items.size) return@launch
                                val (form, song) = items[i]
                                runCatching { measure(form, song.takeIf { it != form }, network) }
                                    .exceptionOrNull()?.let { if (it is CancellationException) throw it }
                                // Stopped during it: that song wasn't measured, and a [resume] takes it up again — no step
                                // is counted for it, so the count never runs ahead of the work done.
                                if (mine != walk || !isActive) return@launch
                                note(done + counted.incrementAndGet())
                            }
                        }
                    }
                }
                finished = true
            } finally {
                if (mine == walk) {
                    running.value = false
                    // Everything walked and measured: the row asks for a set again. A stop leaves the count where it got
                    // to, for [resume] to carry on from.
                    if (finished) progress.value = null
                }
            }
        }
    }

    /** Stops the walk where it is: nothing more is measured, and its count is kept for [resume]. */
    fun stop() { job?.cancel() }
}
