// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SnFlagTextTest {
    @Test
    fun `flag emojis become country codes between the plain text`() {
        assertEquals(
            listOf(
                FlagTextPart.Plain("Multi Audio / "),
                FlagTextPart.Flag("gb"),
                FlagTextPart.Plain(" / "),
                FlagTextPart.Flag("br"),
            ),
            splitFlags("Multi Audio / 🇬🇧 / 🇧🇷"),
        )
    }

    @Test
    fun `flags side by side and text without flags`() {
        assertEquals(
            listOf(FlagTextPart.Flag("jp"), FlagTextPart.Flag("kr")),
            splitFlags("🇯🇵🇰🇷"),
        )
        assertEquals(listOf(FlagTextPart.Plain("1080p WEB-DL")), splitFlags("1080p WEB-DL"))
        assertEquals(emptyList<FlagTextPart>(), splitFlags(""))
    }

    @Test
    fun `a lone regional indicator stays text`() {
        assertEquals(listOf(FlagTextPart.Plain("x 🇬 y")), splitFlags("x 🇬 y"))
    }
}
