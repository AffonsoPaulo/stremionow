// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import io.github.affonsopaulo.stremionow.core.Episode
import io.github.affonsopaulo.stremionow.core.MetaDetails
import io.github.affonsopaulo.stremionow.core.MetaItem
import io.github.affonsopaulo.stremionow.core.StreamSource
import io.github.affonsopaulo.stremionow.core.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Locale

class DetailsLogicTest {
    private val now = Instant.parse("2026-11-14T12:00:00Z")

    private fun episode(
        season: Int,
        number: Int,
        released: String? = "2020-01-01T00:00:00.000Z",
    ) = Episode(
        id = "tt1:$season:$number",
        title = "E$number",
        season = season.toUInt(),
        episode = number.toUInt(),
        released = released,
        overview = null,
        thumbnail = null,
        runtimeMinutes = null,
    )

    private fun details(episodes: List<Episode>) =
        MetaDetails(
            item =
                MetaItem(
                    "tt1",
                    if (episodes.isEmpty()) "movie" else "series",
                    "Title",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    emptyList(),
                ),
            runtime = null,
            cast = emptyList(),
            directors = emptyList(),
            episodes = episodes,
            trailerYoutubeIds = emptyList(),
            shareUrl = null,
        )

    private fun progress(
        videoId: String,
        position: Long = 300_000,
        duration: Long = 1_200_000,
    ) = WatchProgress(
        "tt1",
        "series",
        "Title",
        null,
        null,
        videoId,
        null,
        null,
        position.toULong(),
        duration.toULong(),
        0,
    )

    private val series =
        details(
            listOf(
                episode(0, 1),
                episode(1, 1),
                episode(1, 2),
                episode(1, 3),
                episode(2, 1),
                episode(2, 2, released = "2027-01-01T00:00:00.000Z"),
            ),
        )

    @Test
    fun `release dates follow the region's short format with the full year`() {
        val date = "2009-09-11T00:00:00.000Z"
        assertEquals("11/09/2009", formatReleaseDate(date, Locale.forLanguageTag("pt-BR")))
        assertEquals("9/11/2009", formatReleaseDate(date, Locale.US))
        assertEquals("11/09/2009", formatReleaseDate("2009-09-11", Locale.forLanguageTag("pt-BR")))
        assertNull(formatReleaseDate(null, Locale.US))
        assertNull(formatReleaseDate("soon", Locale.US))
    }

    @Test
    fun `episodes without a date count as released, future ones do not`() {
        assertTrue(episode(1, 1, released = null).isReleased(now))
        assertTrue(episode(1, 1, released = "2026-11-14T12:00:00Z").isReleased(now))
        assertFalse(episode(1, 1, released = "2026-11-15T00:00:00.000Z").isReleased(now))
    }

    @Test
    fun `a mark covers the released episodes of its scope`() {
        assertEquals(listOf("tt1:1:2"), coveredVideos(series, WatchScope.Episode, episode(1, 2), null, now))
        assertEquals(listOf("tt1:1:1", "tt1:1:2", "tt1:1:3"), coveredVideos(series, WatchScope.Season, null, 1u, now))
        assertEquals(
            "not the unreleased one",
            listOf("tt1:2:1"),
            coveredVideos(series, WatchScope.Season, null, 2u, now),
        )
        assertEquals(
            listOf("tt1:0:1", "tt1:1:1", "tt1:1:2", "tt1:1:3", "tt1:2:1"),
            coveredVideos(series, WatchScope.Series, null, null, now),
        )
        assertEquals(
            "an unreleased episode is never marked",
            emptyList<String>(),
            coveredVideos(series, WatchScope.Episode, episode(2, 2, released = "2027-01-01T00:00:00.000Z"), null, now),
        )
        assertEquals(
            "a movie is itself",
            listOf("tt1"),
            coveredVideos(details(emptyList()), WatchScope.Series, null, null, now),
        )
    }

    @Test
    fun `a mark toggles to unwatched once everything it covers is watched`() {
        val season = coveredVideos(series, WatchScope.Season, null, 1u, now)
        assertFalse(allWatched(season, setOf("tt1:1:1", "tt1:1:2")))
        assertTrue(allWatched(season, setOf("tt1:1:1", "tt1:1:2", "tt1:1:3", "tt1:2:1")))
        assertFalse("nothing to mark is never all watched", allWatched(emptyList(), setOf("tt1:1:1")))
    }

