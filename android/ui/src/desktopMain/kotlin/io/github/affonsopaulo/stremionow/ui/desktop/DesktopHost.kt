// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import androidx.compose.runtime.staticCompositionLocalOf
import com.sun.jna.Platform
import java.awt.Frame
import java.awt.Image
import java.awt.Insets
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.WindowEvent
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
    /** The folder of native libraries bundled with the app (libmpv on Windows), or null. */
    val nativeLibraries: File? = null,
    /** mpv's detailed logs, which include the addresses it opens. */
    val verboseLogs: Boolean = false,
) {
    /** Whether the app's window is maximized; a framed player window opens maximized too. */
    internal val isMaximized: Boolean
        get() = mainWindow.extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH

    /**
     * Where the player opens, in AWT units. A [framed] player window takes the app's window's
     * bounds as they are, frame and all. A borderless one covers what the app's window shows:
     * the work area when maximized, the window without its invisible borders otherwise.
     */
    internal fun playerBounds(framed: Boolean): Rectangle {
        if (framed) return mainWindow.bounds
        if (isMaximized) {
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
        // The player's window takes its place: no closing animation (see WindowTransitions).
        WindowTransitions.disable(mainWindow)
        mainWindow.isVisible = false
    }

    /**
     * Set when the player's window was closed with "Closing the window quits the app" off: the
     * player leaves first, and once it has gone the app's window closes the way that setting
     * says (see `KeepRunning`), rather than leaving the video playing out of sight.
     */
    internal var closeAfterPlayer = false

    /** Closes the app, as the close button of its window does, from the player's window. */
    internal fun closeApp() {
        mainWindow.dispatchEvent(WindowEvent(mainWindow, WindowEvent.WINDOW_CLOSING))
    }

    /**
     * Shows the app's window again, where the player was unless [playerBounds] is null (the
     * player was full screen). A [framed] player window hands over its bounds, frame and all, and
     * whether it was [maximized]; a borderless one only its bounds, ignored while the app's window
     * is maximized. Call it before navigating away from the player: a hidden window does not
     * draw, and its navigation would not move on.
     */
    internal fun comeBack(
        playerBounds: Rectangle?,
        framed: Boolean = false,
        maximized: Boolean = false,
    ) {
        if (framed) {
            when {
                playerBounds == null -> {
                    Unit
                }

                maximized -> {
                    mainWindow.extendedState = Frame.MAXIMIZED_BOTH
                }

                else -> {
                    mainWindow.extendedState = Frame.NORMAL
                    mainWindow.bounds = playerBounds
                }
            }
            show()
            return
        }
        if (playerBounds != null && !isMaximized) {
            val borders = invisibleBorders()
            mainWindow.bounds =
                Rectangle(
                    playerBounds.x - borders.left,
                    playerBounds.y - borders.top,
                    playerBounds.width + borders.left + borders.right,
                    playerBounds.height + borders.top + borders.bottom,
                )
        }
        show()
    }

    /** Shows the app's window in the player's place, without the system's opening animation. */
    private fun show() {
        mainWindow.isVisible = true
        mainWindow.toFront()
        WindowTransitions.enableSoon(mainWindow)
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
