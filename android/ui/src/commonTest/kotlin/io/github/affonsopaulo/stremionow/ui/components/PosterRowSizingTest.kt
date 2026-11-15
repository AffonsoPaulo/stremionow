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
    fun `two rows and a half fit in the window, the last card cut in half`() {
        val sizing = posterRowSizing(1918.dp, 1034.dp, 48.dp)
        // (1034 - 96) / 2.5 = 375 a row; less 150 of text and gaps = 225 of picture, 150 wide.
        assertEquals(150f, sizing.cardWidth.value, 0.5f)
        assertTrue(sizing.spacing >= MIN_POSTER_SPACING)
        assertEquals(0.5f, lastCardShown(sizing, 1918.dp, 48.dp), 0.01f)
    }

    @Test
    fun `cards keep their bounds in tiny and huge windows`() {
        assertEquals(MIN_POSTER_CARD_WIDTH, posterRowSizing(800.dp, 500.dp, 24.dp).cardWidth)
        assertEquals(MAX_POSTER_CARD_WIDTH, posterRowSizing(3800.dp, 2100.dp, 48.dp).cardWidth)
        assertTrue(posterRowSizing(400.dp, 500.dp, 24.dp).spacing >= MIN_POSTER_SPACING)
    }
}
