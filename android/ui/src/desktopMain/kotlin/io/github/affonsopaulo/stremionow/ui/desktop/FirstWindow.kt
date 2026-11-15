// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Rectangle

/**
 * Where the app's first window opens. Centred on the screen at a fixed size, a window taller
 * than the screen's free area (a 1366 × 768 laptop, or a large display scale) put its title bar
 * above the screen, out of reach of the mouse. Instead it is centred in the work area (the
 * screen without the taskbar) when it fits, and opens maximized when it does not.
 */
object FirstWindow {
    /** The window's [bounds] in AWT units, and whether it opens [maximized] (restoring to [bounds]). */
    data class Placement(
        val bounds: Rectangle,
        val maximized: Boolean,
    )

    /** The placement for a window of [wanted] size on the main screen, or null when unknown. */
    fun place(wanted: Dimension): Placement? =
        runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }
            .getOrNull()
            ?.takeIf { it.width > 0 && it.height > 0 }
            ?.let { place(wanted, it) }

    /** The placement for a window of [wanted] size in [workArea]. */
    fun place(
        wanted: Dimension,
        workArea: Rectangle,
    ): Placement {
        val width = minOf(wanted.width, workArea.width)
        val height = minOf(wanted.height, workArea.height)
        val bounds =
            Rectangle(
                workArea.x + (workArea.width - width) / 2,
                workArea.y + (workArea.height - height) / 2,
                width,
                height,
            )
        val fits = width == wanted.width && height == wanted.height
        return Placement(bounds, maximized = !fits)
    }
}
