package com.prism.music

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.prism.music.data.ScoreMark
import com.prism.music.data.SleepScoreMarks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The tuning marks: "too high" / "too low" on a song, toggled off by tapping the same one again, kept on the phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SleepScoreMarksTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun aMarkTogglesAndIsKept() {
        SleepScoreMarks(context).clear()
        val marks = SleepScoreMarks(context)
        assertNull(marks.of("a"))
        marks.toggle("a", ScoreMark.HIGH)
        assertEquals(ScoreMark.HIGH, marks.of("a"))
        // The other direction replaces it; the same one again takes it off.
        marks.toggle("a", ScoreMark.LOW)
        assertEquals(ScoreMark.LOW, marks.of("a"))
        marks.toggle("a", ScoreMark.LOW)
        assertNull(marks.of("a"))
        // Kept for the next time the list opens.
        marks.toggle("b", ScoreMark.HIGH)
        assertEquals(mapOf("b" to ScoreMark.HIGH), SleepScoreMarks(context).marks.value)
        SleepScoreMarks(context).clear()
        assertEquals(emptyMap<String, ScoreMark>(), SleepScoreMarks(context).marks.value)
    }
}
