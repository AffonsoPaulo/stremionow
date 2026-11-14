// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.player

import java.lang.management.ManagementFactory

/**
 * The level the desktop player uses when the user has not chosen one, from this computer's
 * memory, so Settings can show which one that is.
 */
fun StreamMemory.Companion.forThisComputer(): StreamMemory = forDeviceMemory(totalMemoryBytes())

/** The computer's physical memory, or 0 (the lightest level) when the JVM cannot tell. */
private fun totalMemoryBytes(): Long =
    (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
        ?.totalMemorySize ?: 0L
