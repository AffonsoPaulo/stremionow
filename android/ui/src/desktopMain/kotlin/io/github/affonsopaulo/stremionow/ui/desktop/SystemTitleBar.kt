// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
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
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/**
 * Gives the window's title bar the app's look where the system allows it.
 *
 * - macOS: AWT follows the system appearance when `apple.awt.application.appearance` is
 *   `system`, which [useSystemAppearance] sets before the first window exists.
 * - Windows 11: [follow] paints the title bar in the app's background colour, so it reads as
 *   part of the window, as in Stremio's own app, with the app's text colour for its title and
 *   the immersive dark mode's light caption buttons to match.
 * - Windows 10, which cannot colour a title bar: it follows the system's light or dark mode.
 *   Title bars are light unless the app asks DWM for a dark one, so [follow] reads the "apps use
 *   light theme" choice, again whenever the window is activated (the user may have switched
 *   modes meanwhile).
 * - Linux: the window manager draws the title bar from its own theme; nothing to do.
 */
object SystemTitleBar {
    /** Call first thing in `main`, before AWT starts. */
    fun useSystemAppearance() {
        if (Platform.isMac()) System.setProperty("apple.awt.application.appearance", "system")
    }

    /** Gives [window]'s title bar the app's look and keeps it (Windows; elsewhere nothing happens). */
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
            // Fails before Windows 11, which leaves the title bar to the light or dark mode.
            val coloured =
                setAttribute(hwnd, CAPTION_COLOR, colorRef(SnColors.bg)) &&
                    setAttribute(hwnd, TEXT_COLOR, colorRef(SnColors.fg))
            val darkMode = if (coloured) SnColors.fg.luminance() > HALF_LUMINANCE else systemUsesDarkMode()
            val dark = if (darkMode) 1 else 0
            // 20 since Windows 10 20H1; 19 on the builds before it.
            val applied = setAttribute(hwnd, DARK_MODE, dark) || setAttribute(hwnd, DARK_MODE_OLD, dark)
            // Windows 10 repaints the frame only when told to.
            if (applied) {
                User32.INSTANCE.SetWindowPos(hwnd, null, 0, 0, 0, 0, SWP_FRAME_CHANGED_ONLY)
            }
        }.onFailure { logWarning("Could not style the title bar", it) }
    }

    private fun setAttribute(
        hwnd: HWND,
        attribute: Int,
        value: Int,
    ): Boolean {
        val reference = IntByReference(value)
        return Dwm.INSTANCE.DwmSetWindowAttribute(hwnd, attribute, reference.pointer, Int.SIZE_BYTES) == 0
    }

    /** A Win32 COLORREF: 0x00BBGGRR. */
    private fun colorRef(color: Color): Int {
        val argb = color.toArgb()
        val red = argb shr 16 and 0xFF
        val green = argb shr 8 and 0xFF
        val blue = argb and 0xFF
        return red or (green shl 8) or (blue shl 16)
    }

    /** Windows' "Choose your app mode": dark when `AppsUseLightTheme` is 0; light when unknown. */
    private fun systemUsesDarkMode(): Boolean =
        runCatching {
            Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, PERSONALIZE_KEY, "AppsUseLightTheme") == 0
        }.getOrDefault(false)

    private const val PERSONALIZE_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"
    private const val DARK_MODE = 20
    private const val DARK_MODE_OLD = 19

    /** DWMWA_CAPTION_COLOR and DWMWA_TEXT_COLOR, since Windows 11. */
    private const val CAPTION_COLOR = 35
    private const val TEXT_COLOR = 36

    /** Light text (on a dark title bar) wants the dark mode's light caption buttons. */
    private const val HALF_LUMINANCE = 0.5f

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
