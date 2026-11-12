// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnHeroTest {
    @Test
    fun `the hero keeps the picture's shape until the window's height caps it`() {
        // 1280 x 800: 16:9 would be 720, capped at three quarters of the height.
        assertEquals(600.dp, heroHeight(1280.dp, 800.dp, min = 480.dp))
        // A tall window keeps 16:9.
        assertEquals(450.dp, heroHeight(800.dp, 1200.dp, min = 400.dp))
        // Maximized on 1920 x 1080: 810 instead of a thin strip.
        assertEquals(810.dp, heroHeight(1920.dp, 1080.dp, min = 480.dp))
    }

    @Test
    fun `a narrow or short window still fits the hero's text`() {
        assertEquals(480.dp, heroHeight(600.dp, 1000.dp, min = 480.dp))
        assertEquals(480.dp, heroHeight(2560.dp, 500.dp, min = 480.dp))
    }

    @Test
    fun `asks metahub for its large backdrop`() {
        assertEquals(
            "https://images.metahub.space/background/large/tt0063350/img",
            largerBackdrop("https://images.metahub.space/background/medium/tt0063350/img"),
        )
        assertEquals(
            "https://images.metahub.space/background/large/tt1/img",
            largerBackdrop("https://images.metahub.space/background/small/tt1/img"),
        )
    }

    @Test
    fun `leaves other images alone`() {
        assertNull(largerBackdrop("https://images.metahub.space/background/large/tt1/img"))
        assertNull(largerBackdrop("https://images.metahub.space/poster/medium/tt1/img"))
        assertNull(largerBackdrop("http://images.metahub.space/background/medium/tt1/img"))
        assertNull(largerBackdrop("https://example.com/background/medium/tt1/img"))
        assertNull(largerBackdrop("https://images.metahub.space/background/medium"))
    }
}
