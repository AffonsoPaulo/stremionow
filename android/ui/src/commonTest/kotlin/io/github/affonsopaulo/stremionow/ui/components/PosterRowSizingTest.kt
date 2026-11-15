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
    fun `eight whole cards and half a ninth fill the row`() {
        val sizing = posterRowSizing(1918.dp, 48.dp)
        // 48 + 8.5 cards + 8 gaps of 48 = 1918.
        assertEquals(174.8f, sizing.cardWidth.value, 0.1f)
        assertEquals(POSTER_SPACING, sizing.spacing)
        assertEquals(0.5f, lastCardShown(sizing, 1918.dp, 48.dp), 0.01f)
    }

    @Test
    fun `cards keep their bounds, still with half a card showing`() {
        val narrow = posterRowSizing(1000.dp, 24.dp)
        assertEquals(MIN_POSTER_CARD_WIDTH, narrow.cardWidth)
        assertTrue(narrow.spacing >= MIN_POSTER_SPACING)
        assertEquals(0.5f, lastCardShown(narrow, 1000.dp, 24.dp), 0.01f)
        assertEquals(MAX_POSTER_CARD_WIDTH, posterRowSizing(3800.dp, 48.dp).cardWidth)
    }
}
