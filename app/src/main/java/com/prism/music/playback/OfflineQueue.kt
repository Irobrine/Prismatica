package com.prism.music.playback

import com.prism.music.data.model.Song

/**
 * Where a queue starts when the phone is offline (#55): a song that isn't on the phone can't be fetched, so a queue
 * starts on the nearest one that is rather than on a song that would fail a moment later.
 *
 * The queue itself keeps every song: the ones passed over stay listed, and the player's own AUTO skip goes on passing
 * them by until a connection comes back (or the queue ends). This only decides the place the first sound comes from.
 */
object OfflineQueue {
    /**
     * The place [songs] should start on, given the place asked for ([start]) and whether the queue plays shuffled.
     *
     * A tap keeps its own song when it plays offline; otherwise the next one after it that does, and if there's none,
     * the last one before it, so a queue whose on-phone songs are all earlier still starts. A shuffle picks among the
     * songs that play offline, so an offline shuffle of a mainly-streaming list still starts on the phone.
     *
     * [playable] is asked once per song, in order. [random] picks one of `0 until count` and exists for tests; the
     * default uses [kotlin.random.Random]. Null when not one song in the queue can play offline.
     */
    fun start(
        songs: List<Song>,
        start: Int,
        shuffle: Boolean,
        playable: (Song) -> Boolean,
        random: (Int) -> Int = { kotlin.random.Random.nextInt(it) },
    ): Int? {
        if (songs.isEmpty()) return null
        if (shuffle) {
            val here = songs.indices.filter { playable(songs[it]) }
            if (here.isEmpty()) return null
            return here[random(here.size).coerceIn(0, here.lastIndex)]
        }
        val from = start.coerceIn(0, songs.lastIndex)
        for (i in from..songs.lastIndex) if (playable(songs[i])) return i
        for (i in from - 1 downTo 0) if (playable(songs[i])) return i
        return null
    }

    /** [songs] with only those that play offline, in their own order (the notification card's shuffles use it). */
    fun onlyPlayable(songs: List<Song>, playable: (Song) -> Boolean): List<Song> =
        songs.filter(playable)
}
