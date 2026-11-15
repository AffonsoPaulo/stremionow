// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import com.sun.jna.Platform
import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.Color
import java.awt.Dimension
import java.awt.Frame
import java.awt.Image
import java.awt.Rectangle
import javax.swing.JFrame
import javax.swing.WindowConstants

/**
 * The native window mpv draws into.
 *
 * While the player shows, it is the app's main window as far as the system is concerned (the
 * app's own window steps aside): it owns the taskbar (or dock) entry, its title and icon, and it
 * owns the Compose overlay, which the system therefore keeps directly above it. mpv draws into
 * [canvas], a heavyweight AWT component (on Windows and X11 through a child window of its own, on
 * macOS through a layer over the window's content), so no frame ever passes through the JVM.
 *
 * When [isFramed], it has the system's title bar and borders, like the app's window: the system
 * moves, resizes, maximizes and minimizes it (with its animations), and the overlay covers the
 * area inside the frame. That is the case on Windows. Elsewhere it is borderless, and the
 * overlay draws its own handles to move and resize the pair (see [WindowPair]).
 */
internal class VideoWindow(
    title: String,
    icons: List<Image>,
    val isFramed: Boolean = FRAMED,
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
            isUndecorated = !isFramed
            background = Color.BLACK
            contentPane.background = Color.BLACK
            defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
            WindowIcon.apply(this, icons)
            contentPane.add(canvas, BorderLayout.CENTER)
            if (isFramed) minimumSize = Dimension(MIN_WIDTH, MIN_HEIGHT)
        }

    /**
     * Shows the window at [bounds] (AWT units), maximized when [maximized] and the window is
     * framed; mpv can draw into [canvas] from then on.
     */
    fun show(
        bounds: Rectangle,
        maximized: Boolean = false,
    ) {
        frame.bounds = bounds
        if (isFramed && maximized) frame.extendedState = Frame.MAXIMIZED_BOTH
        // Appears in place of the app's window, without the system's opening animation.
        frame.addNotify()
        WindowTransitions.disable(frame)
        frame.isVisible = true
        WindowTransitions.enableSoon(frame)
        if (isFramed) SystemTitleBar.follow(frame)
    }

    /** Hides the window; mpv may go on drawing into it until it is destroyed. */
    fun hide() {
        // Gives way to the app's window without the system's closing animation.
        WindowTransitions.disable(frame)
        frame.isVisible = false
    }

    /** Closes the window. Destroy mpv first: it draws into a child of this window. */
    fun dispose() {
        frame.dispose()
    }

    companion object {
        /** Whether video windows have the system's frame on this system. */
        val FRAMED: Boolean = Platform.isWindows()

        /** In AWT units, the frame included: about 480 × 270 inside it. */
        private const val MIN_WIDTH = 496
        private const val MIN_HEIGHT = 310
    }
}
