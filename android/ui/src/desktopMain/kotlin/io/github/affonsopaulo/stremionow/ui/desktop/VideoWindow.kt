// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.Color
import java.awt.Image
import java.awt.Rectangle
import javax.swing.JFrame
import javax.swing.WindowConstants

/**
 * The borderless native window mpv draws into.
 *
 * While the player shows, it is the app's main window as far as the system is concerned (the
 * app's own window steps aside): it owns the taskbar (or dock) entry, its title and icon, and it
 * owns the Compose overlay, which the system therefore keeps directly above it. mpv draws into
 * [canvas], a heavyweight AWT component (on Windows and X11 through a child window of its own, on
 * macOS through a layer over the window's content), so no frame ever passes through the JVM.
 */
internal class VideoWindow(
    title: String,
    icons: List<Image>,
) {
    /** Fills the frame; mpv's output covers it and follows its size. */
    val canvas =
        Canvas().apply {
            background = Color.BLACK
            // mpv paints this area; AWT repaints would only flash black over the video.
            ignoreRepaint = true
        }

    /** The top-level window. */
    val frame: JFrame =
        JFrame(title).apply {
            isUndecorated = true
            background = Color.BLACK
            contentPane.background = Color.BLACK
            defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
            iconImages = icons
            contentPane.add(canvas, BorderLayout.CENTER)
        }

    /** Shows the window at [bounds] (AWT units); mpv can draw into [canvas] from then on. */
    fun show(bounds: Rectangle) {
        frame.bounds = bounds
        frame.isVisible = true
    }

    /** Hides the window; mpv may go on drawing into it until it is destroyed. */
    fun hide() {
        frame.isVisible = false
    }

    /** Closes the window. Destroy mpv first: it draws into a child of this window. */
    fun dispose() {
        frame.dispose()
    }
}
