package com.prism.music

import androidx.test.core.app.ApplicationProvider
import com.prism.music.data.MoreFrom
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.onlineMoreFrom
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * "More from this artist" picks from the artist's catalogue, not the five songs their page carries: the list behind
 * Top songs' "See all" (a `VL…` playlist) is what it reads. The page's shuffle link is a "next" queue, not a list.
 */
@Category(LiveNetwork::class)
@RunWith(RobolectricTestRunner::class)
class MoreFromCatalogueLiveTest {
    private val c get() = ApplicationProvider.getApplicationContext<PrismApp>().container

    @Test
    fun anArtistOffersMoreThanTheirTopSongs() = runBlocking {
        for (name in listOf("Radiohead", "Daft Punk")) {
            val id = c.ytm.artistIdByName(name) ?: continue
            val song = Song("probe", "Creep", listOf(ArtistRef(name, id)), null)
            val pool = c.onlineMoreFrom(song, MoreFrom.ARTIST)
            assertTrue("$name: ${pool.size} songs to pick from", pool.size >= 25)
            assertTrue("$name: every song from one album", pool.mapNotNull { it.album?.title }.distinct().size >= 2)
        }
    }
}
