// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.awt.Dimension
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
import kotlin.math.roundToInt

/** How long the overlay's content takes to fade out before the pair minimizes. */
internal const val OVERLAY_FADE_OUT_MS = 120

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
 * Keeps the video window and the overlay together and makes the two behave as one window.
 *
 * - Stacking: the overlay is owned by the video window, so the system keeps it directly above
 *   the video without making it topmost, hides it while the video window is minimized and shows
 *   it again on restore. Nothing here touches the z-order.
 * - Geometry, with a [framed] video window (Windows): the video window is the reference. The
 *   system moves, resizes, snaps and maximizes it through its frame, and the overlay is kept over
 *   the area inside the frame. Full screen hides the frame.
 * - Geometry, with a borderless video window: the overlay is the reference. Moves and resizes
 *   made by the app ([beginGesture], [enterFullScreen]) apply to both windows in one step;
 *   changes made by the system (DPI changes, display changes, window snapping) are copied from
 *   the overlay to the video window, and the video window is put back under the overlay if
 *   anything moves it alone.
 * - Minimizing: the overlay's content fades out first ([isFadingOut]) and back in on restore, so
 *   the controls do not vanish ahead of the system's animation of the video window.
 * - Focus: activating the video window (taskbar, Alt+Tab) passes focus to the overlay, which
 *   handles all input.
 *
 * Call [attach] once both windows are showing and [close] before disposing them. All methods
 * must be called on the AWT event dispatch thread.
 */
