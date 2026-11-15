// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import io.github.affonsopaulo.stremionow.ui.platform.logWarning
import java.awt.Window
import javax.swing.Timer

/**
 * Turns the system's window animations off while the app swaps its window for the player's and
 * back. Both windows animate on their own when one shows and the other hides (Windows zooms a
 * framed window in and fades the other out), which reads as a flash; the swap should look like
 * one window changing its content. Windows only; elsewhere nothing happens.
 */
internal object WindowTransitions {
    /** How long after a swap the animations come back, once the system has shown or hidden the windows. */
    private const val RESTORE_DELAY_MS = 500

    /**
     * The pending [enableSoon] of each window. One per window, and cancelled by [disable]: a
     * swap back within the delay (leaving the player at once) would otherwise have the earlier
     * timer turn animations on in the middle of it, and the flash would show. A timer leaves
     * when it fires; touched on the event thread only.
     */
    private val pending = HashMap<Window, Timer>()

    /** Turns [window]'s animations off; it must be displayable. */
    fun disable(window: Window) {
        pending.remove(window)?.stop()
        set(window, enabled = false)
    }

    /** Turns [window]'s animations back on shortly, after the show or hide in progress. */
    fun enableSoon(window: Window) {
        pending.remove(window)?.stop()
        val timer =
            Timer(RESTORE_DELAY_MS) {
                pending.remove(window)
                set(window, enabled = true)
            }.apply { isRepeats = false }
        pending[window] = timer
        timer.start()
    }

    private fun set(
        window: Window,
        enabled: Boolean,
    ) {
        if (!Platform.isWindows() || !window.isDisplayable) return
        runCatching {
            val disabled = IntByReference(if (enabled) 0 else 1)
            val hwnd = HWND(Native.getWindowPointer(window))
            Dwm.INSTANCE.DwmSetWindowAttribute(hwnd, TRANSITIONS_FORCEDISABLED, disabled.pointer, Int.SIZE_BYTES)
        }.onFailure { logWarning("Could not change the window's animations", it) }
    }

    /** DWMWA_TRANSITIONS_FORCEDISABLED. */
    private const val TRANSITIONS_FORCEDISABLED = 3

    @Suppress("FunctionName")
    private interface Dwm : StdCallLibrary {
        fun DwmSetWindowAttribute(
            window: HWND,
            attribute: Int,
            value: Pointer,
            size: Int,
        ): Int

        companion object {
            val INSTANCE: Dwm = Native.load("dwmapi", Dwm::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }
}
