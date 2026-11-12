// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import io.github.affonsopaulo.stremionow.ui.platform.logWarning
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/**
 * Makes the window's title bar follow the system's light or dark mode, as native apps do.
 *
 * - macOS: AWT follows the system appearance when `apple.awt.application.appearance` is
 *   `system`, which [useSystemAppearance] sets before the first window exists.
 * - Windows: title bars are light unless the app asks DWM for a dark one, so [follow] reads the
 *   "apps use light theme" choice and sets the window's immersive dark mode, again whenever the
 *   window is activated (the user may have switched modes meanwhile).
 * - Linux: the window manager draws the title bar from its own theme; nothing to do.
 */
object SystemTitleBar {
    /** Call first thing in `main`, before AWT starts. */
    fun useSystemAppearance() {
        if (Platform.isMac()) System.setProperty("apple.awt.application.appearance", "system")
    }

    /** Keeps [window]'s title bar in the system's mode (Windows; elsewhere nothing happens). */
    fun follow(window: Window) {
        if (!Platform.isWindows()) return
        apply(window)
        window.addWindowListener(
            object : WindowAdapter() {
                override fun windowActivated(event: WindowEvent) = apply(window)
            },
        )
    }

    private fun apply(window: Window) {
        if (!window.isDisplayable) return
        runCatching {
            val hwnd = HWND(Native.getWindowPointer(window))
            val dark = IntByReference(if (systemUsesDarkMode()) 1 else 0)
            // 20 since Windows 10 20H1; 19 on the builds before it.
            val applied =
                Dwm.INSTANCE.DwmSetWindowAttribute(hwnd, DARK_MODE, dark.pointer, Int.SIZE_BYTES) == 0 ||
                    Dwm.INSTANCE.DwmSetWindowAttribute(hwnd, DARK_MODE_OLD, dark.pointer, Int.SIZE_BYTES) == 0
            // Windows 10 repaints the frame only when told to.
            if (applied) {
                User32.INSTANCE.SetWindowPos(hwnd, null, 0, 0, 0, 0, SWP_FRAME_CHANGED_ONLY)
            }
        }.onFailure { logWarning("Could not match the title bar to the system theme", it) }
    }

    /** Windows' "Choose your app mode": dark when `AppsUseLightTheme` is 0; light when unknown. */
    private fun systemUsesDarkMode(): Boolean =
        runCatching {
            Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, PERSONALIZE_KEY, "AppsUseLightTheme") == 0
        }.getOrDefault(false)

    private const val PERSONALIZE_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"
    private const val DARK_MODE = 20
    private const val DARK_MODE_OLD = 19

    /** SWP_NOSIZE | SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED. */
    private const val SWP_FRAME_CHANGED_ONLY = 0x0001 or 0x0002 or 0x0004 or 0x0010 or 0x0020

    @Suppress("FunctionName")
    private interface Dwm : StdCallLibrary {
        fun DwmSetWindowAttribute(
            window: HWND,
            attribute: Int,
            value: com.sun.jna.Pointer,
            size: Int,
        ): Int

        companion object {
            val INSTANCE: Dwm = Native.load("dwmapi", Dwm::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }
}
