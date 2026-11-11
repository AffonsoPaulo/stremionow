// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.desktop.window

import com.sun.jna.Native
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
 * It is the app's main window as far as the system is concerned: it owns the taskbar (or dock)
 * entry, its title and icon, and it owns the Compose overlay, which the system therefore keeps
 * directly above it. mpv renders into a child window it creates inside [canvas], a heavyweight
 * AWT component, so no frame ever passes through the JVM.
 */
class VideoWindow(
    title: String,
    icons: List<Image>,
) {
    /** Fills the frame; mpv's child window covers it and follows its size. */
    private val canvas =
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

    /**
     * Shows the window at [bounds] (AWT units) and returns the native handle mpv draws into: the
     * canvas HWND on Windows, its X11 window id on Linux.
     */
    fun show(bounds: Rectangle): Long {
        frame.bounds = bounds
        frame.isVisible = true
        return Native.getComponentID(canvas)
    }

    /** Closes the window. Destroy mpv first: it draws into a child of this window. */
    fun dispose() {
        frame.dispose()
    }
}
