// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Dimension
import java.awt.Rectangle

class FirstWindowTest {
    private val wanted = Dimension(1280, 800)

    @Test
    fun aWindowThatFitsIsCentredInTheWorkArea() {
        // 1920 × 1080 with a 48 px taskbar at the bottom.
        val placement = FirstWindow.place(wanted, Rectangle(0, 0, 1920, 1032))
        assertFalse(placement.maximized)
        assertEquals(Rectangle(320, 116, 1280, 800), placement.bounds)
    }

    @Test
    fun aTallerWindowOpensMaximizedAndRestoresInsideTheWorkArea() {
        // 1366 × 768 with the taskbar: centred on the screen, the title bar was above it.
        val placement = FirstWindow.place(wanted, Rectangle(0, 0, 1366, 728))
        assertTrue(placement.maximized)
        assertEquals(Rectangle(43, 0, 1280, 728), placement.bounds)
    }

    @Test
    fun theWorkAreaCanStartAnywhere() {
        // A taskbar at the top, or a primary screen right of another.
        val placement = FirstWindow.place(wanted, Rectangle(1920, 40, 1280, 800))
        assertFalse(placement.maximized)
        assertEquals(Rectangle(1920, 40, 1280, 800), placement.bounds)
    }

    @Test
    fun aNarrowWorkAreaMaximizesToo() {
        val placement = FirstWindow.place(wanted, Rectangle(0, 0, 1024, 900))
        assertTrue(placement.maximized)
        assertEquals(Rectangle(0, 50, 1024, 800), placement.bounds)
    }
}
