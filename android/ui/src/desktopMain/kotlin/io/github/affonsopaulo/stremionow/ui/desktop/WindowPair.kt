// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.awt.Frame
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.awt.event.WindowStateListener
import java.beans.PropertyChangeListener
import javax.swing.Timer

/** Which edges a resize gesture moves. */
internal enum class ResizeEdge(
    val left: Boolean = false,
    val top: Boolean = false,
    val right: Boolean = false,
    val bottom: Boolean = false,
) {
    Left(left = true),
    Top(top = true),
    Right(right = true),
    Bottom(bottom = true),
    TopLeft(left = true, top = true),
    TopRight(top = true, right = true),
    BottomLeft(left = true, bottom = true),
    BottomRight(right = true, bottom = true),
}

/**
 * Keeps the video window exactly under the overlay and makes the two behave as one window.
 *
 * - Stacking: the overlay is owned by the video window, so the system keeps it directly above
 *   the video without making it topmost, hides it while the video window is minimized and shows
 *   it again on restore. Nothing here touches the z-order.
 * - Geometry: the overlay is the reference. Moves and resizes made by the app ([beginGesture],
 *   [enterFullScreen]) apply to both windows in one step; changes made by the system (DPI changes,
 *   display changes, window snapping) are copied from the overlay to the video window, and the
 *   video window is put back under the overlay if anything moves it alone.
 * - Focus: activating the video window (taskbar, Alt+Tab) passes focus to the overlay, which
 *   handles all input.
 *
 * Call [attach] once both windows are showing and [close] before disposing them. All methods
 * must be called on the AWT event dispatch thread.
 */
