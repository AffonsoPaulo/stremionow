// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import io.github.affonsopaulo.stremionow.core.AppSettings
import io.github.affonsopaulo.stremionow.player.SubtitleStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleStyleTest {
    private fun SubtitleStyle.option(name: String): String = mpvOptions().first { name in it.names }.value

    @Test
    fun defaultsAreStremios() {
        val style = defaultSettings().subtitleStyle()
        assertEquals(SubtitleStyle(), style)
        assertEquals(SubtitleStyle(), (null as AppSettings?).subtitleStyle())
        assertEquals("1.00", style.option("sub-scale"))
        assertEquals("100", style.option("sub-pos"))
        assertEquals("#FFFFFFFF", style.option("sub-color"))
        assertEquals("#00000000", style.option("sub-back-color"))
        assertEquals("#FF000000", style.option("sub-border-color"))
        assertEquals("outline-and-shadow", style.option("sub-border-style"))
        assertEquals("force", style.option("sub-ass-override"))
    }

    @Test
    fun settingsBecomeMpvOptions() {
        val style =
            defaultSettings()
                .copy(
                    subtitleSizePercent = 130u,
                    subtitlePosition = 80u,
                    subtitleTextColor = 0xFFFFE600u,
                    subtitleBackgroundColor = 0x99000000u,
                    subtitleOutlineColor = 0u,
                    subtitleFileStyling = true,
                ).subtitleStyle()
        assertEquals("1.30", style.option("sub-scale"))
        assertEquals("80", style.option("sub-pos"))
        assertEquals("#FFFFE600", style.option("sub-color"))
        assertEquals("#99000000", style.option("sub-back-color"))
        assertEquals("#00000000", style.option("sub-border-color"))
        assertEquals("a background draws a box", "background-box", style.option("sub-border-style"))
        assertEquals("styled files keep their look", "scale", style.option("sub-ass-override"))
    }

    @Test
    fun theOutlineIsTriedUnderItsNewNameFirst() {
        val outline = SubtitleStyle().mpvOptions().first { "sub-border-color" in it.names }
        assertEquals(listOf("sub-outline-color", "sub-border-color"), outline.names)
    }

    @Test
    fun outOfRangeValuesAreBroughtIn() {
        val style = defaultSettings().copy(subtitleSizePercent = 999u, subtitlePosition = 0u).subtitleStyle()
        assertEquals(SubtitleStyle.SIZE_RANGE.last, style.sizePercent)
        assertEquals(SubtitleStyle.POSITION_RANGE.first, style.position)
    }

    @Test
    fun everyOfferedColorHasAName() {
        for (colors in listOf(SubtitleTextColors, SubtitleBackgroundColors, SubtitleOutlineColors)) {
            assertEquals(colors.size, colors.map { it.argb }.distinct().size)
        }
        assertTrue(SubtitleTextColors.first().argb == SubtitleStyle.WHITE)
        assertTrue(SubtitleBackgroundColors.first().argb == SubtitleStyle.TRANSPARENT)
        assertTrue(SubtitleOutlineColors.first().argb == SubtitleStyle.BLACK)
    }
}
