// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import com.sun.jna.CallbackReference
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.POINT
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.Frame
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import javax.swing.SwingUtilities

/**
 * Window geometry as the platform sees it.
 *
 * All rectangles and points of one implementation share a unit: physical pixels on Windows, where
 * AWT's per-monitor scaled units would round differently for the two windows, and AWT units
 * elsewhere. Callers never mix them with values from other sources.
 */
internal interface WindowSystem {
    /** Outer bounds of [window]. */
    fun bounds(window: Window): Rectangle

    /** Bounds of the area inside [window]'s frame (its title bar and borders), on screen. */
    fun clientBounds(window: Window): Rectangle

    /**
     * Shows or hides the system frame of a decorated [window] without recreating it (mpv draws
     * into a child of it), for full screen. Does nothing where the system cannot.
     */
    fun setFramed(
        window: Window,
        framed: Boolean,
    )

    /**
     * Calls [onMinimize] on the AWT event thread instead of minimizing when the system is asked
     * to minimize [window] (its title bar's button, its taskbar entry), so the caller can finish
     * something first and minimize with [minimize]. Returns what undoes it; close it before
     * disposing the window. Null where the system offers no way.
     */
    fun interceptMinimize(
        window: Window,
        onMinimize: () -> Unit,
    ): AutoCloseable?

    /** Moves and resizes [window] without changing its stacking or activating it. */
    fun setBounds(
        window: Window,
        bounds: Rectangle,
    )

    /** Moves and resizes both windows in one step, so neither is seen out of place. */
    fun setBounds(
        first: Window,
        second: Window,
        bounds: Rectangle,
    )

    /** Pointer position on screen. */
    fun cursorPosition(): Point

    /** Bounds of the whole monitor that shows most of [window]. */
    fun monitorBounds(window: Window): Rectangle

    /** Size of one AWT unit (one dp) in this system's unit, on the monitor of [window]. */
    fun scale(window: Window): Double

    /** Whether [window] is minimized. */
    fun isMinimized(window: Window): Boolean

    /** Brings a minimized [window] back without activating it. */
    fun restore(window: Window)

    /** Minimizes [window] and activates the window behind it, like the minimize button of a title bar. */
    fun minimize(window: Window)

    /** Makes [window] the active window, even when it is minimized. */
    fun activate(window: Window)

    /** Whether the primary mouse button is held down now, or null when the system cannot tell. */
    fun isPrimaryButtonDown(): Boolean?

    companion object {
        /** The implementation for the running system. */
        fun current(): WindowSystem = if (Platform.isWindows()) Win32WindowSystem() else AwtWindowSystem()
    }
}

/** Win32 implementation: physical pixels, `DeferWindowPos` for atomic moves of both windows. */
internal class Win32WindowSystem : WindowSystem {
    private val user32 = User32.INSTANCE
    private val extra = User32Extra.INSTANCE

    override fun bounds(window: Window): Rectangle {
        val rect = RECT()
        user32.GetWindowRect(hwnd(window), rect)
        return rect.toRectangle()
    }

    override fun clientBounds(window: Window): Rectangle {
        val rect = RECT()
        user32.GetClientRect(hwnd(window), rect)
        val origin = POINT(0, 0)
        extra.ClientToScreen(hwnd(window), origin)
        return Rectangle(origin.x, origin.y, rect.right - rect.left, rect.bottom - rect.top)
    }

    override fun setFramed(
        window: Window,
        framed: Boolean,
    ) {
        val hwnd = hwnd(window)
        val style = user32.GetWindowLong(hwnd, WinUser.GWL_STYLE)
        val changed = if (framed) style or FRAME_STYLES else style and FRAME_STYLES.inv()
        if (changed == style) return
        user32.SetWindowLong(hwnd, WinUser.GWL_STYLE, changed)
        user32.SetWindowPos(hwnd, null, 0, 0, 0, 0, FRAME_CHANGED_FLAGS)
    }