internal class WindowPair(
    private val video: Frame,
    private val overlay: Window,
    private val system: WindowSystem = WindowSystem.current(),
) : AutoCloseable {
    /** Whether both windows cover the monitor. Compose state, so the UI follows it. */
    var isFullScreen by mutableStateOf(false)
        private set

    /** Where the windows were before going full screen. */
    private var windowedBounds: Rectangle? = null

    private class Gesture(
        val edge: ResizeEdge?,
        val startCursor: Point,
        val startBounds: Rectangle,
    ) {
        /** Where the pointer holds the window, as fractions of its size. */
        val anchorX = (startCursor.x - startBounds.x).toDouble() / startBounds.width
        val anchorY = (startCursor.y - startBounds.y).toDouble() / startBounds.height
    }

    private var gesture: Gesture? = null

    /**
     * Follows the pointer while a gesture lasts, where the system can tell when the button is
     * released. Compose restarts its pointer handlers when the overlay changes density (crossing
     * to a monitor with another scale), which would otherwise drop the gesture halfway.
     */
    private val gestureTimer =
        Timer(GESTURE_POLL_MS) {
            when (system.isPrimaryButtonDown()) {
                true -> updateGesture()
                false -> endGesture()
                null -> Unit
            }
        }

    private val geometryListener =
        object : ComponentAdapter() {
            override fun componentMoved(event: ComponentEvent) = conform()

            override fun componentResized(event: ComponentEvent) = conform()
        }

    /**
     * A DPI or monitor change reaches each window separately, and AWT keeps resizing both for a
     * moment afterwards; check again once that has settled.
     */
    private val settleTimer = Timer(SETTLE_DELAY_MS) { conform() }.apply { isRepeats = false }

    private val configurationListener =
        PropertyChangeListener {
            conform()
            settleTimer.restart()
        }

    private val focusListener =
        object : WindowFocusListener {
            override fun windowGainedFocus(event: WindowEvent) {
                // A minimized pair keeps focus on the video window, so keys do not reach the hidden overlay.
                if (system.isMinimized(video)) return
                // The video window has no controls; whatever activated it meant the player. Focus
                // goes back to the component that had it, the Compose content: focusing the window
                // itself would leave Compose without key events.
                overlay.toFront()
                overlay.mostRecentFocusOwner?.requestFocus()
            }

            override fun windowLostFocus(event: WindowEvent) = Unit
        }

    private val stateListener =
        WindowStateListener { event ->
            val wasMinimized = event.oldState and Frame.ICONIFIED != 0
            val isMinimized = event.newState and Frame.ICONIFIED != 0
            when {
                wasMinimized && !isMinimized -> conform()

                // Minimized from outside (taskbar, Win+Down): the hidden overlay would keep the keyboard.
                !wasMinimized && isMinimized && overlay.isFocused -> system.activate(video)
            }
        }

    private val overlayStateListener =
        object : WindowAdapter() {
            override fun windowIconified(event: WindowEvent) = minimizeFromOverlay()
        }

    /** Starts following the overlay. */
    fun attach() {
        conform()
        overlay.addComponentListener(geometryListener)
        overlay.addWindowListener(overlayStateListener)
        video.addComponentListener(geometryListener)
        video.addWindowFocusListener(focusListener)
        video.addWindowStateListener(stateListener)
        overlay.addPropertyChangeListener("graphicsConfiguration", configurationListener)
        video.addPropertyChangeListener("graphicsConfiguration", configurationListener)
    }

    /** Starts moving the windows with the pointer, or resizing them from [edge]. */
    fun beginGesture(edge: ResizeEdge?) {
        if (isFullScreen) return
        gesture = Gesture(edge, system.cursorPosition(), system.bounds(overlay))
        gestureTimer.restart()
    }

    /** Applies the pointer's movement since [beginGesture] to both windows. */
    fun updateGesture() {
        val current = gesture ?: return
        val cursor = system.cursorPosition()
        val bounds = system.bounds(overlay)
        val target =
            when (val edge = current.edge) {
                // Keep the current size, which a DPI change may alter on the way to another
                // monitor, and the pointer on the same spot of the window, like system dragging.
                null -> {
                    Rectangle(
                        cursor.x - (current.anchorX * bounds.width).toInt(),
                        cursor.y - (current.anchorY * bounds.height).toInt(),
                        bounds.width,
                        bounds.height,
                    )
                }

                else -> {
                    val dx = cursor.x - current.startCursor.x
                    val dy = cursor.y - current.startCursor.y
                    resized(current.startBounds, edge, dx, dy)
                }
            }
        if (target != bounds) system.setBounds(video, overlay, target)
    }

    /**
     * Ends the gesture started by [beginGesture]. Where the system reports the mouse button, a
     * [cancelled] gesture goes on until the button is released.
     */
    fun endGesture(cancelled: Boolean = false) {
        if (cancelled && system.isPrimaryButtonDown() == true) return
        gesture = null
        gestureTimer.stop()
    }

    fun toggleFullScreen() = if (isFullScreen) exitFullScreen() else enterFullScreen()

    /** Covers the monitor that shows the overlay with both windows. */
    fun enterFullScreen() {
        if (isFullScreen) return
        gesture = null
        windowedBounds = system.bounds(overlay)
        system.setBounds(video, overlay, system.monitorBounds(overlay))
        isFullScreen = true
        log.log(System.Logger.Level.INFO, "Full screen on: ${system.bounds(overlay)}")
    }

    /** Puts both windows back where they were before [enterFullScreen]. */
    fun exitFullScreen() {
        if (!isFullScreen) return
        windowedBounds?.let { system.setBounds(video, overlay, it) }
        windowedBounds = null
        isFullScreen = false
        log.log(System.Logger.Level.INFO, "Full screen off: ${system.bounds(overlay)}")
    }

    /**
     * Minimizes the pair; the system hides the overlay along with the video window it belongs to
     * and activates the window behind, as for any minimized app.
     */
    fun minimize() = system.minimize(video)

    override fun close() {
        overlay.removeComponentListener(geometryListener)
        overlay.removeWindowListener(overlayStateListener)
        video.removeComponentListener(geometryListener)
        video.removeWindowFocusListener(focusListener)
        video.removeWindowStateListener(stateListener)
        overlay.removePropertyChangeListener("graphicsConfiguration", configurationListener)
        video.removePropertyChangeListener("graphicsConfiguration", configurationListener)
        settleTimer.stop()
        gestureTimer.stop()
    }

    /** Puts the video window exactly under the overlay. */
    private fun conform() {
        if (system.isMinimized(overlay)) {
            minimizeFromOverlay()
            return
        }
        if (system.isMinimized(video) || !overlay.isShowing) return
        val target = system.bounds(overlay)
        if (system.bounds(video) != target) system.setBounds(video, target)
    }

    /**
     * The overlay was minimized on its own (Win+Down, or a tool acting on it): minimize the video
     * window instead, so the pair keeps a single taskbar entry and restores together.
     */
    private fun minimizeFromOverlay() {
        system.restore(overlay)
        system.minimize(video)
    }

    private fun resized(
        start: Rectangle,
        edge: ResizeEdge,
        dx: Int,
        dy: Int,
    ): Rectangle {
        val scale = system.scale(overlay)
        val minWidth = (MIN_WIDTH_DP * scale).toInt()
        val minHeight = (MIN_HEIGHT_DP * scale).toInt()
        var left = start.x
        var top = start.y
        var right = start.x + start.width
        var bottom = start.y + start.height
        if (edge.left) left = minOf(left + dx, right - minWidth)
        if (edge.right) right = maxOf(right + dx, left + minWidth)
        if (edge.top) top = minOf(top + dy, bottom - minHeight)
        if (edge.bottom) bottom = maxOf(bottom + dy, top + minHeight)
        return Rectangle(left, top, right - left, bottom - top)
    }

    private companion object {
        val log: System.Logger = System.getLogger("StremioNow.window")
        const val MIN_WIDTH_DP = 480
        const val MIN_HEIGHT_DP = 270
        const val SETTLE_DELAY_MS = 300
        const val GESTURE_POLL_MS = 8
    }
}
