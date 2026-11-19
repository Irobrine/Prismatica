package com.prism.music

import android.content.Context
import com.prism.music.data.prefs.SleepTimerChoices
import com.prism.music.data.prefs.SleepTimerPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The sleep timer's saved choices: remembered across menu closures and restarts, clamped to what the menu can show, and
 * kept apart from a session (cancelling one never rewrites them).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SleepTimerPrefsTest {
    private fun context(): Context = RuntimeEnvironment.getApplication()

    private fun fresh(): SleepTimerPrefs {
        context().getSharedPreferences("sleep_timer", Context.MODE_PRIVATE).edit().clear().commit()
        return SleepTimerPrefs(context())
    }

    @Test fun defaultsKeepTheOldTimersBehaviour() {
        val prefs = fresh()
        assertEquals(SleepTimerChoices(minutes = 30, maxBpm = 0, finishSong = false, volume = 1f), prefs.choices.value)
    }

    @Test fun everyChoiceSurvivesARestart() {
        val prefs = fresh()
        prefs.setMinutes(45)
        prefs.setMaxBpm(120)
        prefs.setFinishSong(true)
        prefs.setVolume(0.35f)
        // A new repository on the same store: the app started again.
        val again = SleepTimerPrefs(context()).choices.value
        assertEquals(45, again.minutes)
        assertEquals(120, again.maxBpm)
        assertTrue(again.finishSong)
        assertEquals(0.35f, again.volume, 0f)
    }

    @Test fun valuesAreClampedToTheMenusRange() {
        val prefs = fresh()
        prefs.setMinutes(0)
        assertEquals(SleepTimerPrefs.MIN_MINUTES, prefs.choices.value.minutes)
        prefs.setMinutes(10_000)
        assertEquals(SleepTimerPrefs.MAX_MINUTES, prefs.choices.value.minutes)
        prefs.setMaxBpm(-5)
        assertEquals(0, prefs.choices.value.maxBpm)
        prefs.setMaxBpm(10_000)
        assertEquals(SleepTimerPrefs.MAX_BPM_LIMIT, prefs.choices.value.maxBpm)
        prefs.setVolume(-1f)
        assertEquals(0f, prefs.choices.value.volume, 0f)
        prefs.setVolume(3f)
        assertEquals(1f, prefs.choices.value.volume, 0f)
    }

    @Test fun aTypedNumberOfMinutesIsKeptInsideTheMenusRange() {
        assertEquals(45, SleepTimerPrefs.parseMinutes("45"))
        assertEquals(45, SleepTimerPrefs.parseMinutes(" 45 "))
        assertEquals(SleepTimerPrefs.MIN_MINUTES, SleepTimerPrefs.parseMinutes("0"))
        assertEquals(SleepTimerPrefs.MAX_MINUTES, SleepTimerPrefs.parseMinutes("999"))
        assertNull(SleepTimerPrefs.parseMinutes(""))
        assertNull(SleepTimerPrefs.parseMinutes("soon"))
    }

    @Test fun changingAChoicePublishesItAtOnce() {
        val prefs = fresh()
        val seen = ArrayList<Int>()
        seen += prefs.choices.value.minutes
        prefs.setMinutes(90)
        seen += prefs.choices.value.minutes
        assertEquals(listOf(30, 90), seen)
    }
}
