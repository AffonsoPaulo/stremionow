// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.player

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamMemoryTest {
    private val gib = 1024L * 1024 * 1024

    @Test
    fun followsTheDeviceMemory() {
        // Machines sold as 8, 16 and 32 GB report a little less to apps.
        assertEquals(StreamMemory.LIGHT, StreamMemory.forDeviceMemory(4 * gib))
        assertEquals(StreamMemory.LIGHT, StreamMemory.forDeviceMemory(7_800_000_000))
        assertEquals(StreamMemory.LIGHT, StreamMemory.forDeviceMemory(8 * gib))
        assertEquals(StreamMemory.BALANCED, StreamMemory.forDeviceMemory(8 * gib + 1))
        assertEquals(StreamMemory.BALANCED, StreamMemory.forDeviceMemory(15_900_000_000))
        assertEquals(StreamMemory.BALANCED, StreamMemory.forDeviceMemory(16 * gib))
        assertEquals(StreamMemory.SMOOTH, StreamMemory.forDeviceMemory(16 * gib + 1))
        assertEquals(StreamMemory.SMOOTH, StreamMemory.forDeviceMemory(33_500_000_000))
    }
}
