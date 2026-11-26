package com.prism.music.ui.player

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.prism.music.data.prefs.PanelSwipes

/**
 * #280 (1.8.2): "Reopen on the last page". What the player had open when it was left, kept in plain preferences of its
 * own: it changes with every page turn, and a settings write there would pass through everything that watches settings.
 */
enum class PlayerPage {
    COVER, LYRICS, QUEUE;

    companion object {
        fun of(lyricsOpen: Boolean, queueOpen: Boolean): PlayerPage = when {
            queueOpen -> QUEUE
            lyricsOpen -> LYRICS
            else -> COVER
        }

        /** An episode has no lyrics, so a song's lyrics page opens as the cover there. */
        fun opensOn(saved: String?, episode: Boolean): PlayerPage {
            val page = entries.firstOrNull { it.name == saved } ?: COVER
            return if (page == LYRICS && episode) COVER else page
        }
    }
}

/**
 * What a swipe up on the player's lower half opens, from its [right] half (else the left): the queue or the lyrics,
 * picked per side ([PanelSwipes]); null with [PanelSwipes.OFF]. Both panels are opened the same way — the deck's one
 * drag and the player's morph — so the side only picks which page comes, never how it comes.
 */
fun PanelSwipes.page(right: Boolean): PlayerPage? =
    if (this == PanelSwipes.OFF) null else if (queueFrom(right)) PlayerPage.QUEUE else PlayerPage.LYRICS

/**
 * Whether the panel's one layer is the queue's: the queue open, or — every panel shut — the one it was while the move
 * fades it away (its list used to be cut away while the deck rose under it). A panel that isn't the queue (the lyrics,
 * the stats, the menu) takes the layer: the same number brings them, and the queue's list is drawn apart from the words,
 * so both stood on the screen at once when a side set to the lyrics was swiped up.
 */
fun queuePanelLast(queueOpen: Boolean, lyricsOpen: Boolean, statsOpen: Boolean, menuUp: Boolean, was: Boolean): Boolean =
    when {
        queueOpen -> true
        lyricsOpen || statsOpen || menuUp -> false
        else -> was
    }

/** Whether the queue's layer is composed: with the queue, and through the move that takes it away ([queuePanelLast]). */
fun queuePanelShown(queueOpen: Boolean, queueLast: Boolean, panel: Float): Boolean =
    queueOpen || (queueLast && panel > 0.001f)

/**
 * A page asked for from outside the player, taken by it the next time it's on screen with that song: a recognized song
 * played opens on its lyrics (#215). [lyricsFor] is the song's id.
 */
object PlayerAsks {
    var lyricsFor by androidx.compose.runtime.mutableStateOf<String?>(null)
}

class PlayerPageMemory(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("player_page", Context.MODE_PRIVATE)

    fun opensOn(episode: Boolean): PlayerPage = PlayerPage.opensOn(prefs.getString(KEY, null), episode)

    fun keep(page: PlayerPage) = prefs.edit().putString(KEY, page.name).apply()

    private companion object {
        const val KEY = "page"
    }
}
