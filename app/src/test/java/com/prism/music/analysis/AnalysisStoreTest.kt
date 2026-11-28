package com.prism.music.analysis

import androidx.room.Room
import com.prism.music.data.ModelStore
import com.prism.music.data.db.PrismDatabase
import com.prism.music.data.prefs.AnalysisPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The analysis store over a real (in-memory) database: what a scan records, what the tagger adds, what survives
 * re-analysis (the listener's tag decisions), the sleep score with votes, and the listening signals.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AnalysisStoreTest {
    private lateinit var db: PrismDatabase
    private lateinit var store: AnalysisStore
    private var prefs = AnalysisPrefs(tagger = true)
    /** The level the player brings songs to (the Normalize target), or null when it doesn't: part of the weights. */
    private var target: Float? = -14f
    private var taggerRuns = 0

    /** A tagger that hears piano, a little strings, and singing in the second half of the test. */
    private var heard = FloatArray(YamnetTagger.CLASSES).also { it[148] = 0.8f; it[185] = 0.15f }

    private val fake = object : AudioTagger {
        override val id = YamnetTagger.ID
        override val version = YamnetTagger.VERSION
        override fun run(windows: List<FloatArray>, step: () -> Unit): TaggerOutput {
            taggerRuns++
            return TaggerOutput(heard.copyOf(), FloatArray(1024) { (it % 7).toFloat() }, 10)
        }
        override fun close() {}
    }

    private val features = SongFeatures(
        seconds = 200f, lufs = -20f, lra = 5f, maxMomentary = -14f, bpm = 70f, pulseClarity = 0.2f, onsetRate = 1.2f,
        percussive = 0.15f, centroidHz = 800f, rolloffHz = 1600f, segLoud = List(20) { -20f }, segBright = List(20) { 800f },
    )

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(app, PrismDatabase::class.java).allowMainThreadQueries().build()
        // The model and every engine build "on the phone" (only their ready marks: the fake tagger needs no files).
        ModelStore.Model.entries.forEach { File(app.filesDir, "models/${it.id}").apply { mkdirs() }.resolve(".ready").writeText("x") }
        store = AnalysisStore(db.analysis(), ModelStore(app, OkHttpClient()), { prefs }, CoroutineScope(SupervisorJob() + Dispatchers.IO), { fake }, { target })
        runBlocking { store.load() }
    }

    @After fun tearDown() {
        db.close()
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "models").deleteRecursively()
    }

    private val window = listOf(FloatArray(16_000 * 5))

    @Test fun aRecordedSongNeedsNoMoreAndHasAScore() = runBlocking {
        assertTrue(store.needs("a"))
        store.record("a", null, features, window)
        assertFalse(store.needs("a"))
        assertTrue(store.has("a"))
        val score = store.sleepScore("a")
        assertNotNull(score)
        assertTrue("calm piano scores high: $score", score!! >= 70)
        assertEquals(1, store.count.value)
        val row = db.analysis().get("a")!!
        assertEquals(Descriptors.VERSION, row.algorithmVersion)
        assertEquals(YamnetTagger.ID, row.taggerId)
        assertEquals(1024, row.embedding!!.size)
        assertEquals(YamnetTagger.CLASSES, row.scores!!.size)
        assertEquals(1, taggerRuns)
    }

    @Test fun withoutTheTaggerASongIsAnalysedAgainOnceItIsOn() = runBlocking {
        prefs = AnalysisPrefs(tagger = false)
        store.record("b", null, features, window)
        assertEquals(0, taggerRuns)
        assertFalse(store.needs("b"))
        assertTrue(db.analysis().tags("b").isEmpty())
        prefs = AnalysisPrefs(tagger = true)
        assertTrue("the tagger hasn't heard it", store.needs("b"))
    }

    @Test fun theListenersTagDecisionsOutliveReanalysis() = runBlocking {
        store.record("c", null, features, window)
        val tags = db.analysis().tags("c").associateBy { it.tag }
        assertEquals(TagStatus.AUTO.ordinal, tags.getValue("instrument:piano").status)
        assertEquals(TagStatus.REVIEW.ordinal, tags.getValue("instrument:strings").status)
        store.setTagStatus("c", "instrument:strings", TagStatus.ACCEPTED)
        store.setTagStatus("c", "instrument:piano", TagStatus.REJECTED)
        // Analysed again (a new tagger version, say), now also hearing singing.
        heard[24] = 0.6f
        store.record("c", null, features, window)
        val again = db.analysis().tags("c").associateBy { it.tag }
        assertEquals(TagStatus.REJECTED.ordinal, again.getValue("instrument:piano").status)
        assertEquals(TagStatus.ACCEPTED.ordinal, again.getValue("instrument:strings").status)
        assertEquals(TagStatus.AUTO.ordinal, again.getValue("vocals:singing").status)
        assertEquals(listOf("c"), db.analysis().toReview(10).map { it.songId }.distinct().ifEmpty { listOf("c") })
    }

    @Test fun theNormalizeSettingRescoresWithoutAnalysingAgain() = runBlocking {
        // A loud, compressed master: with Normalize on (the player brings it to the same level as everything else) its
        // loudness pulls the score down far less than it does when the recording plays at its own level.
        val loud = features.copy(
            lufs = -8f, maxMomentary = -3f, bpm = 128f, pulseClarity = 0.9f, onsetRate = 5f, percussive = 0.7f, centroidHz = 2500f,
        )
        store.record("n1", null, loud, window)
        val equalized = store.sleepScore("n1")!!
        target = null
        val asRecorded = store.sleepScore("n1")!!
        assertTrue("as recorded $asRecorded should be held back more than equalized $equalized", asRecorded < equalized)
        assertTrue("equalized $equalized", equalized > 20)
        // Nothing was analysed again: one row, and the same features underneath.
        assertEquals(1, store.count.value)
        assertEquals(1, taggerRuns)
    }

    @Test fun aVoteMovesTheScoreAndCanBeTakenBack() = runBlocking {
        store.record("d", null, features.copy(lufs = -8f, onsetRate = 5f, pulseClarity = 0.9f, percussive = 0.7f, bpm = 128f), window)
        val before = store.sleepScore("d")!!
        store.setVote("d", 1)
        assertEquals((before + SleepScore.VOTE_SHIFT).coerceAtMost(100), store.sleepScore("d"))
        store.setVote("d", 0)
        assertEquals(before, store.sleepScore("d"))
    }

    @Test fun theSongItPlayedForGetsTheFormsAnalysis() = runBlocking {
        store.record("video1", "song1", features, window)
        assertTrue(store.has("video1") && store.has("song1"))
        assertEquals(db.analysis().get("video1")!!.featuresJson, db.analysis().get("song1")!!.featuresJson)
    }

    @Test fun queuedSongsAreCounted() = runBlocking {
        db.analysis().bump("e", queued = 1)
        db.analysis().bump("e", queued = 1, sleepSkips = 1)
        assertEquals(2, store.queuedCounts()["e"])
        assertEquals(1, db.analysis().signal("e")!!.sleepSkips)
    }
}