    override fun interceptMinimize(
        window: Window,
        onMinimize: () -> Unit,
    ): AutoCloseable = MinimizeHook(hwnd(window), onMinimize)

    override fun setBounds(
        window: Window,
        bounds: Rectangle,
    ) {
        user32.SetWindowPos(hwnd(window), null, bounds.x, bounds.y, bounds.width, bounds.height, MOVE_FLAGS)
    }

    override fun setBounds(
        first: Window,
        second: Window,
        bounds: Rectangle,
    ) {
        var batch = extra.BeginDeferWindowPos(2)
        for (window in listOf(first, second)) {
            batch =
                batch?.let {
                    extra.DeferWindowPos(
                        it,
                        hwnd(window),
                        null,
                        bounds.x,
                        bounds.y,
                        bounds.width,
                        bounds.height,
                        MOVE_FLAGS,
                    )
                }
        }
        if (batch == null || !extra.EndDeferWindowPos(batch)) {
            // DeferWindowPos gives up on its own failures; fall back to two separate moves.
            setBounds(first, bounds)
            setBounds(second, bounds)
        }
    }

    override fun cursorPosition(): Point {
        val point = POINT()
        user32.GetCursorPos(point)
        return Point(point.x, point.y)
    }

    override fun monitorBounds(window: Window): Rectangle {
        val monitor = user32.MonitorFromWindow(hwnd(window), WinUser.MONITOR_DEFAULTTONEAREST)
        val info = WinUser.MONITORINFO()
        user32.GetMonitorInfo(monitor, info)
        return info.rcMonitor.toRectangle()
    }

    override fun scale(window: Window): Double = extra.GetDpiForWindow(hwnd(window)) / BASE_DPI

    override fun isMinimized(window: Window): Boolean = extra.IsIconic(hwnd(window))

    override fun restore(window: Window) {
        user32.ShowWindow(hwnd(window), WinUser.SW_SHOWNOACTIVATE)
    }

    override fun minimize(window: Window) {
        // SW_MINIMIZE activates the next window only when the minimized one is active; the
        // overlay usually is, and it would otherwise keep the keyboard while hidden.
        activate(window)
        user32.ShowWindow(hwnd(window), WinUser.SW_MINIMIZE)
    }

    override fun activate(window: Window) {
        user32.SetForegroundWindow(hwnd(window))
    }

    override fun isPrimaryButtonDown(): Boolean {
        // The primary button is the right one when the user swapped the buttons.
        val swapped = user32.GetSystemMetrics(WinUser.SM_SWAPBUTTON) != 0
        val key = if (swapped) VK_RBUTTON else VK_LBUTTON
        return user32.GetAsyncKeyState(key).toInt() and KEY_DOWN != 0
    }

    private fun hwnd(window: Window): HWND = HWND(Native.getWindowPointer(window))

    /**
     * Replaces the window procedure of [hwnd] with one that turns the system's minimize command
     * into [onMinimize] and passes everything else on to AWT's. The procedure must stay reachable
     * while installed: the system calls it through a pointer the garbage collector does not see.
     */
    private class MinimizeHook(
        private val hwnd: HWND,
        private val onMinimize: () -> Unit,
    ) : AutoCloseable {
        private val user32 = User32.INSTANCE
        private var original: Pointer? = null
        private val procedure =
            WinUser.WindowProc { window, message, wParam, lParam ->
                val command = wParam.toLong() and SYSCOMMAND_MASK
                if (message == WM_SYSCOMMAND && command == SC_MINIMIZE) {
                    SwingUtilities.invokeLater(onMinimize)
                    LRESULT(0)
                } else {
                    user32.CallWindowProc(original, window, message, wParam, lParam)
                }
            }

        init {
            original =
                user32.SetWindowLongPtr(hwnd, WinUser.GWL_WNDPROC, CallbackReference.getFunctionPointer(procedure))
        }

        override fun close() {
            val previous = original ?: return
            user32.SetWindowLongPtr(hwnd, WinUser.GWL_WNDPROC, previous)
            original = null
        }
    }

