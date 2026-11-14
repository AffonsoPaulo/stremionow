// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import io.github.affonsopaulo.stremionow.core.AddonInfo
import io.github.affonsopaulo.stremionow.core.Episode
import io.github.affonsopaulo.stremionow.core.LastSource
import io.github.affonsopaulo.stremionow.core.MetaDetails
import io.github.affonsopaulo.stremionow.core.MetaItem
import io.github.affonsopaulo.stremionow.core.StreamSource
import io.github.affonsopaulo.stremionow.core.WatchProgress
import io.github.affonsopaulo.stremionow.ui.data.PlaybackSession
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumePlaybackTest {
    private fun episode(number: Int) =
        Episode(
            id = "tt1:1:$number",
            title = "E$number",
            season = 1u,
            episode = number.toUInt(),
            released = "2020-01-01T00:00:00.000Z",
            overview = null,
            thumbnail = null,
            runtimeMinutes = null,
        )

    private val series = details(listOf(episode(1), episode(2)))
    private val movie = details(emptyList())

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

    private fun source(
        identity: String?,
        group: String? = null,
        url: String? = "https://debrid.example/signed",
        infoHash: String? = null,
        external: String? = null,
        addonId: String = "torrentio",
    ) = StreamSource(
        addonName = "Torrentio",
        addonId = addonId,
        name = identity.orEmpty(),
        description = "",
        url = url,
        infoHash = infoHash,
        fileIdx = null,
        externalUrl = external,
        ytId = null,
        badges = emptyList(),
        quality = null,
        seeders = null,
        size = null,
        releaseGroup = null,
        subtitles = emptyList(),
        bingeGroup = group,
        trackers = emptyList(),
        identity = identity,
        languages = emptyList(),
        hdr = emptyList(),
        audio = emptyList(),
        codec = null,
        releaseQuality = null,
        sizeBytes = null,
        cached = null,
    )

    private fun addon(id: String) =
        AddonInfo(
            manifestUrl = "https://$id.example/manifest.json",
            id = id,
            name = id,
            version = "1.0.0",
            description = "",
            logo = null,
            enabled = true,
            types = listOf("movie", "series"),
            providesCatalogs = false,
            providesMetadata = false,
            providesStreams = true,
            providesSubtitles = false,
            providesTitleMetadata = false,
            configurable = false,
            configurationRequired = false,
            configureUrl = null,
            linkHasSettings = false,
            fingerprint = "",
        )

    /** Addons answering from memory; [answer] gives each addon's sources for a video. */
    private class FakeFinder(
        val installed: List<AddonInfo>,
        val answer: suspend (AddonInfo, String) -> List<StreamSource>,
    ) : SourceFinder {
        val asked = mutableListOf<Pair<String, String>>()

        override suspend fun addons(
            kind: String,
            videoId: String,
        ) = installed

        override suspend fun sources(
            addon: AddonInfo,
            kind: String,
            videoId: String,
        ): List<StreamSource> {
            asked += addon.id to videoId
            return answer(addon, videoId)
        }
    }

    private val last =
        LastSource(
            videoId = "tt1:1:1",
            addonId = "torrentio",
            identity = "torrentio|url|aaa",
            bingeGroup = "torrentio|1080p",
        )

    private fun request(
        videoId: String,
        details: MetaDetails? = series,
        remembered: LastSource = last,
    ) = PlaybackSession.Resume(
        kind = details?.item?.kind ?: "series",
        titleId = "tt1",
        name = "Title",
        videoId = videoId,
        last = remembered,
        details = details,
    )

    private fun resolve(
        request: PlaybackSession.Resume,
        finder: SourceFinder,
        loadDetails: suspend () -> MetaDetails = { series },
        torrentsAllowed: Boolean = true,
        timeoutMs: Long = RESUME_TIMEOUT_MS,
    ) = runBlocking { resolveResume(request, finder, loadDetails, { torrentsAllowed }, timeoutMs) }

    @Test
    fun `the same source is found again for the same video`() {
        val wanted = source("torrentio|url|aaa", "torrentio|1080p")
        val finder =
            FakeFinder(listOf(addon("other"), addon("torrentio"))) { _, _ ->
                listOf(source("torrentio|url|bbb", "torrentio|1080p"), wanted)
            }
        val result = resolve(request("tt1:1:1"), finder)
        assertEquals(ResumeResult.Play(PlaybackSession.Selection(series, episode(1)), wanted), result)
        assertEquals(
            "only the addon of the source chosen before is asked",
            listOf("torrentio" to "tt1:1:1"),
            finder.asked,
        )
    }

    @Test
    fun `a movie resumes with its own source`() {
        val wanted = source("torrentio|url|aaa")
        val finder = FakeFinder(listOf(addon("torrentio"))) { _, _ -> listOf(wanted) }
        val result = resolve(request("tt1", details = movie, remembered = last.copy(videoId = "tt1")), finder)
        assertEquals(ResumeResult.Play(PlaybackSession.Selection(movie, null), wanted), result)
    }

    @Test
    fun `a link signed anew falls back to the binge group`() {
        val resigned = source("torrentio|url|new", "torrentio|1080p")
        val finder =
            FakeFinder(listOf(addon("torrentio"))) { _, _ ->
                listOf(source("torrentio|url|other", "torrentio|720p"), resigned)
            }
        val result = resolve(request("tt1:1:1"), finder)
        assertEquals(resigned, (result as ResumeResult.Play).source)
    }

    @Test
    fun `the next episode plays the first source of the same binge group`() {
        val sameGroup = source("torrentio|url|e2", "torrentio|1080p")
        val finder =
            FakeFinder(listOf(addon("torrentio"))) { _, video ->
                assertEquals("tt1:1:2", video)
                listOf(
                    source("torrentio|url|link-out", "torrentio|1080p", url = null, external = "https://x.example"),
                    source("torrentio|url|e2-720", "torrentio|720p"),
                    sameGroup,
                    source("torrentio|url|e2-later", "torrentio|1080p"),
                )
            }
        val result = resolve(request("tt1:1:2"), finder)
        assertEquals(ResumeResult.Play(PlaybackSession.Selection(series, episode(2)), sameGroup), result)
    }

    @Test
    fun `no match shows the video's sources`() {
        val finder = FakeFinder(listOf(addon("torrentio"))) { _, _ -> listOf(source("torrentio|url|x", "other")) }
        assertEquals(
            ResumeResult.Sources(PlaybackSession.Selection(series, episode(2))),
            resolve(request("tt1:1:2"), finder),
        )
    }

    @Test
    fun `an addon that fails, answers slowly or is gone shows the video's sources`() {
        val selection = PlaybackSession.Selection(series, episode(1))
        val failing = FakeFinder(listOf(addon("torrentio"))) { _, _ -> error("the addon is down") }
        assertEquals(ResumeResult.Sources(selection), resolve(request("tt1:1:1"), failing))

        val slow = FakeFinder(listOf(addon("torrentio"))) { _, _ -> awaitCancellation() }
        assertEquals(ResumeResult.Sources(selection), resolve(request("tt1:1:1"), slow, timeoutMs = 50))

        val uninstalled = FakeFinder(listOf(addon("other"))) { _, _ -> listOf(source("torrentio|url|aaa")) }
        assertEquals(ResumeResult.Sources(selection), resolve(request("tt1:1:1"), uninstalled))
        assertTrue("an addon of another id is never asked", uninstalled.asked.isEmpty())
    }

    @Test
    fun `a title that cannot be told shows its page alone`() {
        val finder = FakeFinder(listOf(addon("torrentio"))) { _, _ -> listOf(source("torrentio|url|aaa")) }
        assertEquals(
            "the details fail",
            ResumeResult.Sources(null),
            resolve(request("tt1:1:1", details = null), finder, loadDetails = { error("offline") }),
        )
        assertEquals(
            "the episode is gone",
            ResumeResult.Sources(null),
            resolve(request("tt1:1:9"), finder),
        )
        // Continue watching knows no details: they are loaded first.
        val loaded = resolve(request("tt1:1:1", details = null), finder, loadDetails = { series })
        assertNotNull((loaded as ResumeResult.Play).selection.episode)
    }

    @Test
    fun `a torrent on mobile data with Only on Wi-Fi leaves the choice to the list`() {
        val torrent = source("torrentio|bt|hash|", url = null, infoHash = "0123456789abcdef0123456789abcdef01234567")
        val finder = FakeFinder(listOf(addon("torrentio"))) { _, _ -> listOf(torrent) }
        val remembered = last.copy(identity = "torrentio|bt|hash|")
        assertEquals(
            ResumeResult.Sources(PlaybackSession.Selection(series, episode(1))),
            resolve(request("tt1:1:1", remembered = remembered), finder, torrentsAllowed = false),
        )
        assertTrue(resolve(request("tt1:1:1", remembered = remembered), finder) is ResumeResult.Play)
    }

    @Test
    fun `resuming needs a source that could find the video`() {
        assertEquals(SourceWanted("torrentio", "torrentio|url|aaa", "torrentio|1080p"), last.wantedFor("tt1:1:1"))
        assertEquals(SourceWanted("torrentio", bingeGroup = "torrentio|1080p"), last.wantedFor("tt1:1:2"))
        assertNull("another video and no group", last.copy(bingeGroup = null).wantedFor("tt1:1:2"))

        val progress =
            WatchProgress(
                id = "tt1",
                kind = "series",
                name = "Title",
                poster = null,
                background = null,
                videoId = "tt1:1:2",
                season = 1u,
                episode = 2u,
                // Moved on once the episode before was finished.
                positionMs = 1u,
                durationMs = 1_000_000u,
                updatedAt = 0,
            )
        assertNull("nothing remembered opens the title's page", resumeRequest(progress, null))
        assertNull(resumeRequest(progress, last.copy(bingeGroup = null)))
        assertEquals("tt1:1:2", resumeRequest(progress, last)?.videoId)
        assertNull(resumeRequest(progress, last)?.details)

        assertEquals(series, resumeRequest(series, episode(1), last)?.details)
        assertNull(resumeRequest(series, episode(2), last.copy(bingeGroup = null)))
    }

    @Test
    fun `sources that open outside the app are never picked`() {
        val outside = source("torrentio|url|aaa", "g", url = null, external = "https://x.example")
        assertNull(pickSource(listOf(outside), SourceWanted("torrentio", "torrentio|url|aaa", "g")))
        assertNull(pickSource(emptyList(), SourceWanted("torrentio", "torrentio|url|aaa", "g")))
    }
}
