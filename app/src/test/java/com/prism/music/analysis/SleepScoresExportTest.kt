package com.prism.music.analysis

import com.prism.music.AppContainer
import com.prism.music.closeAfterTest
import com.prism.music.data.db.toEntity
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The CSV for tuning the score: every analysed song with its score, each factor and weight, the figures and its tags;
 * and the flags that find the cases the score most likely misreads (an energetic sound scoring calm).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SleepScoresExportTest {
    private val made = lazy { AppContainer(RuntimeEnvironment.getApplication()) }
    private val c by made

    @After fun tearDown() = made.closeAfterTest()

    private val calm = SongFeatures(
        seconds = 200f, lufs = -20f, lra = 5f, maxMomentary = -14f, bpm = 70f, pulseClarity = 0.2f, onsetRate = 1.2f,
        percussive = 0.15f, centroidHz = 800f, rolloffHz = 1600f, flatness = 0.02f, segLoud = List(20) { -20f }, segBright = List(20) { 800f },
    )

    @Test fun everyAnalysedSongIsARowItsNameQuoted() = runBlocking {
        val song = Song("${Song.LOCAL_PREFIX}9100", "Title, with comma", artists = listOf(ArtistRef("Its artist")))
        c.db.songs().upsertAll(listOf(song.toEntity()))
        c.analysis.record(song.id, null, calm, emptyList())
        val lines = SleepScoresExport.csv(c).trim().lines()
        assertEquals(SleepScoresExport.HEADER, lines[0])
        assertEquals(2, lines.size)
        assertTrue(lines[1], lines[1].contains("\"Title, with comma\""))
        assertTrue(lines[1], lines[1].contains("Its artist"))
        // The score, and every factor with its calmness and weight, are in the row.
        assertTrue(lines[1], lines[1].contains(",${c.analysis.sleepScore(song.id)},"))
        assertTrue(lines[1], lines[1].contains("ENERGY 0."))
        assertTrue(lines[1], lines[1].contains("BRIGHTNESS 0."))
        // The figures behind them too, so a wrong score can be traced.
        assertTrue(lines[1], lines[1].contains("-20.0"))
    }

    @Test fun flagsFindAnEnergeticSoundScoringCalm() {
        val r = SleepResult(score = 70, base = 70, parts = emptyList(), capped = false, personal = 0, vote = 0, completeness = 1f)
        val harsh = calm.copy(flatness = 0.12f, percussive = 0.6f, onsetRate = 5f, pulseClarity = 0.8f, bpm = 140f, worstRise = 8f)
        val f = SleepScoresExport.flags(r, harsh)
        for (flag in listOf("harsh", "percussive", "busy", "rise", "fast-beat")) assertTrue(flag, f.split(';').contains(flag))
        // A calm sound scoring calm isn't flagged, and a low score from a genuinely lively sound isn't either.
        assertEquals("", SleepScoresExport.flags(r, calm))
        assertEquals("", SleepScoresExport.flags(r.copy(score = 20), harsh.copy(onsetRate = 5f, percussive = 0.6f)))
    }

    @Test fun theCatalogsGenreIsShownBesideTheAudiosAndDisagreementIsFlagged() {
        // The sample that started this: a catalog filing a metal track as classical. The score never sees the catalog; the
        // report shows both and flags the disagreement so the label can be reviewed.
        assertTrue(SleepScoresExport.HEADER.contains("catalogGenre,audioGenre"))
        assertEquals("genre-mismatch;", SleepScoresExport.genreFlag("Classical", "Metal"))
        assertEquals("", SleepScoresExport.genreFlag("Metal", "Metal"))
        assertEquals("", SleepScoresExport.genreFlag("Classical", null))
        assertEquals("", SleepScoresExport.genreFlag(null, "Metal"))
        assertEquals("", SleepScoresExport.genreFlag("Classical", "classical"))
    }

    @Test fun aCellWithACommaOrAQuoteIsQuoted() {
        assertEquals("plain", SleepScoresExport.quote("plain"))
        assertEquals("\"a,b\"", SleepScoresExport.quote("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", SleepScoresExport.quote("say \"hi\""))
        assertEquals("", SleepScoresExport.quote(null))
    }
}
