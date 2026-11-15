// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import io.github.affonsopaulo.stremionow.core.Episode
import io.github.affonsopaulo.stremionow.core.MetaDetails
import io.github.affonsopaulo.stremionow.core.MetaItem
import io.github.affonsopaulo.stremionow.ui.data.PlaybackSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackChecksTest {
    private fun selection(
        kind: String,
        episodeMinutes: UInt? = null,
        movieRuntime: String? = null,
    ): PlaybackSession.Selection {
        val episode =
            if (kind == "series") {
                Episode("tt1:1:1", "E1", 1u, 1u, null, null, null, episodeMinutes)
            } else {
                null
            }
        val details =
            MetaDetails(
                item = MetaItem("tt1", kind, "Title", null, null, null, null, null, null, emptyList()),
                runtime = movieRuntime,
                cast = emptyList(),
                directors = emptyList(),
                episodes = listOfNotNull(episode),
                trailerYoutubeIds = emptyList(),
                shareUrl = null,
            )
        return PlaybackSession.Selection(details, episode)
    }

    @Test
    fun `a clip in place of an episode looks incomplete`() {
        assertTrue(looksIncomplete(30.0, selection("series")))
        assertTrue(looksIncomplete(20 * 60.0, selection("series", episodeMinutes = 50u)))
        assertFalse(looksIncomplete(42 * 60.0, selection("series", episodeMinutes = 50u)))
        assertFalse(looksIncomplete(24 * 60.0, selection("series")))
        assertTrue(looksIncomplete(40 * 60.0, selection("movie", movieRuntime = "2h 22min")))
        assertFalse(looksIncomplete(130 * 60.0, selection("movie", movieRuntime = "142 min")))
        // Channels' videos can be short.
        assertFalse(looksIncomplete(30.0, selection("channel")))
    }

    @Test
    fun `runtimes are read as addons write them`() {
        assertEquals(142, runtimeMinutes("142 min"))
        assertEquals(142, runtimeMinutes("2h 22min"))
        assertEquals(120, runtimeMinutes("2h"))
        assertEquals(102, runtimeMinutes("1:42"))
        assertEquals(45, runtimeMinutes("45"))
        assertNull(runtimeMinutes("N/A"))
    }

    @Test
    fun `lengths read like a clock`() {
        assertEquals("0:30", clockText(30.4))
        assertEquals("1:02:05", clockText(3725.0))
    }
}
