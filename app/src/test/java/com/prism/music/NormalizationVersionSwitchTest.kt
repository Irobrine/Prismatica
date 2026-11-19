package com.prism.music

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.prefs.SettingsRepository
import com.prism.music.data.stream.StreamResolver
import com.prism.music.playback.Loudness
import com.prism.music.playback.LoudnessCatchUp
import com.prism.music.playback.MixTempo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The complaint behind 1.1.3's rebuild: normalization was inconsistent around a version picked for a song and around music
 * videos. A song's own recording, the version picked for it and its video are three masters with three loudnesses; each
 * now plays at its own figure ([Loudness.formId]), and switching between them needs no forgetting (a figure belongs to
 * the recording it was measured from) and no waiting for the next play (a figure landing seconds in still applies once,
 * [LoudnessCatchUp]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NormalizationVersionSwitchTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val target = -14f

    /** The figures known now, keyed by the recording heard; tests change them under the store, as a scan does. */
    private val measured = mutableMapOf("song" to -9f, "cover" to -16f)
    private val current = mutableSetOf("song", "cover")
    private val picks = mutableMapOf("song" to "cover")

    private fun store(): Loudness = Loudness(
        context.getSharedPreferences("loudness_switch", Context.MODE_PRIVATE).also { it.edit().clear().commit() },
        measuredOf = { measured[it] },
        peakOf = { null },
        measuredCurrentOf = { it in current },
        pickedOf = { picks[it] },
        fileMatchedId = { null },
        soundFrom = emptyMap(),
    )

    /** A loud, a quieter picked version, and the song's own again: the version's own level each time, no scan in between. */
    @Test fun aPickedVersionPlaysAtItsOwnLevelAndTheSongAtItsOwnAgain() {
        val l = store()
        val picked = l.figure("song", null, target)
        assertEquals("the version's own -16 LUFS: lifted 2 dB, not the song's -5", 2f, picked.gainDb, 0.01f)
        assertEquals(Loudness.Source.MEASURED, picked.source)
        picks.clear() // "Play the original"
        val own = l.figure("song", null, target)
        assertEquals("the song's own -9 LUFS again: brought down 5", -5f, own.gainDb, 0.01f)
        assertEquals(Loudness.Source.MEASURED, own.source)
    }

    /**
     * A picked version's figures survive the pick changing, and so no re-measure is needed when the song is heard as
     * itself again (1.1.3: [MixTempo.forget] no longer clears them).
     */
    @Test fun forgettingASongsTempoKeepsItsFiguresForTheNextTime() {
        val settings = SettingsRepository(context, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val http = OkHttpClient()
        val mix = MixTempo(context, http, StreamResolver(context, http, settings, InnerTube(http, { settings.current })))
        val prefs = context.getSharedPreferences("song_loudness", Context.MODE_PRIVATE)
        prefs.edit().putFloat("song", -9f).putFloat("cover", -16f).commit()
        context.getSharedPreferences("song_loudness_version", Context.MODE_PRIVATE)
            .edit().putInt("song", MixTempo.LUFS_VERSION).putInt("cover", MixTempo.LUFS_VERSION).commit()
        context.getSharedPreferences("mix_tempo", Context.MODE_PRIVATE)
            .edit().putString("song", "none").commit()

        mix.forget("song")
        assertEquals(-9f, mix.loudness("song")!!, 0f)
        assertEquals(-16f, mix.loudness("cover")!!, 0f)
        assertTrue(mix.loudnessCurrent("song"))
        assertNull("its tempo was the recording's that played then, and goes", mix.cached("song"))
    }

    /**
     * A music video starts on its song's figure (it's the same recording's other master, and it was just switched to), and
     * its own lands seconds later: the catch-up applies it once, so the video doesn't play at the song's level through the
     * whole watch.
     */
    @Test fun aVideosOwnFigureTakesOverOnceWhenItLands() {
        val l = store()
        picks.clear() // The video is the song's video, not a picked version's
        // The song's own recording says what the video's other master is near, until the video's own figure lands.
        val fallback = l.figure("song", "vid", target)
        assertEquals(Loudness.Source.MEASURED_FALLBACK, fallback.source)
        assertEquals(-5f, fallback.gainDb, 0.01f)

        val catchUp = LoudnessCatchUp()
        catchUp.start("vid", atMs = 0)
        catchUp.note("vid", fallback)
        // Measured while the video plays (a scan of its own audio), 6 s in.
        measured["vid"] = -16f
        current += "vid"
        val own = l.figure("song", "vid", target)
        assertEquals(Loudness.Source.MEASURED, own.source)
        assertEquals(2f, own.gainDb, 0.01f)
        assertTrue("7 dB better, in the window", catchUp.wants("vid", own, fallback.gainDb, 6_000))
        catchUp.took("vid", own)
        assertFalse("and not again this listen", catchUp.wants("vid", own.copy(gainDb = -2f), own.gainDb, 9_000))
        assertNotNull(l.measured("vid"))
    }

    /** The song itself is the first form still: its own figure, and never its video's when the video isn't watched. */
    @Test fun theSongWithoutItsVideoIsTheSongsOwnRecording() {
        val l = store()
        picks.clear()
        measured["vid"] = 3f
        current += "vid"
        val f = l.figure("song", null, target)
        assertEquals(Loudness.Source.MEASURED, f.source)
        assertEquals(-5f, f.gainDb, 0.01f)
    }
}
