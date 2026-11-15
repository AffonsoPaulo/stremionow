// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import io.github.affonsopaulo.stremionow.core.StreamSource
import io.github.affonsopaulo.stremionow.core.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SourceFiltersTest {
    private fun source(
        name: String,
        addon: String = "Torrentio",
        quality: String? = null,
        hdr: List<String> = emptyList(),
        languages: List<String> = emptyList(),
        sizeBytes: Long? = null,
        seeders: Int? = null,
        url: String? = null,
        infoHash: String? = "0123456789abcdef0123456789abcdef01234567",
        external: String? = null,
        cached: Boolean? = null,
        identity: String? = null,
        description: String = name,
    ) = StreamSource(
        addonName = addon,
        addonId = addon.lowercase(),
        name = "$addon\n${quality.orEmpty()}",
        description = description,
        url = url,
        infoHash = if (url != null || external != null) null else infoHash,
        fileIdx = null,
        externalUrl = external,
        ytId = null,
        badges = emptyList(),
        quality = quality,
        seeders = seeders?.toUInt(),
        size = sizeBytes?.let { "${it / 1_000_000_000} GB" },
        releaseGroup = null,
        subtitles = emptyList(),
        bingeGroup = null,
        trackers = emptyList(),
        identity = identity,
        languages = languages,
        hdr = hdr,
        audio = emptyList(),
        codec = null,
        releaseQuality = null,
        sizeBytes = sizeBytes?.toULong(),
        cached = cached,
    )

    private val remux = source("Hokum.2026.2160p.UHD.BluRay.REMUX", quality = "4K", hdr = listOf("HDR10"), seeders = 35)
    private val dv =
        source(
            "Hokum.2026.2160p.WEB-DL.DV.HDR10+",
            addon = "Comet",
            quality = "4K",
            hdr = listOf("DV", "HDR10+"),
            languages = listOf("en", "it"),
            sizeBytes = 11_000_000_000,
            url = "https://debrid.example/a",
            cached = true,
        )
    private val hd =
        source(
            "Hokum 2026 1080p WEB Português",
            quality = "1080p",
            languages = listOf("pt"),
            sizeBytes = 3_000_000_000,
            seeders = 120,
        )
    private val old = source("Hokum.2026.720p.HDTV", quality = "720p", sizeBytes = 1_000_000_000, seeders = 4)
    private val netflix = source("Watch on Netflix", addon = "WatchHub", external = "https://www.netflix.com/title/1")
    private val ranked = listOf(dv, remux, hd, old, netflix)

    private fun names(sources: List<StreamSource>) = sources.map { it.description }

    @Test
    fun `the search matches names and descriptions ignoring case and accents`() {
        val filters = SourceFilters(query = "portugues WEB")
        assertEquals(listOf(hd.description), names(shownSources(ranked, filters)))
        assertEquals(ranked, shownSources(ranked, SourceFilters(query = "  ")))
        assertEquals(emptyList<String>(), names(shownSources(ranked, SourceFilters(query = "nothing"))))
    }

    @Test
    fun `options of one filter add up and filters narrow each other`() {
        var filters = SourceFilters().toggle(SourceFacet.Resolution, "4K").toggle(SourceFacet.Resolution, "720p")
        assertEquals(names(listOf(dv, remux, old)), names(shownSources(ranked, filters)))
        filters = filters.toggle(SourceFacet.Hdr, SDR)
        assertEquals(names(listOf(old)), names(shownSources(ranked, filters)))
        filters = filters.toggle(SourceFacet.Hdr, SDR).toggle(SourceFacet.Hdr, "DV")
        assertEquals(names(listOf(dv)), names(shownSources(ranked, filters)))
        assertTrue(filters.anySelected)
        // Choosing an option again takes it back.
        assertEquals(setOf("4K", "720p"), filters.toggle(SourceFacet.Hdr, "DV").selected(SourceFacet.Resolution))
        assertFalse(filters.cleared().anySelected)
        assertEquals(ranked, shownSources(ranked, filters.cleared()))
    }

    @Test
    fun `type and language filters`() {
        val inApp = SourceFilters().toggle(SourceFacet.Type, SourceType.InApp.name)
        assertEquals(names(listOf(dv)), names(shownSources(ranked, inApp)))
        val outside = SourceFilters().toggle(SourceFacet.Type, SourceType.Outside.name)
        assertEquals(names(listOf(netflix)), names(shownSources(ranked, outside)))
        val italian = SourceFilters().toggle(SourceFacet.Language, "it").toggle(SourceFacet.Language, "pt")
        assertEquals(names(listOf(dv, hd)), names(shownSources(ranked, italian)))
        assertEquals(SourceType.Torrent, remux.type)
    }

    @Test
    fun `each option counts the sources it would show, given the other filters`() {
        assertEquals(
            listOf(FacetOption("4K", 2, false), FacetOption("1080p", 1, false), FacetOption("720p", 1, false)),
            facetOptions(ranked, SourceFilters(), SourceFacet.Resolution),
        )
        assertEquals(
            listOf(
                FacetOption("Comet", 1, false),
                FacetOption("Torrentio", 3, false),
                FacetOption("WatchHub", 1, false),
            ),
            facetOptions(ranked, SourceFilters(), SourceFacet.Addon),
        )
        assertEquals(
            listOf(
                FacetOption("DV", 1, false),
                FacetOption("HDR10+", 1, false),
                FacetOption("HDR10", 1, false),
                FacetOption(SDR, 3, false),
            ),
            facetOptions(ranked, SourceFilters(), SourceFacet.Hdr),
        )
        // With 4K chosen, the other chips count only 4K sources; options left with none go away.
        val fourK = SourceFilters().toggle(SourceFacet.Resolution, "4K")
        assertEquals(
            listOf(FacetOption("Comet", 1, false), FacetOption("Torrentio", 1, false)),
            facetOptions(ranked, fourK, SourceFacet.Addon),
        )
        // A filter's own options still count as if it were not set, so more can be added.
        assertEquals(
            listOf(FacetOption("4K", 2, true), FacetOption("1080p", 1, false), FacetOption("720p", 1, false)),
            facetOptions(ranked, fourK, SourceFacet.Resolution),
        )
        // An option chosen for another video stays, with no sources, so it can be taken back.
        val gone = SourceFilters().toggle(SourceFacet.Addon, "MediaFusion")
        assertEquals(FacetOption("MediaFusion", 0, true), facetOptions(ranked, gone, SourceFacet.Addon).last())
    }

    @Test
    fun `languages are listed by how many sources have them`() {
        val more = ranked + source("Another 1080p", languages = listOf("pt"))
        assertEquals(
            listOf(FacetOption("pt", 2, false), FacetOption("en", 1, false), FacetOption("it", 1, false)),
            facetOptions(more, SourceFilters(), SourceFacet.Language),
        )
        assertEquals(
            listOf(
                FacetOption(SourceType.InApp.name, 1, false),
                FacetOption(SourceType.Torrent.name, 3, false),
                FacetOption(SourceType.Outside.name, 1, false),
            ),
            facetOptions(ranked, SourceFilters(), SourceFacet.Type),
        )
    }

    @Test
    fun `sorting puts the largest, best seeded or sharpest first and the unknown last`() {
        assertEquals(ranked, sortSources(ranked, SourceSort.Best))
        assertEquals(names(listOf(dv, hd, old, remux, netflix)), names(sortSources(ranked, SourceSort.Size)))
        assertEquals(names(listOf(hd, remux, old, dv, netflix)), names(sortSources(ranked, SourceSort.Seeds)))
        // Equals keep the ranking between them.
        assertEquals(names(listOf(dv, remux, hd, old, netflix)), names(sortSources(ranked, SourceSort.Resolution)))
        assertEquals(6, resolutionRank("8K"))
        assertEquals(1, resolutionRank("SD"))
        assertNull(resolutionRank(null))
        assertNull(resolutionRank("weird"))
    }

    @Test
    fun `the source played before is pinned first when it is among the results`() {
        val played = source("Played", identity = "torrentio|bt|abc|1")
        val shown = listOf(dv, remux, played, hd)
        val layout = sourcesLayout(shown, "torrentio|bt|abc|1")
        assertSame(played, layout.continueWith)
        assertEquals(listOf(dv, remux, hd), layout.grid)
        val missing = sourcesLayout(shown, "torrentio|bt|other|1")
        assertNull(missing.continueWith)
        assertEquals(shown, missing.grid)
        assertEquals(shown, sourcesLayout(shown, null).grid)
    }

    @Test
    fun `the best source is the first of the ranking when the app plays it`() {
        assertSame(dv, bestSource(ranked))
        assertNull(bestSource(listOf(netflix)))
        assertNull(bestSource(emptyList()))
    }

    @Test
    fun `the grid has one to four columns of cards at least 320 dp wide, one on phones`() {
        assertEquals(1, sourceGridColumns(1200f, 12f, phone = true))
        assertEquals(1, sourceGridColumns(600f, 12f, phone = false))
        assertEquals(2, sourceGridColumns(652f, 12f, phone = false))
        assertEquals(3, sourceGridColumns(1000f, 12f, phone = false))
        assertEquals(4, sourceGridColumns(1400f, 12f, phone = false))
        assertEquals(4, sourceGridColumns(3000f, 12f, phone = false))
    }

    @Test
    fun `a card shows the title, one line of details and its status`() {
        assertEquals("Hokum.2026.2160p.WEB-DL.DV.HDR10+", sourceTitle(dv))
        val nameOnly = source("", quality = "1080p", description = "")
        assertEquals("1080p", sourceTitle(nameOnly))
        val full =
            dv.copy(
                size = "11.3 GB",
                audio = listOf("Atmos", "DD+"),
                codec = "HEVC",
                releaseQuality = "WEB-DL",
                releaseGroup = "FLUX",
            )
        assertEquals("11.3 GB · DV · HDR10+ · Atmos · DD+ · HEVC · WEB-DL · FLUX", sourceDetailsLine(full))
        assertEquals(SourceStatus.Cached, sourceStatus(dv))
        assertEquals(SourceStatus.NotCached, sourceStatus(dv.copy(cached = false)))
        assertEquals(SourceStatus.None, sourceStatus(dv.copy(cached = null)))
        assertEquals(SourceStatus.Seeds, sourceStatus(remux))
        assertEquals(SourceStatus.Outside, sourceStatus(netflix))
    }

    @Test
    fun `time left of a video in progress`() {
        fun progress(
            position: Long,
            duration: Long,
        ) = WatchProgress(
            "tt1",
            "movie",
            "Hokum",
            null,
            null,
            "tt1",
            null,
            null,
            position.toULong(),
            duration.toULong(),
            0,
        )
        assertEquals(30L, minutesLeft(progress(position = 1_800_000, duration = 3_600_000)))
        assertEquals(1L, minutesLeft(progress(position = 3_599_000, duration = 3_600_000)))
        assertEquals(1L, minutesLeft(progress(position = 4_000_000, duration = 3_600_000)))
        assertNull(minutesLeft(progress(position = 10, duration = 0)))
    }

    @Test
    fun `languages are named in the app's language`() {
        assertEquals("Italian", languageName("it", Locale.ENGLISH))
        assertEquals("Italiano", languageName("it", Locale.forLanguageTag("pt-BR")))
        assertEquals("QQ", languageName("qq", Locale.ENGLISH))
    }
}
