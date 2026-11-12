// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonFiltersTest {
    @Test
    fun `declared types map to the filter kinds`() {
        assertEquals(AddonType.Movies, addonType("movie"))
        assertEquals(AddonType.Series, addonType(" Series "))
        assertEquals(AddonType.Channels, addonType("channel"))
        assertEquals(AddonType.Tv, addonType("tv"))
        assertEquals(AddonType.Anime, addonType("anime"))
        assertEquals(AddonType.Other, addonType("Podcasts"))
        assertEquals(AddonType.Other, addonType(""))
        assertEquals(setOf(AddonType.Other), addonTypes(emptyList()))
    }

    @Test
    fun `options list the kinds present in chip order`() {
        val options = typeOptions(listOf(listOf("series", "movie"), listOf("Dice"), listOf("anime", "movie")))
        assertEquals(listOf(AddonType.Movies, AddonType.Series, AddonType.Anime, AddonType.Other), options)
    }

    @Test
    fun `no options when there is nothing to choose between`() {
        assertEquals(emptyList<AddonType>(), typeOptions(emptyList()))
        assertEquals(emptyList<AddonType>(), typeOptions(listOf(listOf("movie"), listOf("Movie", "movies"))))
    }

    @Test
    fun `search ignores case, accents and word order`() {
        assertTrue(matchesQuery("", "Anything"))
        assertTrue(matchesQuery("  ", "Anything"))
        assertTrue(matchesQuery("legendas", "OpenSubtitles", "Legendas em português"))
        assertTrue(matchesQuery("PORTUGUES legendas", "OpenSubtitles", "Legendas em português"))
        assertTrue(matchesQuery("cinemeta", "Cinemeta", "", "com.linvo.cinemeta"))
        assertTrue(matchesQuery("linvo", "Cinemeta", "", "com.linvo.cinemeta"))
        assertFalse(matchesQuery("torrent", "Cinemeta", "The official addon for movie and series catalogs"))
        assertFalse(matchesQuery("legendas francês", "OpenSubtitles", "Legendas em português"))
    }
}
