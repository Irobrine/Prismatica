package com.prism.music

import com.prism.music.data.model.Song
import com.prism.music.playback.OfflineQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where a queue starts offline (#55). A song that isn't on the phone can't be fetched, so a tap on one starts the
 * nearest one that is rather than failing a moment later; a shuffle picks among those that can play. The queue keeps
 * its songs either way.
 */
class OfflineQueueTest {
    private fun songs(vararg ids: String) = ids.map { Song(it, "Song $it") }
    /** The ids that are on the phone. */
    private val onPhone = setOf("b", "d")
    private val playable: (Song) -> Boolean = { it.id in onPhone }

    private fun start(songs: List<Song>, at: Int, shuffle: Boolean = false, pick: (Int) -> Int = { 0 }) =
        OfflineQueue.start(songs, at, shuffle, playable, pick)

    @Test fun aSongOnThePhoneStartsAsTapped() {
        assertEquals(1, start(songs("a", "b", "c", "d"), 1))
    }

    @Test fun aSongThatIsNotOnThePhoneGivesWayToTheNextOneThatIs() {
        assertEquals(3, start(songs("a", "b", "c", "d"), 2))
        assertEquals(1, start(songs("a", "b", "c", "d"), 0))
    }

    @Test fun withNothingAfterTheTappedSongTheNearestOneBeforeItStarts() {
        // d is the last song on the phone; tapping e (not) goes back to it rather than playing nothing.
        assertEquals(3, start(songs("a", "b", "c", "d", "e"), 4))
    }

    @Test fun aShufflePicksAmongTheSongsOnThePhoneOnly() {
        val list = songs("a", "b", "c", "d", "e")
        // Only b and d can play, wherever the random pick lands.
        assertEquals(1, start(list, 0, shuffle = true) { 0 })
        assertEquals(3, start(list, 0, shuffle = true) { 1 })
        assertEquals(3, start(list, 0, shuffle = true) { 99 })
    }

    @Test fun aQueueWithNothingOnThePhoneHasNoPlaceToStart() {
        assertNull(OfflineQueue.start(songs("a", "c"), 0, shuffle = false, playable = { false }) { 0 })
    }

    @Test fun anEmptyQueueHasNoPlaceToStart() {
        assertNull(OfflineQueue.start(emptyList(), 0, shuffle = false, playable = { true }) { 0 })
    }

    @Test fun aPlaceOutsideTheQueueIsBroughtInside() {
        assertEquals(3, start(songs("a", "b", "c", "d"), 99))
        assertEquals(1, start(songs("a", "b", "c", "d"), -5))
    }

    @Test fun onlyTheSongsOnThePhoneAreKeptInTheirOwnOrder() {
        assertEquals(listOf("b", "d"), OfflineQueue.onlyPlayable(songs("a", "b", "c", "d"), playable).map { it.id })
    }
}
