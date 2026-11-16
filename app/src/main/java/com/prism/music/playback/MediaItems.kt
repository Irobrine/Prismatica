package com.prism.music.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.model.Song
import com.prism.music.data.model.hiRes

const val EXTRA_SONG = "prism.song"
const val EXTRA_VIDEO = "prism.video"

/**
 * prism://audio/<id>[?video=<videoId>] – resolved to a real stream URL at load time.
 * Live streams are prism://live/<id>, resolved to their HLS manifest, which carries the picture too: their video mode
 * keeps the same URI (so switching doesn't reload the stream) and only turns the video track on.
 */
fun Song.toMediaItem(videoId: String? = null): MediaItem {
    val uri = if (isLive) Uri.Builder().scheme("prism").authority("live").appendPath(id).build()
    else Uri.Builder().scheme("prism").authority("audio").appendPath(id).apply {
        if (videoId != null) appendQueryParameter("video", videoId)
        // The listener's own upload (#101): asked for as their account from the start.
        if (isUpload) appendQueryParameter("upload", "1")
    }.build()
    val extras = Bundle().apply {
        putString(EXTRA_SONG, InnerTube.json.encodeToString(Song.serializer(), this@toMediaItem))
        if (videoId != null) putString(EXTRA_VIDEO, videoId)
    }
    // #328: shown as the version picked to play in its place (the item itself stays the song, for history and likes).
    val shown = com.prism.music.data.SongVersions.shownOf(this)
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(uri)
        .setCustomCacheKey(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(shown.title)
                .setArtist(shown.artistText)
                .setAlbumTitle(shown.album?.title)
                // The cover picked for the song or its album (#305), or a file's own (#350), through the artwork provider:
                // a car or a watch can't open Prism's files, nor Android's album art without its own permission.
                .setArtworkUri(com.prism.music.data.PlaylistCovers.artOf(this@toMediaItem).let { art ->
                    if (art != null && (art.startsWith("file:") || art.startsWith(ArtworkProvider.LOCAL_ALBUM_ART))) ArtworkProvider.contentUri(art)
                    else hiRes(art, 544)?.let(Uri::parse)
                })
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(if (isEpisode) MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE else MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(extras)
                .build()
        )
        .build()
}

/**
 * The [Song] carried by a media item. The same item is read again and again (the queue is mirrored on every player event),
 * so its decoded song is kept by the text it came from: a queue of thousands is decoded once, not on every update.
 */
fun MediaItem.toSong(): Song? {
    val text = mediaMetadata.extras?.getString(EXTRA_SONG) ?: return null
    decodedSongs[text]?.let { return it }
    val song = runCatching { InnerTube.json.decodeFromString(Song.serializer(), text) }.getOrNull()?.trusted() ?: return null
    decodedSongs[text] = song
    return song
}

/** Decoded songs by their own JSON, the text a media item keeps ([toSong]). */
private val decodedSongs = java.util.concurrent.ConcurrentHashMap<String, Song>()

private val SONG_IMAGE_HOSTS = listOf("ytimg.com", "ggpht.com", "googleusercontent.com", "gstatic.com")

/**
 * A song a controller sent, with anything that could reach somewhere else taken out. An app that isn't Prism can set a
 * media item: a made-up id or a cover address of its own would otherwise be fetched or played as if it were the library's.
 */
internal fun Song.trusted(): Song? {
    if (id.isEmpty() || id.length > 200 || id.contains('/') || id.contains('\\') || id.any { it.code < 0x20 }) return null
    val safeThumb = thumbnail?.takeIf { url ->
        url.startsWith("https://") && runCatching { Uri.parse(url).host?.lowercase() }.getOrNull()?.let { h ->
            SONG_IMAGE_HOSTS.any { d -> h == d || h.endsWith(".$d") }
        } == true
    }
    return if (safeThumb == thumbnail) this else copy(thumbnail = safeThumb)
}

val MediaItem.videoModeId: String? get() = mediaMetadata.extras?.getString(EXTRA_VIDEO)
