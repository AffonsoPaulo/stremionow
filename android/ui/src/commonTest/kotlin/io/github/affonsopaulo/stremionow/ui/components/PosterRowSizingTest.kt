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
        // 1870 of room: 8 cards of 175 and 48 (1784), and 86 of the ninth, half of it.
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
