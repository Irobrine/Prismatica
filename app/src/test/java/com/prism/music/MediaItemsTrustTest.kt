package com.prism.music

import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.playback.toMediaItem
import com.prism.music.playback.toSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A media item can be set by any app that reaches Prism's session. What such an item carries is only taken when it is
 * Prism's own: an id it made up, or a cover address of its own, is dropped (never fetched or played as the library's).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class MediaItemsTrustTest {
    private fun song(id: String, thumbnail: String? = null) =
        Song(id, "A song", listOf(ArtistRef("The Band")), AlbumRef("An album"), 200, thumbnail)

    @Test fun aCoverFromAnywhereElseIsDropped() {
        assertNull(song("dQw4w9WgXcQ", "https://evil.example/a.jpg").toMediaItem().toSong()?.thumbnail)
        assertNull(song("dQw4w9WgXcQ", "https://evilgoogleusercontent.com/a.jpg").toMediaItem().toSong()?.thumbnail)
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq.jpg",
            song("dQw4w9WgXcQ", "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq.jpg").toMediaItem().toSong()?.thumbnail,
        )
    }

    @Test fun aMadeUpIdIsntASong() {
        assertNull(song("../../private").toMediaItem().toSong())
        assertNull(song("with/inside").toMediaItem().toSong())
        assertNull(song("").toMediaItem().toSong())
        assertEquals("dQw4w9WgXcQ", song("dQw4w9WgXcQ").toMediaItem().toSong()?.id)
        assertEquals("local:42", song("local:42").toMediaItem().toSong()?.id)
    }
}
