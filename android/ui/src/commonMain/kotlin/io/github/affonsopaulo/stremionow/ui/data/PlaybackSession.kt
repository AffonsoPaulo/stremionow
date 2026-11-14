// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data

import io.github.affonsopaulo.stremionow.core.Episode
import io.github.affonsopaulo.stremionow.core.MetaDetails
import io.github.affonsopaulo.stremionow.core.StreamSource

/**
 * What the user chose to watch, handed from the details page, where the sources are, to the
 * player, and back from the player when it moves on to another episode or another source.
 *
 * It lives in memory only: stream addresses can carry debrid keys, so they never go into
 * navigation arguments, saved state or logs. After the process dies the player finds it
 * empty and steps back.
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

    /**
     * When moving on to the next episode, the binge group of the source just watched: the
     * details page plays the first source of the same group by itself, as Stremio does.
     */
    @Volatile var continueWith: String? = null

    /**
     * Set by the player when it hands back to the details page to choose a source: "Change
     * source", or Up Next without a source of the same group. The page opens the sources of
     * [selection]'s video in place of its episodes, then clears it.
     */
    @Volatile var showSources: Boolean = false

    fun select(selection: Selection) {
        this.selection = selection
        source = null
    }

    /** Asks the details page of [selection]'s title to show its sources; see [showSources]. */
    fun returnToSources() {
        showSources = selection != null
    }
}
