// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.crash

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The app's last warnings, kept in memory for crash reports. Nothing here is written to disk
 * unless a crash report is, and then only after [Redactor] has gone over it.
 */
internal object RecentLog {
    const val CAPACITY = 50

    /** Longest line kept; longer messages are cut. */
    private const val MAX_LINE = 500

    private val lines = ArrayDeque<String>(CAPACITY)
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun add(
        level: Char,
        message: String,
        error: Throwable? = null,
    ) {
        val cause = error?.let { " (${it.javaClass.name}: ${it.message.orEmpty()})" }.orEmpty()
        val line = "${LocalTime.now().format(timeFormat)} $level $message$cause".replace('\n', ' ').take(MAX_LINE)
        synchronized(lines) {
            if (lines.size == CAPACITY) lines.removeFirst()
            lines.addLast(line)
        }
    }

    /** The lines in the order they were added, oldest first. */
    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }
}
