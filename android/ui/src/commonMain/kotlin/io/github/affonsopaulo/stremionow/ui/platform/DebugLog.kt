// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.platform

/**
 * What the app does while it finds and prepares sources, written to its log only when
 * "Debug log" is on in Settings › About (off by default), to find out why one does not play.
 * Messages never carry addresses or keys: addons, sources and files are named by their name,
 * kind and size only.
 */
object DebugLog {
    /** Set from the settings when the core opens and whenever they change. */
    @Volatile var enabled: Boolean = false

    /** Logs the message [message] builds, only when [enabled]. */
    fun log(message: () -> String) {
        if (enabled) logInfo("[debug] ${message()}")
    }
}

/** Logs a line under the app's tag, without keeping it for crash reports. */
internal expect fun logInfo(message: String)
