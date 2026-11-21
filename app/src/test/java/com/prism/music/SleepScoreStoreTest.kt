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
        context().getSharedPreferences("sleep_scores_normalized", Context.MODE_PRIVATE).edit().clear().commit()
        return SleepScores(context())
    }

    private fun figure(score: Int, version: Int = SleepAnalysis.MODEL_VERSION, normalized: Boolean = false) = SleepScore(
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
        normalized = normalized,
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

    @Test fun aNormalizedFigureLivesInItsOwnModeAndOverridesThePlainOneThere() {
        val store = fresh()
        store.put("a", figure(40))
        // The same recording, judged on the sound as Normalize plays it: its own file, and the one that counts when the
        // mode is on. It doesn't destroy the plain figure, which is what the mode off goes back to.
        store.put("a", figure(90, normalized = true))
        assertEquals(40, store.score("a", normalized = false))
        assertEquals(90, store.score("a", normalized = true))
        assertEquals(40, store.stored("a", normalized = false)!!.score)
        assertEquals(90, store.stored("a", normalized = true)!!.score)
        assertEquals(1, store.calculated.value)
        assertEquals(1, store.calculatedNormalized.value)
        // Both survive a restart, in their own files.
        val again = SleepScores(context())
        assertEquals(40, again.score("a", normalized = false))
        assertEquals(90, again.score("a", normalized = true))
    }

    @Test fun onlyTheFigureOfTheModeInUseIsCurrent() {
        val store = fresh()
        store.put("plain", figure(80))
        store.put("norm", figure(70, normalized = true))
        assertEquals(80, store.score("plain", normalized = false))
        assertNull("a plain figure doesn't count in normalized mode", store.score("plain", normalized = true))
        assertFalse(store.isCurrent("plain", normalized = true))
        assertNull("a normalized figure doesn't count in plain mode", store.score("norm", normalized = false))
        assertFalse(store.isCurrent("norm", normalized = false))
        assertEquals(70, store.score("norm", normalized = true))
        // The counts are each mode's own.
        assertEquals(1, store.calculated.value)
        assertEquals(1, store.calculatedNormalized.value)
        // An old version in either file is not current there either.
        store.put("oldnorm", figure(95, version = SleepAnalysis.MODEL_VERSION - 1, normalized = true))
        assertNull(store.current("oldnorm", normalized = true))
        assertEquals(1, store.calculatedNormalized.value)
    }

    @Test fun clearingDropsBothModes() {
        val store = fresh()
        store.put("a", figure(80))
        store.put("a", figure(85, normalized = true))
        store.clear()
        assertNull(store.current("a", normalized = false))
        assertNull(store.current("a", normalized = true))
        assertEquals(0, store.calculated.value)
        assertEquals(0, store.calculatedNormalized.value)
    }

    @Test fun clearingNormalizedKeepsThePlainFigures() {
        val store = fresh()
        store.put("a", figure(60))
        store.put("a", figure(80, normalized = true))
        // A new Normalize target makes the normalized gain wrong; the plain figure doesn't depend on it.
        store.clearNormalized()
        assertEquals(60, store.score("a", normalized = false))
        assertNull(store.score("a", normalized = true))
        assertEquals(1, store.calculated.value)
        assertEquals(0, store.calculatedNormalized.value)
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
        val figure = figure(63, normalized = true)
        assertTrue("the mode travels with the figure", figure.normalized)
        assertEquals(figure, SleepScore.decode(SleepScore.encode(figure)))
        val withExtra = SleepScore.encode(figure).dropLast(1) + ",\"aFieldFromALaterVersion\":true}"
        assertEquals(figure, SleepScore.decode(withExtra))
        assertNull(SleepScore.decode(null))
        assertNull(SleepScore.decode("nonsense"))
    }

    @Test fun aWalkSkipsCurrentFiguresAndTakesUpMissingOrOutdatedOnesOnce() {
        val store = fresh()
        store.put("plain", figure(80))
        store.put("norm", figure(70, normalized = true))
        store.put("outdated", figure(30, version = SleepAnalysis.MODEL_VERSION - 1))
        fun walkFor(normalized: Boolean) = com.prism.music.data.MeasureSongs(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            { _, _, _ -> },
            { form -> store.isCurrent(form, normalized) },
            { it },
            { 1 },
            { false },
        )
        // Each mode walks what it has no figure for: switching the mode changes the work, and the progress with it.
        assertEquals(
            "plain mode: the normalized figure is not a plain one; missing and outdated are walked",
            listOf("norm" to "norm", "outdated" to "outdated"),
            walkFor(false).pending(listOf("plain", "norm", "outdated")),
        )
        assertEquals(
            "normalized mode: the plain figure is not a normalized one; missing and outdated are walked",
            listOf("plain" to "plain", "outdated" to "outdated"),
            walkFor(true).pending(listOf("plain", "norm", "outdated")),
        )
        // A duplicate is one recording, and a current figure of the mode is left out.
        assertEquals(
            listOf("missing" to "missing"),
            walkFor(true).pending(listOf("norm", "missing", "missing")),
        )
    }
}
