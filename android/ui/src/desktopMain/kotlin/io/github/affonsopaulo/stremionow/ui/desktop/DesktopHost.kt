// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import androidx.compose.runtime.staticCompositionLocalOf
import com.sun.jna.Platform
import java.awt.Frame
import java.awt.Image
import java.awt.Insets
import java.awt.Rectangle
import java.awt.Toolkit
import java.io.File

/**
 * What the shared interface needs from the desktop app around it: provide it with
 * [LocalDesktopHost] around `StremioNowApp`.
 *
 * The player opens in its own pair of windows (docs/adr/0005-desktop-ui.md) exactly where the
 * app's window is, and the app's window steps aside until the player closes, so the two read
 * as one window that switches between browsing and watching.
 */
class DesktopHost(
    /** The app's main window. */
    val mainWindow: Frame,
    /** Icons for the player's window, which owns the taskbar entry while it shows. */
    val icons: List<Image>,
    /** The Mozilla CA store mpv uses to verify HTTPS servers, or null when it is not bundled. */
    val caBundle: File?,
    /** mpv's detailed logs, which include the addresses it opens. */
    val verboseLogs: Boolean = false,
) {
    /** Where the player opens, in AWT units: over the app's window, or over the work area when maximized. */
    internal fun playerBounds(): Rectangle {
        if (mainWindow.extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH) {
            val configuration = mainWindow.graphicsConfiguration
            val screen = configuration.bounds
            val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
            return Rectangle(
                screen.x + insets.left,
                screen.y + insets.top,
                screen.width - insets.left - insets.right,
                screen.height - insets.top - insets.bottom,
            )
        }
        return mainWindow.bounds.shrunkBy(invisibleBorders())
    }

    /** Hides the app's window while the player shows. */
    internal fun stepAside() {
        mainWindow.isVisible = false
    }

    /**
     * Shows the app's window again, where the player was unless [playerBounds] is null (the
     * player was full screen) or the window is maximized. Call it before navigating away from
     * the player: a hidden window does not draw, and its navigation would not move on.
     */
    internal fun comeBack(playerBounds: Rectangle?) {
        val maximized = mainWindow.extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH
        if (playerBounds != null && !maximized) {
            val borders = invisibleBorders()
            mainWindow.bounds =
                Rectangle(
                    playerBounds.x - borders.left,
                    playerBounds.y - borders.top,
                    playerBounds.width + borders.left + borders.right,
                    playerBounds.height + borders.top + borders.bottom,
                )
        }
        mainWindow.isVisible = true
        mainWindow.toFront()
    }

    /**
     * The parts of the window frame that take space but are not drawn. Since Windows 10 the side
     * and bottom borders of a decorated window are invisible resize areas; elsewhere, all borders
     * are visible.
     */
    private fun invisibleBorders(): Insets {
        if (!Platform.isWindows() || mainWindow.isUndecorated) return Insets(0, 0, 0, 0)
        val frame = mainWindow.insets
        return Insets(0, frame.left, frame.bottom, frame.right)
    }

    private fun Rectangle.shrunkBy(insets: Insets) =
        Rectangle(
            x + insets.left,
            y + insets.top,
            width - insets.left - insets.right,
            height - insets.top - insets.bottom,
        )
}

/** The [DesktopHost] of the running app. */
val LocalDesktopHost = staticCompositionLocalOf<DesktopHost> { error("The desktop app must provide a DesktopHost") }
