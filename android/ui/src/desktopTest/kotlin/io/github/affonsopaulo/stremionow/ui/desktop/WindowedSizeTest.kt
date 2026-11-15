// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Dimension
import java.awt.Rectangle

/**
 * The player's window keeps its size in dp across scale changes, whatever AWT did to each window
 * on the way.
 */
class WindowedSizeTest {
    @Test
    fun keepsTheSizeInDpAcrossScales() {
        val size = WindowedSize()
        size.remember(Rectangle(0, 0, 960, 540), 1.0)
        assertEquals(Dimension(1440, 810), size.at(1.5))
        assertEquals(Dimension(1200, 675), size.at(1.25))
        assertEquals(Dimension(960, 540), size.at(1.0))
    }

    @Test
    fun aDragBackAndForthAcrossABoundaryDoesNotShrinkTheWindow() {
        val size = WindowedSize()
        // 853 x 480 dp taken on a 150 % monitor.
        size.remember(Rectangle(0, 0, 1279, 720), 1.5)
        // However often the scale flips while crossing, the target comes from the size in dp.
        repeat(5) {
            size.at(1.0)
            size.at(1.5)
        }
        assertEquals(Dimension(853, 480), size.at(1.0))
        assertEquals(Dimension(1279, 720), size.at(1.5))
    }

    @Test
    fun aScaleChangeIsPendingUntilApplied() {
        val size = WindowedSize()
        size.remember(Rectangle(0, 0, 960, 540), 1.0)
        assertTrue(size.isCurrentAt(1.0))
        assertFalse(size.isCurrentAt(1.5))
        size.appliedAt(1.5)
        assertTrue(size.isCurrentAt(1.5))
        // Resizing at the new scale is the user's choice and becomes the size.
        size.remember(Rectangle(0, 0, 1800, 900), 1.5)
        assertEquals(Dimension(1200, 600), size.at(1.0))
    }
}
