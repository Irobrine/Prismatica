package com.prism.music

import com.prism.music.data.sleep.SleepCalibration
import com.prism.music.data.sleep.SleepFeatures
import com.prism.music.data.sleep.SleepLabel
import com.prism.music.data.sleep.SleepLabels
import com.prism.music.data.sleep.SleepWeights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Listener judgments and the weights fitted from them (1.9.2). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SleepCalibrationTest {
    private fun context() = RuntimeEnvironment.getApplication()

    private fun fresh(): Pair<SleepLabels, SleepCalibration> {
        context().getSharedPreferences("sleep_score_labels", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        context().getSharedPreferences("sleep_score_calibration", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        return SleepLabels(context()) to SleepCalibration(context())
    }

    @Test fun labelsAreRememberedAndCanBeUnmarked() {
        val (labels, _) = fresh()
        labels.set("song:a", SleepLabel.SUITABLE)
        labels.set("song:b", SleepLabel.UNSUITABLE)
        assertEquals(SleepLabel.SUITABLE, SleepLabels(context()).label("song:a"))
        assertEquals(SleepLabel.UNSUITABLE, labels.label("song:b"))
        assertNull(labels.label("song:c"))
        labels.set("song:a", null)
        assertNull(SleepLabels(context()).label("song:a"))
    }

    @Test fun fittingNeedsBothEndsOfTheJudgment() {
        val (labels, _) = fresh()
        repeat(8) { labels.set("song:$it", SleepLabel.SUITABLE) }
        assertFalse("all one judgment teaches nothing", labels.enoughToFit())
        labels.set("song:7", SleepLabel.UNSUITABLE)
        assertFalse("still needs two unsuitable", labels.enoughToFit())
        labels.set("song:6", SleepLabel.UNSUITABLE)
        assertTrue(labels.enoughToFit())
    }

    @Test fun fittingLearnsWhatTheListenerMarkedAndKeepsMonotonicity() {
        val (_, calibration) = fresh()
        val calm = SleepFeatures(transients = 0.05, onsets = 0.08, activity = 0.1, worstSection = 0.1)
        val busy = SleepFeatures(transients = 0.9, onsets = 0.85, activity = 0.8, worstSection = 0.9)
        val samples = listOf(
            calm to SleepLabel.SUITABLE.target,
            calm.copy(transients = 0.1) to SleepLabel.SUITABLE.target,
            calm.copy(onsets = 0.12) to SleepLabel.SUITABLE.target,
            calm.copy(brightness = 0.05) to SleepLabel.SUITABLE.target,
            busy to SleepLabel.UNSUITABLE.target,
            busy.copy(transients = 0.85) to SleepLabel.UNSUITABLE.target,
            busy.copy(onsets = 0.8) to SleepLabel.UNSUITABLE.target,
            busy.copy(worstSection = 0.8) to SleepLabel.UNSUITABLE.target,
        )
        val fitted = calibration.fit(samples)
        assertTrue("the transient weight grew: ${fitted.weights[2]}", fitted.weights[2] > SleepWeights.DEFAULT_WEIGHTS[2])
        val calmScore = fitted.score(calm, 100)
        val busyScore = fitted.score(busy, 100)
        assertTrue("the marked-calm song scores higher: $calmScore vs $busyScore", calmScore > busyScore + 25)
        assertTrue("and even a disruptive song keeps a usable figure: $busyScore", busyScore >= SleepWeights.FLOOR.toInt())
        // More disturbance can never raise a score, whatever the fit did.
        assertTrue(fitted.weights.all { it >= 0.0 })
        assertEquals(fitted, calibration.fit(samples))
    }

    @Test fun fittedWeightsSurviveARestart() {
        val (_, calibration) = fresh()
        val weights = SleepWeights(bias = 1.6, weights = doubleArrayOf(0.1, 0.6, 1.7, 1.1, 0.9, 1.0, 0.4, 0.8), labels = 12)
        calibration.save(weights)
        assertEquals(weights, SleepCalibration(context()).weights.value)
        calibration.reset()
        assertEquals(SleepWeights(), SleepCalibration(context()).weights.value)
    }
}
