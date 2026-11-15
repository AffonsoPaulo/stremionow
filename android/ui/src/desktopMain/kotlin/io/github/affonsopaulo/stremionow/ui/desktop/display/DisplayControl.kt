// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop.display

import com.sun.jna.Platform
import java.awt.Window
import kotlin.math.roundToInt

/**
 * One monitor, as the system identifies it: the display path's adapter and target (for HDR) and
 * the GDI device name (for display modes), for example `\\.\DISPLAY1`.
 */
data class DisplayTarget(
    val adapterLow: Int,
    val adapterHigh: Int,
    val targetId: Int,
    val deviceName: String,
)

/** Whether a monitor can show HDR, and whether HDR is on now. */
data class HdrState(
    val supported: Boolean,
    val enabled: Boolean,
)

/**
 * The operating system's display settings that the player may change, with the user's consent,
 * for one monitor at a time. Every call may fail (a monitor unplugged, a driver refusing); failures
 * return null or false, never throw.
 */
interface DisplayControl {
    /** The monitor that shows most of [window], or null when it cannot be told. */
    fun displayOf(window: Window): DisplayTarget?

    /** HDR support and state of [target], or null when the monitor is gone or cannot report it. */
    fun hdr(target: DisplayTarget): HdrState?

    /** Turns HDR on or off on [target]. This setting persists until changed back. */
    fun setHdr(
        target: DisplayTarget,
        enabled: Boolean,
    ): Boolean

    /** The current refresh rate of [target] in Hz, or null. */
    fun refreshRate(target: DisplayTarget): Int?

    /** Refresh rates [target] offers at its current resolution and color depth. */
    fun refreshRates(target: DisplayTarget): List<Int>

    /**
     * Switches [target] to [hertz] at its current resolution, as a temporary mode that the system
     * drops when the app exits, even if it crashes.
     */
    fun setRefreshRate(
        target: DisplayTarget,
        hertz: Int,
    ): Boolean

    /** Puts [target] back in the mode saved in the system settings. */
    fun resetRefreshRate(target: DisplayTarget): Boolean

    companion object {
        /** The implementation for this system, or null where the app does not change displays. */
        fun current(): DisplayControl? = if (Platform.isWindows()) Win32DisplayControl() else null
    }
}

/** The refresh rate decisions, apart from the system so they can be tested. */
internal object RefreshRates {
    /** Highest refresh rate considered: above it, matching brings nothing visible. */
    private const val MAX_HERTZ = 240

    /**
     * The refresh rate to switch to for a video at [framesPerSecond], given the [current] rate and
     * the [available] ones, or null to stay: when the current rate already shows every frame for
     * the same time (240 Hz for 24 fps), or no rate does better. The highest matching rate wins,
     * so the interface stays fluid (120 Hz rather than 24 Hz for a film).
     *
     * Rates are whole numbers as the system reports them: 23.976 Hz shows as 23 and 59.94 Hz as
     * 59, so those count as multiples of 24 and 60 (and 30).
     */
    fun choose(
        framesPerSecond: Double,
        current: Int,
        available: List<Int>,
    ): Int? {
        if (!framesPerSecond.isFinite()) return null
        val nominal = framesPerSecond.roundToInt()
        if (nominal < 10 || nominal > MAX_HERTZ) return null
        if (matches(current, nominal)) return null
        return available.filter { it in 1..MAX_HERTZ && matches(it, nominal) }.maxOrNull()
    }

    /** Whether [hertz] shows every frame of a video at [framesPerSecond] for the same time. */
    fun suits(
        framesPerSecond: Double,
        hertz: Int,
    ): Boolean {
        if (!framesPerSecond.isFinite()) return false
        val nominal = framesPerSecond.roundToInt()
        return nominal in 1..MAX_HERTZ && matches(hertz, nominal)
    }

    private fun matches(
        hertz: Int,
        nominal: Int,
    ): Boolean {
        if (hertz <= 0) return false
        if (hertz % nominal == 0) return true
        // NTSC rates (23.976, 29.97, 59.94 Hz and their multiples) only exist for these families.
        val ntscFamily = nominal % 24 == 0 || nominal % 30 == 0
        return ntscFamily && (hertz + 1) % nominal == 0
    }
}