    @Test
    fun `the main button resumes the episode in progress`() {
        val next = nextUp(series, progress("tt1:1:2"), emptySet(), now)
        assertEquals("tt1:1:2", next.episode?.id)
        assertEquals(0.25f, next.progress!!, 0.001f)
        assertTrue(next.started)
    }

    @Test
    fun `without progress it goes to the next unwatched episode, else starts at the first`() {
        val afterWatched = nextUp(series, null, setOf("tt1:1:1", "tt1:1:2"), now)
        assertEquals("tt1:1:3", afterWatched.episode?.id)
        assertTrue(afterWatched.started)
        assertNull(afterWatched.progress)

        val fresh = nextUp(series, null, emptySet(), now)
        assertEquals("skips the specials", "tt1:1:1", fresh.episode?.id)
        assertFalse(fresh.started)

        // Everything released is watched; the next one is not out yet.
        val caughtUp = nextUp(series, null, setOf("tt1:1:1", "tt1:1:2", "tt1:1:3", "tt1:2:1"), now)
        assertEquals("tt1:1:1", caughtUp.episode?.id)
        assertFalse(caughtUp.started)
    }

    @Test
    fun `a movie resumes with its progress or starts`() {
        val movie = details(emptyList())
        assertFalse(nextUp(movie, null, emptySet(), now).started)
        val resumed = nextUp(movie, progress("tt1", position = 600_000), emptySet(), now)
        assertTrue(resumed.started)
        assertEquals(0.5f, resumed.progress!!, 0.001f)
        assertNull(progress("tt1", duration = 0).fraction())
    }

    private fun source(
        addon: String,
        quality: String? = null,
        url: String? = "https://cdn.example/v.mkv",
        external: String? = null,
    ) = StreamSource(
        addonName = addon,
        name = addon,
        description = "",
        url = url,
        infoHash = null,
        fileIdx = null,
        externalUrl = external,
        ytId = null,
        badges = emptyList(),
        quality = quality,
        seeders = null,
        size = null,
        releaseGroup = null,
        subtitles = emptyList(),
        bingeGroup = null,
        trackers = emptyList(),
    )

    @Test
    fun `the filter lists each addon with its count, in the sources' order`() {
        val sources = listOf(source("B"), source("A"), source("B"), source("C"))
        assertEquals(listOf("B" to 2, "A" to 1, "C" to 1), addonCounts(sources))
        assertEquals(2, sources.fromAddon("B").size)
        assertEquals(4, sources.fromAddon(null).size)
        assertEquals(emptyList<Pair<String, Int>>(), addonCounts(emptyList()))
    }

    @Test
    fun `4K is available only when a playable 4K source was found`() {
        assertFalse(hasPlayable4k(emptyList()))
        assertFalse(hasPlayable4k(listOf(source("A", "1080p"))))
        assertFalse(
            "a link out says nothing about what the app can play",
            hasPlayable4k(listOf(source("A", "4K", url = null, external = "https://www.netflix.com/title/1"))),
        )
        assertTrue(hasPlayable4k(listOf(source("A", "1080p"), source("B", "4K"))))
    }

    @Test
    fun `a service's initial comes from its site's name`() {
        assertEquals("N", serviceInitial("https://www.netflix.com/title/80057281", "WatchHub"))
        assertEquals("P", serviceInitial("https://primevideo.com/detail/x", "WatchHub"))
        assertEquals("A", serviceInitial("https://tv.apple.com/show/x", "WatchHub"))
        assertEquals("W", serviceInitial(null, "WatchHub"))
        assertEquals("?", serviceInitial(null, ""))
    }

    @Test
    fun `source text keeps the title lines and drops the emoji stats line`() {
        assertEquals(
            listOf("Movie.2023.2160p.WEB-DL-GRP", "Multi Audio"),
            descriptionLines("Movie.2023.2160p.WEB-DL-GRP\n👤 154 💾 14.2 GB ⚙️ TPB\n\nMulti Audio\n"),
        )
    }
}
