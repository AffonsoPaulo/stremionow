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
    fun `a release date with an offset keeps the day the addon wrote`() {
        val brazil = Locale.forLanguageTag("pt-BR")
        // 23:00 at -03:00 is already the 12th in UTC.
        assertEquals("11/09/2009", formatReleaseDate("2009-09-11T23:00:00-03:00", brazil))
        assertEquals("11/09/2009", formatReleaseDate("2009-09-11T01:00:00+09:00", brazil))
        assertEquals("11/09/2009", formatReleaseDate(" 2009-09-11T23:00:00.000Z ", brazil))
    }

    private fun unnumbered(id: String) =
        Episode(
            id = id,
            title = id,
            season = null,
            episode = null,
            released = null,
            overview = null,
            thumbnail = null,
            runtimeMinutes = null,
        )

    @Test
    fun `a movie whose addon lists videos is still a movie`() {
        val itself = details(listOf(unnumbered("tt1")))
        assertTrue(itself.asShown().episodes.isEmpty())
        val movie = details(listOf(unnumbered("tt1:a"), unnumbered("tt1:b")))
        assertTrue(
            movie
                .copy(item = movie.item.copy(kind = "movie"))
                .asShown()
                .episodes
                .isEmpty(),
        )
        // A series, and a movie with numbered parts, keep their lists.
        assertEquals(series, series.asShown())
        assertEquals(2, movie.asShown().episodes.size)
        val parts = details(listOf(episode(1, 1), episode(1, 2)))
        assertEquals(
            2,
            parts
                .copy(item = parts.item.copy(kind = "movie"))
                .asShown()
                .episodes.size,
        )
    }

    @Test
    fun `episodes without a season are a group of their own, before the specials`() {
        val mixed = series.episodes + unnumbered("tt1:x") + unnumbered("tt1:y") + episode(1, 1)
        val groups = seasonGroups(mixed)
        assertEquals(listOf(1L, 2L, NO_SEASON, 0L), groups.keys.toList())
        assertEquals("a repeated episode shows once", 3, groups.getValue(1L).size)
        assertEquals(listOf("tt1:x", "tt1:y"), groups.getValue(NO_SEASON).map { it.id })
        val show = details(mixed)
        assertEquals(listOf("tt1:x", "tt1:y"), coveredVideos(show, WatchScope.Season, null, NO_SEASON, now))
        assertEquals(listOf("tt1:0:1"), coveredVideos(show, WatchScope.Season, null, 0L, now))
    }

    @Test
    fun `the details tab shows only with something in it`() {
        val bare = details(emptyList())
        assertFalse(hasInfo(bare))
        assertTrue(hasInfo(bare.copy(runtime = "120 min")))
        assertTrue(hasInfo(bare.copy(cast = listOf("Someone"))))
        assertTrue(hasInfo(bare.copy(item = bare.item.copy(description = "A story."))))
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
        assertEquals(listOf("tt1:1:1", "tt1:1:2", "tt1:1:3"), coveredVideos(series, WatchScope.Season, null, 1L, now))
        assertEquals(
            "not the unreleased one",
            listOf("tt1:2:1"),
            coveredVideos(series, WatchScope.Season, null, 2L, now),
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
        val season = coveredVideos(series, WatchScope.Season, null, 1L, now)
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

    @Test
    fun `marking the episode in progress moves on to the next unwatched released one`() {
        val now = Instant.parse("2024-01-01T00:00:00Z")
        val show =
            details(
                listOf(
                    episode(0, 1),
                    episode(1, 1),
                    episode(1, 2),
                    episode(1, 3),
                    episode(2, 1, released = "2030-01-01T00:00:00.000Z"),
                ),
            )
        // E2 was watched before: it is skipped.
        assertEquals("tt1:1:3", resumeAfterMarking(show, "tt1:1:1", setOf("tt1:1:1", "tt1:1:2"), now)?.id)
        // Only an unreleased episode left, and specials stay apart from the seasons.
        assertNull(resumeAfterMarking(show, "tt1:1:3", setOf("tt1:1:3"), now))
        assertNull(resumeAfterMarking(show, "tt1:0:1", setOf("tt1:0:1"), now))
        assertNull(resumeAfterMarking(show, "tt9:1:1", emptySet(), now))
    }

    private fun source(
        addon: String,
        quality: String? = null,
        url: String? = "https://cdn.example/v.mkv",
        external: String? = null,
    ) = StreamSource(
        addonName = addon,
        addonId = addon.lowercase(),
        addonKey = "key-${addon.lowercase()}",
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
        identity = null,
        languages = emptyList(),
        hdr = emptyList(),
        audio = emptyList(),
        codec = null,
        releaseQuality = null,
        sizeBytes = null,
        cached = null,
    )

    @Test
    fun `an empty search for sources says why it found nothing`() {
        assertNull("still searching", SourcesState(addonsAsked = 2).emptyReason)
        assertEquals(SourcesEmpty.NoAddons, SourcesState(finished = true).emptyReason)
        assertEquals(SourcesEmpty.AllFailed, SourcesState(finished = true, listFailed = true).emptyReason)
        assertEquals(
            SourcesEmpty.AllFailed,
            SourcesState(addonsAsked = 2, addonsAnswered = 2, addonsFailed = 2, finished = true).emptyReason,
        )
        assertEquals(
            SourcesEmpty.NoSources,
            SourcesState(addonsAsked = 2, addonsAnswered = 2, addonsFailed = 1, finished = true).emptyReason,
        )
        assertNull(SourcesState(sources = listOf(source("A")), finished = true).emptyReason)
    }

    @Test
    fun `only a search that found sources from every addon is kept for later`() {
        val found = listOf(source("A"))
        assertTrue("one still under way", SourcesState(addonsAsked = 2).reusable)
        assertTrue(SourcesState(sources = found, addonsAsked = 1, addonsAnswered = 1, finished = true).reusable)
        assertFalse(SourcesState(addonsAsked = 1, addonsAnswered = 1, finished = true).reusable)
        assertFalse(
            SourcesState(sources = found, addonsAsked = 2, addonsAnswered = 2, addonsFailed = 1, finished = true)
                .reusable,
        )
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
