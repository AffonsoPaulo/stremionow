// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class PosterRowSizingTest {
    @Test
    fun `eight whole cards and half a ninth fill the row after the gutter`() {
        val width = posterCardWidth(1918.dp, 48.dp, 24.dp)
        // 48 + 8.5 cards + 8 gaps of 24 = 1918.
        assertEquals(197.4f, width.value, 0.1f)
    }

    @Test
    fun `cards never shrink below the phone size nor grow past the cap`() {
        assertEquals(PosterCardWidth, posterCardWidth(900.dp, 24.dp, 24.dp))
        assertEquals(MAX_POSTER_CARD_WIDTH, posterCardWidth(4000.dp, 48.dp, 24.dp))
    }
}
