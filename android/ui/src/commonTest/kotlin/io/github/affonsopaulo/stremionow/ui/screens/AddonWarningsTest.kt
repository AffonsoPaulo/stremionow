// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import io.github.affonsopaulo.stremionow.core.AddonInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonWarningsTest {
    private fun addon(
        url: String,
        titleMetadata: Boolean,
        enabled: Boolean = true,
    ) = AddonInfo(
        manifestUrl = url,
        id = url,
        name = url,
        version = "1.0.0",
        description = "",
        logo = null,
        enabled = enabled,
        types = listOf("movie", "series"),
        providesCatalogs = true,
        providesMetadata = titleMetadata,
        providesStreams = false,
        providesSubtitles = false,
        providesTitleMetadata = titleMetadata,
        configurable = false,
        configurationRequired = false,
        configureUrl = null,
        linkHasSettings = false,
    )

    @Test
    fun `warns only for the last enabled source of title information`() {
        val cinemeta = addon("cinemeta", titleMetadata = true)
        val streams = addon("streams", titleMetadata = false)
        assertTrue(isOnlyTitleMetadata(cinemeta, listOf(cinemeta, streams)))
        assertFalse("not a source of information", isOnlyTitleMetadata(streams, listOf(cinemeta, streams)))

        val tmdb = addon("tmdb", titleMetadata = true)
        assertFalse("another addon takes over", isOnlyTitleMetadata(cinemeta, listOf(cinemeta, tmdb)))

        val tmdbOff = addon("tmdb", titleMetadata = true, enabled = false)
        assertTrue("a disabled one does not count", isOnlyTitleMetadata(cinemeta, listOf(cinemeta, tmdbOff)))

        val cinemetaOff = addon("cinemeta", titleMetadata = true, enabled = false)
        assertFalse(
            "turning it back on or removing it while off",
            isOnlyTitleMetadata(cinemetaOff, listOf(cinemetaOff)),
        )
    }
}
