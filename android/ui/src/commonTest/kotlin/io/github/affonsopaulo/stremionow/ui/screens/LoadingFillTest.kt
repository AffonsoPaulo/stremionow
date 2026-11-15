// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadingFillTest {
    @Test
    fun `the stage follows the player's progress`() {
        assertEquals(OpeningStage.PREPARING, OpeningStage.of(preparing = true, loaded = false))
        assertEquals(OpeningStage.OPENING, OpeningStage.of(preparing = false, loaded = false))
        assertEquals(OpeningStage.DONE, OpeningStage.of(preparing = false, loaded = true))
        // A new source while the old one played: done is done.
        assertEquals(OpeningStage.DONE, OpeningStage.of(preparing = true, loaded = true))
    }

    @Test
    fun `stages head further along and only the last one completes`() {
        val targets = OpeningStage.entries.map { it.target }
        assertEquals(targets.sorted(), targets)
        assertTrue(OpeningStage.entries.dropLast(1).all { it.target < 1f })
        assertEquals(1f, OpeningStage.DONE.target)
    }
}