    /** User32 functions that JNA's platform library does not declare. */
    @Suppress("FunctionName", "ktlint:standard:function-naming")
    private interface User32Extra : StdCallLibrary {
        fun BeginDeferWindowPos(count: Int): Pointer?

        fun DeferWindowPos(
            batch: Pointer,
            window: HWND,
            insertAfter: HWND?,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            flags: Int,
        ): Pointer?

        fun EndDeferWindowPos(batch: Pointer): Boolean

        fun GetDpiForWindow(window: HWND): Int

        fun IsIconic(window: HWND): Boolean

        fun ClientToScreen(
            window: HWND,
            point: POINT,
        ): Boolean

        companion object {
            val INSTANCE: User32Extra = Native.load("user32", User32Extra::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }

    private companion object {
        const val BASE_DPI = 96.0
        const val VK_LBUTTON = 0x01
        const val VK_RBUTTON = 0x02
        const val KEY_DOWN = 0x8000

        /** Keep stacking (the overlay stays above the video through ownership) and focus as they are. */
        const val MOVE_FLAGS = WinUser.SWP_NOZORDER or WinUser.SWP_NOACTIVATE or WinUser.SWP_NOOWNERZORDER

        /** The title bar and the resizing border; the system menu and its buttons stay. */
        const val FRAME_STYLES = WinUser.WS_CAPTION or WinUser.WS_THICKFRAME

        /** Recalculates the frame after a style change, moving and resizing nothing. */
        const val FRAME_CHANGED_FLAGS =
            WinUser.SWP_NOMOVE or WinUser.SWP_NOSIZE or MOVE_FLAGS or WinUser.SWP_FRAMECHANGED

        const val WM_SYSCOMMAND = 0x0112
        const val SC_MINIMIZE = 0xF020L

        /** The low four bits of a system command are the system's own. */
        const val SYSCOMMAND_MASK = 0xFFF0L
    }
}

/**
 * Plain AWT implementation for macOS and Linux. Not validated by hand yet: each system may need
 * native calls of its own, as Windows does.
 */
internal class AwtWindowSystem : WindowSystem {
    override fun bounds(window: Window): Rectangle = window.bounds

    override fun clientBounds(window: Window): Rectangle {
        val bounds = window.bounds
        val insets = window.insets
        return Rectangle(
            bounds.x + insets.left,
            bounds.y + insets.top,
            bounds.width - insets.left - insets.right,
            bounds.height - insets.top - insets.bottom,
        )
    }

    override fun setFramed(
        window: Window,
        framed: Boolean,
    ) = Unit

    override fun interceptMinimize(
        window: Window,
        onMinimize: () -> Unit,
    ): AutoCloseable? = null

    override fun setBounds(
        window: Window,
        bounds: Rectangle,
    ) {
        window.bounds = bounds
    }

    override fun setBounds(
        first: Window,
        second: Window,
        bounds: Rectangle,
    ) {
        first.bounds = bounds
        second.bounds = bounds
    }

    override fun cursorPosition(): Point = MouseInfo.getPointerInfo()?.location ?: Point()

    override fun monitorBounds(window: Window): Rectangle = window.graphicsConfiguration.bounds

    override fun scale(window: Window): Double = 1.0

    override fun isMinimized(window: Window): Boolean = window is Frame && window.extendedState and Frame.ICONIFIED != 0

    override fun restore(window: Window) {
        if (window is Frame) window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
    }

    override fun minimize(window: Window) {
        if (window is Frame) window.extendedState = window.extendedState or Frame.ICONIFIED
    }

    override fun activate(window: Window) {
        window.toFront()
    }

    override fun isPrimaryButtonDown(): Boolean? = null
}
