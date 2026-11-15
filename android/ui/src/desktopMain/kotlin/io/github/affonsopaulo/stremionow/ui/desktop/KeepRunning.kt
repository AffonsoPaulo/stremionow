// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.l10n.DesktopStrings
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.platform.PlatformContext
import io.github.affonsopaulo.stremionow.ui.platform.logWarning
import java.awt.Frame
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon

/** The desktop window settings, read when they apply, so a change in Settings counts at once. */
internal object DesktopWindowSettings {
    /** "Closing the window quits the app"; on when the settings cannot be read. */
    fun quitsOnClose(): Boolean =
        runCatching { AppGraph.core(PlatformContext.INSTANCE).settings().quitOnClose }.getOrDefault(true)
}

/**
 * What closing the app's window does. By default it quits, as Stremio's does; with "Closing the
 * window quits the app" off, the app keeps running out of sight, so other devices it serves
 * keep playing, with an icon in the system tray to bring it back or quit. Where the system has
 * no tray (some Linux desktops), the window is minimized instead, so it can always come back.
 *
 * Give the window to [attach] once it exists and route its close requests to [onCloseRequest].
 * Everything runs on the AWT event thread.
 */
class KeepRunning(
    private val onQuit: () -> Unit,
) {
    private var window: Frame? = null
    private var icons: List<Image> = emptyList()
    private var trayIcon: TrayIcon? = null

    fun attach(
        window: Frame,
        icons: List<Image>,
    ) {
        this.window = window
        this.icons = icons
    }

    /** The window's close button, Alt+F4, or the player's, through [DesktopHost.closeApp]. */
    fun onCloseRequest() {
        val window = window
        if (window == null || DesktopWindowSettings.quitsOnClose()) {
            quit()
            return
        }
        if (!SystemTray.isSupported() || !showTrayIcon()) {
            window.extendedState = window.extendedState or Frame.ICONIFIED
            return
        }
        window.isVisible = false
    }

    /** Leaves the tray, if the app is there, and quits. */
    fun quit() {
        // A tray icon keeps AWT, and so the app, running: it goes first.
        removeTrayIcon()
        onQuit()
    }

    /**
     * Shows the app in front of other windows, from the tray or minimized: what launching it
     * again does (see [SingleInstance]).
     */
    fun bringToFront() {
        val window = window ?: return
        if (trayIcon != null || window.isVisible) {
            bringBack()
            return
        }
        // The player is open, and the app's window stepped aside for it (DesktopHost.stepAside):
        // the video window comes forward instead. Its overlay is a window it owns, which follows
        // it, and WindowPair puts the overlay above it once it is active.
        Frame.getFrames().filter { it !== window && it.isShowing }.forEach { frame ->
            if (frame.extendedState and Frame.ICONIFIED != 0) {
                frame.extendedState = frame.extendedState and Frame.ICONIFIED.inv()
            }
            frame.toFront()
        }
    }

    private fun bringBack() {
        removeTrayIcon()
        val window = window ?: return
        window.isVisible = true
        if (window.extendedState and Frame.ICONIFIED != 0) {
            window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
        }
        window.toFront()
        window.requestFocus()
    }

    private fun showTrayIcon(): Boolean {
        if (trayIcon != null) return true
        val tray = SystemTray.getSystemTray()
        val wanted = tray.trayIconSize.width
        // The smallest icon at least as large as the tray's, so it is never scaled up.
        val image =
            icons.sortedBy { it.getWidth(null) }.firstOrNull { it.getWidth(null) >= wanted } ?: icons.lastOrNull()
                ?: return false
        val menu =
            PopupMenu().apply {
                add(
                    MenuItem(
                        DesktopStrings.string(Res.string.tray_open.key),
                    ).apply { addActionListener { bringBack() } },
                )
                add(MenuItem(DesktopStrings.string(Res.string.tray_quit.key)).apply { addActionListener { quit() } })
            }
        val icon =
            TrayIcon(image, APP_NAME, menu).apply {
                isImageAutoSize = true
                // A double click, or the keyboard's choice of the icon.
                addActionListener { bringBack() }
            }
        return runCatching { tray.add(icon) }
            .onFailure { logWarning("Could not add the tray icon", it) }
            .onSuccess { trayIcon = icon }
            .isSuccess
    }

    private fun removeTrayIcon() {
        val icon = trayIcon ?: return
        trayIcon = null
        runCatching { SystemTray.getSystemTray().remove(icon) }
    }

    private companion object {
        const val APP_NAME = "StremioNow"
    }
}
