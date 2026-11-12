// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import com.sun.jna.CallbackReference
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser
import java.awt.Window
import java.util.Collections
import javax.swing.SwingUtilities

/** What a media key or button asks for. */
internal enum class MediaCommand { PlayPause, Play, Pause, Stop, Next, Previous }

/**
 * Media keys (play/pause, next, previous, stop) of keyboards, headsets and remotes, while a
 * window of the player is active.
 *
 * AWT has no key codes for them. On Windows they arrive as `WM_APPCOMMAND`, which the system
 * sends to the active window when the focused control leaves the key unhandled, so the player's
 * overlay window is subclassed to read it. Elsewhere this does nothing yet; see
 * docs/poc/phase-5.md for the system media controls still to do (SMTC, MPRIS, Now Playing).
 */
internal object MediaKeys {
    /**
     * Calls [onCommand] on the AWT event thread for each media key [window] receives. Close the
     * result before disposing the window.
     */
    fun install(
        window: Window,
        onCommand: (MediaCommand) -> Unit,
    ): AutoCloseable =
        if (Platform.isWindows()) {
            runCatching { Win32MediaKeys(window, onCommand) }
                .onFailure { log.log(System.Logger.Level.WARNING, "Media keys unavailable", it) }
                .getOrDefault(AutoCloseable {})
        } else {
            AutoCloseable {}
        }

    private val log = System.getLogger("StremioNow.window")
}

/** Reads `WM_APPCOMMAND` by subclassing the window procedure of an AWT window. */
private class Win32MediaKeys(
    window: Window,
    private val onCommand: (MediaCommand) -> Unit,
) : AutoCloseable {
    private val user32 = User32.INSTANCE
    private val hwnd = HWND(Native.getWindowPointer(window))
    private var previous: Pointer? = null

    private val procedure =
        WinUser.WindowProc { window, message, wParam, lParam ->
            val command = if (message == WM_APPCOMMAND) command(lParam) else null
            if (command != null) {
                SwingUtilities.invokeLater { onCommand(command) }
                LRESULT(1)
            } else {
                user32.CallWindowProc(previous, window, message, wParam, lParam)
            }
        }

    private val procedurePointer: Pointer = CallbackReference.getFunctionPointer(procedure)

    init {
        // Kept reachable while installed: the window calls it until it is restored.
        live += this
        previous = user32.SetWindowLongPtr(hwnd, WinUser.GWL_WNDPROC, procedurePointer)
        if (previous == null) {
            live -= this
            error("SetWindowLongPtr failed: ${Native.getLastError()}")
        }
    }

    override fun close() {
        val current = user32.GetWindowLongPtr(hwnd, WinUser.GWL_WNDPROC)
        // Only restore when nothing subclassed the window after us; otherwise stay in the chain.
        if (current.toLong() == Pointer.nativeValue(procedurePointer)) {
            user32.SetWindowLongPtr(hwnd, WinUser.GWL_WNDPROC, previous)
            live -= this
        }
    }

    private fun command(lParam: LPARAM): MediaCommand? {
        // GET_APPCOMMAND_LPARAM: the high word without the device bits.
        val appCommand = ((lParam.toLong() shr 16) and 0xFFFF and FAPPCOMMAND_MASK.inv().toLong()).toInt()
        return when (appCommand) {
            APPCOMMAND_MEDIA_PLAY_PAUSE -> MediaCommand.PlayPause
            APPCOMMAND_MEDIA_PLAY -> MediaCommand.Play
            APPCOMMAND_MEDIA_PAUSE -> MediaCommand.Pause
            APPCOMMAND_MEDIA_STOP -> MediaCommand.Stop
            APPCOMMAND_MEDIA_NEXTTRACK -> MediaCommand.Next
            APPCOMMAND_MEDIA_PREVIOUSTRACK -> MediaCommand.Previous
            else -> null
        }
    }

    private companion object {
        val live: MutableSet<Win32MediaKeys> = Collections.synchronizedSet(mutableSetOf())

        const val WM_APPCOMMAND = 0x0319
        const val FAPPCOMMAND_MASK = 0xF000
        const val APPCOMMAND_MEDIA_NEXTTRACK = 11
        const val APPCOMMAND_MEDIA_PREVIOUSTRACK = 12
        const val APPCOMMAND_MEDIA_STOP = 13
        const val APPCOMMAND_MEDIA_PLAY_PAUSE = 14
        const val APPCOMMAND_MEDIA_PLAY = 46
        const val APPCOMMAND_MEDIA_PAUSE = 47
    }
}
