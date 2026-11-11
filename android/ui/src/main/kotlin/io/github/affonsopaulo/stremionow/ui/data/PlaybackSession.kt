// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data

import io.github.affonsopaulo.stremionow.core.Episode
import io.github.affonsopaulo.stremionow.core.MetaDetails
import io.github.affonsopaulo.stremionow.core.StreamSource

/**
 * What the user chose to watch, handed from the details page to the source picker and on to
 * the player.
 *
 * It lives in memory only: stream addresses can carry debrid keys, so they never go into
 * navigation arguments, saved state or logs. After the process dies the picker and the player
 * find it empty and step back.
 */
object PlaybackSession {
    /** A title, and the episode for series. */
    data class Selection(
        val details: MetaDetails,
        val episode: Episode?,
    ) {
        val kind: String get() = details.item.kind

        /** The id addons expect for this video: the episode's, or the title's for movies. */
        val videoId: String get() = episode?.id ?: details.item.id

        /** The episode after this one, crossing into the next season; specials stay apart. */
        val nextEpisode: Episode?
            get() {
                val current = episode ?: return null
                val sameKind = details.episodes.filter { ((it.season ?: 0u) == 0u) == ((current.season ?: 0u) == 0u) }
                val index = sameKind.indexOfFirst { it.id == current.id }
                return sameKind.getOrNull(index + 1).takeIf { index >= 0 }
            }
    }

    @Volatile var selection: Selection? = null

    /** The source picked for [selection]. */
    @Volatile var source: StreamSource? = null

    /** Other sources of the same pick, to fall back to or keep the binge group across episodes. */
    @Volatile var alternatives: List<StreamSource> = emptyList()

    fun select(selection: Selection) {
        this.selection = selection
        source = null
    }
}
