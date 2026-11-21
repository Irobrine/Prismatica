package com.prism.music

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.prism.music.data.sleep.SleepAnalysis
import com.prism.music.data.sleep.SleepComponents
import com.prism.music.data.sleep.SleepScore
import com.prism.music.data.sleep.SleepScores
import com.prism.music.data.sleep.SleepWarning
import com.prism.music.data.sleep.SleepWarningKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Where sleep scores are kept (1.9): every part of a figure survives a restart, one recording's figure is its own, and a
 * figure from an older scoring version is never used as if it were current (the filter passes the song over, and the
 * next calculation rewrites it) — that is what makes recalculation after a formula change safe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SleepScoreStoreTest {
    private fun context(): Context = RuntimeEnvironment.getApplication()

    private fun fresh(): SleepScores {
        context().getSharedPreferences("sleep_scores", Context.MODE_PRIVATE).edit().clear().commit()
        return SleepScores(context())
    }

    private fun figure(score: Int, version: Int = SleepAnalysis.MODEL_VERSION) = SleepScore(
        score = score,
        confidence = 81,
        components = SleepComponents(quietness = 90, dynamics = 70, transients = 60, rhythm = 50, complexity = 40, mood = 30, model = 20, worstSection = 65),
        warnings = listOf(
            SleepWarning(SleepWarningKind.SUDDEN_LOUD, 59_977, 60_441, 3),
            SleepWarning(SleepWarningKind.CRESCENDO, 10_000, 20_015, 2),
        ),
        modelVersion = version,
        durationMs = 231_000,
        analyzedAtMs = 1_700_000_000_000,
    )

    @Test fun everyPartOfAFigureSurvivesAndOneRecordingIsItsOwn() {
        val store = fresh()
        store.put("form:a", figure(74))
        store.put("form:b", figure(12))
        // A new store on the same preferences: the app started again.
        val again = SleepScores(context())
        val a = again.current("form:a")!!
        assertEquals(74, a.score)
        assertEquals(81, a.confidence)
        assertEquals(SleepComponents(90, 70, 60, 50, 40, 30, 20, 65), a.components)
        assertEquals(SleepAnalysis.MODEL_VERSION, a.modelVersion)
        assertEquals(231_000, a.durationMs)
        assertEquals(1_700_000_000_000, a.analyzedAtMs)
        assertEquals("warnings keep their times and severities", listOf(
            SleepWarning(SleepWarningKind.SUDDEN_LOUD, 59_977, 60_441, 3),
            SleepWarning(SleepWarningKind.CRESCENDO, 10_000, 20_015, 2),
        ), a.warnings)
        assertEquals(74, again.score("form:a"))
        assertEquals(2, again.calculated.value)
        assertNull(again.current("form:c"))
    }

    @Test fun aFigureFromAnOlderScoringVersionIsNotUsed() {
        val store = fresh()
        store.put("old", figure(95, version = SleepAnalysis.MODEL_VERSION - 1))
        assertNull("an outdated figure is never current", store.current("old"))
        assertNull("so a score limit can't be satisfied by it", store.score("old"))
        assertFalse(store.isCurrent("old"))
        assertEquals("but what was measured is still there to show", 95, store.stored("old")!!.score)
        assertEquals(0, store.calculated.value)
        // Recalculating with the current version replaces it.
        store.put("old", figure(48))
        assertEquals(48, store.current("old")!!.score)
        assertTrue(store.isCurrent("old"))
        assertEquals(1, store.calculated.value)
        assertEquals(1, store.ids().size)
    }

    @Test fun aFigureWrittenAfterTheStoreWasReadIsFoundToo() {
        val store = fresh()
        // The store has read what was there (nothing), then a figure lands from another path (a backup restore, a
        // longer-lived store in the app): the next lookup must find it rather than trust an empty cache for ever.
        assertEquals(emptySet<String>(), store.ids())
        context().getSharedPreferences("sleep_scores", Context.MODE_PRIVATE).edit()
            .putString("late", SleepScore.encode(figure(77))).commit()
        assertEquals(77, store.current("late")!!.score)
    }

    @Test fun forgettingOneAndClearingAll() {
        val store = fresh()
        store.put("a", figure(80))
        store.put("b", figure(10))
        store.forget("a")
        assertNull(store.current("a"))
        assertEquals(1, store.calculated.value)
        store.clear()
        assertNull(store.current("b"))
        assertEquals(0, store.calculated.value)
        assertEquals(emptySet<String>(), store.ids())
    }

    @Test fun aCorruptFigureIsIgnoredRatherThanCrashingTheFilter() {
        context().getSharedPreferences("sleep_scores", Context.MODE_PRIVATE).edit()
            .putString("broken", "{not json").commit()
        val store = SleepScores(context())
        assertNull(store.current("broken"))
        assertEquals(0, store.calculated.value)
    }

    @Test fun theStoredTextRoundTripsAndIgnoresUnknownFields() {
        val figure = figure(63)
        assertEquals(figure, SleepScore.decode(SleepScore.encode(figure)))
        val withExtra = SleepScore.encode(figure).dropLast(1) + ",\"aFieldFromALaterVersion\":true}"
        assertEquals(figure, SleepScore.decode(withExtra))
        assertNull(SleepScore.decode(null))
        assertNull(SleepScore.decode("nonsense"))
    }

    @Test fun aWalkSkipsCurrentFiguresAndTakesUpMissingOrOutdatedOnesOnce() {
        val store = fresh()
        store.put("current", figure(80))
        store.put("outdated", figure(30, version = SleepAnalysis.MODEL_VERSION - 1))
        val walk = com.prism.music.data.MeasureSongs(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            { _, _, _ -> },
            { form -> store.isCurrent(form) },
            { it },
            { 1 },
            { false },
        )
        assertEquals(
            "missing and outdated are walked; a duplicate is one recording",
            listOf("missing" to "missing", "outdated" to "outdated"),
            walk.pending(listOf("current", "missing", "outdated", "missing")),
        )
    }
}
