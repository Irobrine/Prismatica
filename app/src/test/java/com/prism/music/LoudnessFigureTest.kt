package com.prism.music

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.prism.music.playback.CATCHUP_GLIDE_SEC
import com.prism.music.playback.CATCHUP_MS
import com.prism.music.playback.Loudness
import com.prism.music.playback.LoudnessCatchUp
import com.prism.music.playback.NormalizerProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.1.3: one place knows what a song's sound is ([Loudness.formId]: its own recording, its music video, the version picked
 * for it, the file picked for it) and which loudness figure applies to it — and when a better figure that lands a few
 * seconds in may still set the volume ([LoudnessCatchUp]). Normalize's inconsistencies came from both: a video's audio
 * played at its song's figure, a picked version's measurement was filed under the song, and a scan's own figure landed
 * too late to use, so a first play differed from every later one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LoudnessFigureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A store over maps the test can change under it (a figure landing while a form plays). */
    private fun loudness(
        measured: MutableMap<String, Float> = mutableMapOf(),
        current: Set<String> = measured.keys.toSet(),
        peaks: Map<String, Float> = emptyMap(),
        picks: Map<String, String> = emptyMap(),
        soundFrom: Map<String, String> = emptyMap(),
        youtube: Map<String, Float> = emptyMap(),
        name: String = "loudness_figures",
    ): Loudness {
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        youtube.forEach { (id, db) -> prefs.edit().putFloat(id, db).apply() }
        return Loudness(
            prefs,
            measuredOf = { measured[it] },
            peakOf = { peaks[it] },
            measuredCurrentOf = { it in current },
            pickedOf = { picks[it] },
            soundFrom = soundFrom,
        )
    }

    // ---------------------------------------------------------------- the form heard

    @Test fun aSongIsItsOwnForm() {
        assertEquals("song", loudness().formId("song"))
    }

    @Test fun itsVideoIsTheFormWhileWatchingIt() {
        assertEquals("vid", loudness().formId("song", videoId = "vid"))
    }

    @Test fun aPickedVersionIsTheForm() {
        assertEquals("cover", loudness(picks = mapOf("song" to "cover")).formId("song"))
    }

    @Test fun aFilePickedForASongIsTheForm() {
        assertEquals("local:abc", loudness(picks = mapOf("song" to "local:abc")).formId("song"))
    }

    /**
     * 1.2.1: a song that is itself a file on the phone keeps its own form whatever was picked for it. The player plays
     * the file ([PlaybackService.losslessFileFor] returns it before any pick), so following the pick made the file play
     * at the picked stream's level — a local file that was louder or quieter than its own measurement.
     */
    @Test fun aFileOnThePhoneIgnoresAVersionPickedForIt() {
        assertEquals("local:abc", loudness(picks = mapOf("local:abc" to "cover")).formId("local:abc"))
        // A streamed song's pick is still the recording heard, a file on the phone's included.
        assertEquals("cover", loudness(picks = mapOf("song" to "cover")).formId("song"))
        assertEquals("local:abc", loudness(picks = mapOf("song" to "local:abc")).formId("song"))
    }

    /**
     * A file picked for a song and the song's own recording never take each other's figures (1.1.3): the file is
     * another master, so it plays at its own once measured and as it is until then — never at the song's — and back
     * on its own recording the song is at its own level again, not the file's.
     */
    @Test fun aFilePickAndTheSongsOwnFiguresNeverLeakEitherWay() {
        val measured = mutableMapOf("song" to -9f)
        val current = mutableSetOf("song")
        val picks = mutableMapOf("song" to "local:abc")
        val l = loudness(measured = measured, current = current, picks = picks)
        // Until the file is measured it plays as it is: not at the song's own -9 LUFS figure.
        assertEquals(Loudness.Source.NONE, l.figure("song", null, -14f).source)
        // The file measured (its scan reads the file itself): the file's own level.
        measured["local:abc"] = -16f
        current += "local:abc"
        val file = l.figure("song", null, -14f)
        assertEquals(Loudness.Source.MEASURED, file.source)
        assertEquals("lifted 2 dB for the file's -16 LUFS, not the song's -5", 2f, file.gainDb, 0.01f)
        // And un-picked, the song's own recording is at its own level again, not the file's.
        picks.clear()
        assertEquals("brought down 5 for its own -9 LUFS, not the file's +2", -5f, l.figure("song", null, -14f).gainDb, 0.01f)
    }

    @Test fun aVideoWhoseSoundPlaysInASongsPlaceIsTheForm() {
        // #93: the song wouldn't play here, so its video's sound does.
        assertEquals("vid", loudness(soundFrom = mapOf("song" to "vid")).formId("song"))
    }

    @Test fun aVideoWinsOverAPick() {
        // Watching a song's video plays the video's own audio, not the version picked for the song.
        assertEquals("vid", loudness(picks = mapOf("song" to "cover")).formId("song", videoId = "vid"))
    }

    // ---------------------------------------------------------------- the figure

    private val target = -14f

    @Test fun theFormsOwnMeasurementWins() {
        val f = loudness(measured = mutableMapOf("a" to -9f)).figure("a", null, target)
        assertEquals(Loudness.Source.MEASURED, f.source)
        assertEquals("a -9 LUFS song is brought down 5 dB", -5f, f.gainDb, 0.01f)
        assertEquals(-9f, f.level!!, 0.01f)
    }

    @Test fun aQuietFormsMeasurementIsLiftedOnlyAsFarAsItsPeaksAllow() {
        val f = loudness(measured = mutableMapOf("a" to -30f), peaks = mapOf("a" to -6f)).figure("a", null, target)
        assertEquals("16 dB wanted, 11 dB of room over its peaks", 11f, f.gainDb, 0.01f)
    }

    @Test fun youtubeFiguresConvertFromTheirReference() {
        // YouTube's loudnessDb is dB from its own -14 LUFS: 4 dB louder than that is -10 LUFS, brought down 4.
        val f = loudness(youtube = mapOf("a" to 4f)).figure("a", null, target)
        assertEquals(Loudness.Source.YOUTUBE, f.source)
        assertEquals(-4f, f.gainDb, 0.01f)
        assertEquals(4f, f.level!!, 0.01f)
    }

    @Test fun nothingKnownIsPlayedAsItIs() {
        val f = loudness().figure("a", null, target)
        assertEquals(Loudness.Source.NONE, f.source)
        assertEquals(0f, f.gainDb, 0f)
    }

    @Test fun aMeasurementFromBeforeTheRebuildIsntTrusted() {
        // Filed under the song's id whichever recording was scanned then: it may describe the version picked at the time.
        val f = loudness(measured = mutableMapOf("a" to -9f), current = emptySet())
            .figure("a", null, target)
        assertEquals(Loudness.Source.NONE, f.source)
    }

    @Test fun aVideoUsesItsSongsFigureUntilItsOwnIsIn() {
        val f = loudness(measured = mutableMapOf("song" to -9f)).figure("song", "vid", target)
        assertEquals(Loudness.Source.MEASURED_FALLBACK, f.source)
        assertEquals(-5f, f.gainDb, 0.01f)
    }

    @Test fun aVideoUsesItsSongsYoutubeFigureToo() {
        val f = loudness(youtube = mapOf("song" to 4f)).figure("song", "vid", target)
        assertEquals(Loudness.Source.YOUTUBE_FALLBACK, f.source)
        assertEquals(-4f, f.gainDb, 0.01f)
    }

    @Test fun aVideosOwnFigureWinsOverItsSongs() {
        val f = loudness(measured = mutableMapOf("song" to -9f, "vid" to -16f)).figure("song", "vid", target)
        assertEquals(Loudness.Source.MEASURED, f.source)
        assertEquals("a quieter video master is lifted 2 dB", 2f, f.gainDb, 0.01f)
    }

    @Test fun aPickedVersionGetsNoFallbackToTheSongsFigures() {
        // A cover or another master is another recording: its song's loudness says nothing about it, so it plays as it is
        // until its own figure lands (the fetch and scan are what make that quick).
        val l = loudness(
            measured = mutableMapOf("song" to -9f),
            youtube = mapOf("song" to 4f),
            picks = mapOf("song" to "cover"),
        )
        val f = l.figure("song", null, target)
        assertEquals(Loudness.Source.NONE, f.source)
        assertEquals(0f, f.gainDb, 0f)
        // Its own figures, once there, are what it plays at.
        l.rememberYoutube("cover", 1f)
        val own = l.figure("song", null, target)
        assertEquals(Loudness.Source.YOUTUBE, own.source)
        assertEquals(-1f, own.gainDb, 0.01f)
    }

    // ---------------------------------------------------------------- the catch-up window

    private val none = Loudness.Figure(0f, Loudness.Source.NONE)
    private val youtube = Loudness.Figure(0f, Loudness.Source.YOUTUBE)
    private val measured = Loudness.Figure(-3f, Loudness.Source.MEASURED)

    @Test fun aBetterFigureLandsOnceWithinTheWindow() {
        val c = LoudnessCatchUp()
        c.start("song", atMs = 1_000)
        c.note("song", youtube)
        assertTrue("a measured figure 3 dB away, 2 s in", c.wants("song", measured, 0f, 3_000))
        c.took("song", measured)
        assertFalse("one per listen", c.wants("song", Loudness.Figure(-8f, Loudness.Source.MEASURED), -3f, 5_000))
    }

    @Test fun onlyABetterSourceTakesOver() {
        val c = LoudnessCatchUp()
        c.start("song", atMs = 0)
        c.note("song", measured)
        assertFalse("YouTube's after measured (a downgrade)", c.wants("song", youtube, -3f, 3_000))
        assertFalse("the same again", c.wants("song", Loudness.Figure(-9f, Loudness.Source.MEASURED), -3f, 3_000))
    }

    @Test fun aFallbacksOwnFigureTakesOver() {
        val c = LoudnessCatchUp()
        c.start("video", atMs = 0)
        val song = Loudness.Figure(-5f, Loudness.Source.MEASURED_FALLBACK)
        c.note("video", song)
        assertTrue("the video's own measured, 7 dB away", c.wants("video", measured.copy(gainDb = -5f + 7f), -5f, 4_000))
    }

    @Test fun aChangeTooSmallToHearIsLeftAlone() {
        val c = LoudnessCatchUp()
        c.start("song", atMs = 0)
        c.note("song", youtube)
        assertFalse(c.wants("song", Loudness.Figure(-0.4f, Loudness.Source.MEASURED), 0f, 3_000))
        assertTrue(c.wants("song", Loudness.Figure(-0.5f, Loudness.Source.MEASURED), 0f, 3_000))
    }

    @Test fun theWindowIsTheFirstTwentySeconds() {
        val c = LoudnessCatchUp()
        c.start("song", atMs = 1_000)
        assertTrue(c.wants("song", measured, 0f, 1_000 + CATCHUP_MS - 1))
        c.start("song", atMs = 1_000)
        assertFalse(c.wants("song", measured, 0f, 1_000 + CATCHUP_MS))
    }

    @Test fun anotherFormsFigureDoesntTakeOver() {
        // A scan of the song landing after its video started is the song's, not the video's ([Loudness.figure] may use it
        // as a fallback, but the video's own window is its own).
        val c = LoudnessCatchUp()
        c.start("video", atMs = 0)
        assertFalse(c.wants("song", measured, 0f, 3_000))
        assertFalse(c.wants(null, measured, 0f, 3_000))
    }

    @Test fun aNewFormOpensTheWindowAgain() {
        val c = LoudnessCatchUp()
        c.start("song", atMs = 0)
        c.note("song", none)
        c.took("song", youtube)
        c.start("cover", atMs = 30_000)
        assertTrue("the version picked mid-song is its own listen", c.wants("cover", measured, 0f, 32_000))
    }

    /** The gain law is unchanged: the catch-up only decides when a figure may still be applied. */
    @Test fun theCatchUpGlideIsSlowerThanASettles() {
        assertTrue(CATCHUP_GLIDE_SEC > 0.1f)
        assertEquals(6f, NormalizerProcessor.gainFor(-14f, -30f, null), 0.001f)
    }
}
