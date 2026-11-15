// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterRowSizingTest {
    /** How much of the last card shows, after the whole ones and their gaps. */
    private fun lastCardShown(
        sizing: PosterRowSizing,
        rowWidth: Dp,
        gutter: Dp,
    ): Float {
        val room = rowWidth - gutter
        val whole = (room / (sizing.cardWidth + sizing.spacing)).toInt()
        return (room - (sizing.cardWidth + sizing.spacing) * whole) / sizing.cardWidth
    }

    @Test
    fun `cards keep their size while the last one shows between a quarter and three quarters`() {
        // 1870 of room: 9 cards of 160 and 40 (1800), and 70 of the tenth, 44% of it.
        val sizing = posterRowSizing(1918.dp, 48.dp)
        assertEquals(POINTER_POSTER_CARD_WIDTH, sizing.cardWidth)
        assertEquals(POSTER_SPACING, sizing.spacing)
    }

    @Test
    fun `a last card barely or almost wholly in view makes the cards fit`() {
        for (width in listOf(1800, 1830, 2010, 1500, 1280, 2560)) {
            val sizing = posterRowSizing(width.dp, 48.dp)
            val shown = lastCardShown(sizing, width.dp, 48.dp)
            assertTrue("$width: $shown", shown in 0.24f..0.76f)
            assertTrue("$width: ${sizing.cardWidth}", sizing.cardWidth in MIN_POSTER_CARD_WIDTH..MAX_POSTER_CARD_WIDTH)
        }
    }
}