internal class WindowPair(
    private val video: Frame,
    private val overlay: Window,
    /** Whether the video window has the system's frame; see [VideoWindow.isFramed]. */
    val framed: Boolean = !video.isUndecorated,
    private val system: WindowSystem = WindowSystem.current(),
) : AutoCloseable {
    /** Whether both windows cover the monitor. Compose state, so the UI follows it. */
    var isFullScreen by mutableStateOf(false)
        private set

    /**
     * Whether the overlay's content should be hidden: while minimizing and minimized. Compose
     * state; the overlay fades along with it.
     */
    var isFadingOut by mutableStateOf(false)
        private set

    /** Where the windows were before going full screen. */
    private var windowedBounds: Rectangle? = null

    /** Whether the framed video window was maximized before going full screen. */
    private var wasMaximized = false

    /** Undoes [WindowSystem.interceptMinimize]. */
    private var minimizeHook: AutoCloseable? = null

    /** Minimizes once the overlay has faded out. */
    private val minimizeTimer = Timer(OVERLAY_FADE_OUT_MS) { system.minimize(video) }.apply { isRepeats = false }

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
     * The windowed size in dp. A DPI change (a move to a monitor with another scale, or a scale
     * changed in the system settings) reaches each window on its own, and AWT resizes each from
     * whatever size it has at that moment, so the pixel size alone drifts: across a monitor
     * boundary the change can apply twice, or not at all. The pair keeps this size instead and
     * applies it at the new scale once the change settles.
     */
    private val windowedSize = WindowedSize()

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

            // Attached before the overlay shows, conform() would have nothing to place yet.
            override fun componentShown(event: ComponentEvent) = conform()
        }

    /**
     * A DPI or monitor change reaches each window separately, and AWT keeps resizing both for a
     * moment afterwards; check again once that has settled.
     */
    private val settleTimer = Timer(SETTLE_DELAY_MS) { settle() }.apply { isRepeats = false }

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
                wasMinimized && !isMinimized -> {
                    conform()
                    isFadingOut = false
                }

                !wasMinimized && isMinimized -> {
                    // However it was minimized, the controls come back with a fade on restore.
                    isFadingOut = true
                    // Minimized from outside (Win+Down): the hidden overlay would keep the keyboard.
                    if (overlay.isFocused) system.activate(video)
                }

                // Maximized or restored by the system: the area inside the frame changed.
                else -> {
                    conform()
                }
            }
        }

    private val overlayStateListener =
        object : WindowAdapter() {
            override fun windowIconified(event: WindowEvent) = minimizeFromOverlay()
        }

    /** Starts keeping the windows together. */
    fun attach() {
        conform()
        rememberSize()
        if (framed) minimizeHook = system.interceptMinimize(video, ::minimize)
        overlay.addComponentListener(geometryListener)
        overlay.addWindowListener(overlayStateListener)
        video.addComponentListener(geometryListener)
        video.addWindowFocusListener(focusListener)
        video.addWindowStateListener(stateListener)
        overlay.addPropertyChangeListener("graphicsConfiguration", configurationListener)
        video.addPropertyChangeListener("graphicsConfiguration", configurationListener)
    }

    /**
     * Starts moving the windows with the pointer, or resizing them from [edge]. Only for a
     * borderless video window: a framed one is moved and resized through its frame.
     */
    fun beginGesture(edge: ResizeEdge?) {
        if (isFullScreen || framed) return
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
                // Keep the pointer on the same spot of the window, like system dragging, and the
                // size in dp at the scale of the monitor the window is on now.
                null -> {
                    val size = sizeAtCurrentScale()
                    Rectangle(
                        cursor.x - (current.anchorX * size.width).toInt(),
                        cursor.y - (current.anchorY * size.height).toInt(),
                        size.width,
                        size.height,
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
        val ended = gesture ?: return
        gesture = null
        gestureTimer.stop()
        if (ended.edge == null) {
            // A DPI change may still be on its way after the drop; settle the size once it is.
            settleTimer.restart()
        } else {
            rememberSize()
        }
    }

    fun toggleFullScreen() = if (isFullScreen) exitFullScreen() else enterFullScreen()

    /** Covers the monitor that shows the overlay with both windows. */
    fun enterFullScreen() {
        if (isFullScreen) return
        gesture = null
        if (framed) {
            val monitor = system.monitorBounds(video)
            // Restored first, as browsers do: a maximized window keeps its maximized place.
            wasMaximized = video.extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH
            if (wasMaximized) video.extendedState = Frame.NORMAL
            windowedBounds = system.bounds(video)
            system.setFramed(video, false)
            system.setBounds(video, overlay, monitor)
        } else {
            windowedBounds = system.bounds(overlay)
            system.setBounds(video, overlay, system.monitorBounds(overlay))
        }
        isFullScreen = true
        log.log(System.Logger.Level.INFO, "Full screen on: ${system.bounds(overlay)}")
    }

    /** Puts both windows back where they were before [enterFullScreen]. */
    fun exitFullScreen() {
        if (!isFullScreen) return
        if (framed) {
            system.setFramed(video, true)
            windowedBounds?.let { system.setBounds(video, it) }
            if (wasMaximized) video.extendedState = Frame.MAXIMIZED_BOTH
            wasMaximized = false
            conform()
        } else {
            windowedBounds?.let { system.setBounds(video, overlay, it) }
        }
        windowedBounds = null
        isFullScreen = false
        // Full screen may have ended on a monitor with another scale.
        settleTimer.restart()
        log.log(System.Logger.Level.INFO, "Full screen off: ${system.bounds(overlay)}")
    }

    /**
     * Minimizes the pair once the overlay has faded out; the system hides the overlay along with
     * the video window it belongs to and activates the window behind, as for any minimized app.
     */
    fun minimize() {
        if (system.isMinimized(video) || minimizeTimer.isRunning) return
        isFadingOut = true
        minimizeTimer.restart()
    }

    override fun close() {
        minimizeHook?.close()
        minimizeHook = null
        minimizeTimer.stop()
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

    /**
     * Once DPI changes have settled: applies the windowed size in dp at the overlay's current
     * scale, keeping its top-left corner, then puts the video window under it.
     */
    private fun settle() {
        if (!framed && gesture == null && !isFullScreen && !system.isMinimized(overlay) && overlay.isShowing) {
            val bounds = system.bounds(overlay)
            val size = sizeAtCurrentScale()
            if (bounds.width != size.width || bounds.height != size.height) {
                system.setBounds(video, overlay, Rectangle(bounds.x, bounds.y, size.width, size.height))
            }
            windowedSize.appliedAt(system.scale(overlay))
        }
        conform()
    }

    /** Takes the overlay's current size as the windowed size in dp. */
    private fun rememberSize() = windowedSize.remember(system.bounds(overlay), system.scale(overlay))

    private fun sizeAtCurrentScale(): Dimension = windowedSize.at(system.scale(overlay))

    /**
     * Puts the overlay over the area inside a framed video window's frame, or the borderless
     * video window exactly under the overlay.
     */
    private fun conform() {
        if (system.isMinimized(overlay)) {
            minimizeFromOverlay()
            return
        }
        if (system.isMinimized(video) || !overlay.isShowing) return
        if (framed) {
            val inside = system.clientBounds(video)
            if (inside.width > 0 && inside.height > 0 && system.bounds(overlay) != inside) {
                system.setBounds(overlay, inside)
            }
            return
        }
        val target = system.bounds(overlay)
        if (system.bounds(video) != target) system.setBounds(video, target)
        // Resized by the system at the same scale (snapping, another program): that is the new
        // windowed size. At another scale, the size is left to settle().
        if (gesture == null && !isFullScreen) {
            if (windowedSize.isCurrentAt(system.scale(overlay))) rememberSize() else settleTimer.restart()
        }
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

/**
 * A window size in dp, kept apart from its size in pixels so that it survives scale changes. The
 * scale is the size of one dp in pixels.
 */
internal class WindowedSize {
    private var width = 0.0
    private var height = 0.0
    private var scale = 0.0

    /** Takes [bounds], in pixels at [scale], as the size. */
    fun remember(
        bounds: Rectangle,
        scale: Double,
    ) {
        width = bounds.width / scale
        height = bounds.height / scale
        this.scale = scale
    }

    /** The size in pixels at [scale]. */
    fun at(scale: Double): Dimension = Dimension((width * scale).roundToInt(), (height * scale).roundToInt())

    /** Records that the size was applied at [scale]. */
    fun appliedAt(scale: Double) {
        this.scale = scale
    }

    /** Whether the size was last taken or applied at [scale]; if not, it still has to be applied. */
    fun isCurrentAt(scale: Double): Boolean = scale == this.scale
}
