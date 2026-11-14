// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    @Test
    fun levelsKeepTheirSizes() {
        assertEquals(StreamMemorySizes(64, 32), StreamMemory.LIGHT.sizes)
        assertEquals(StreamMemorySizes(128, 128), StreamMemory.BALANCED.sizes)
        assertEquals(StreamMemorySizes(256, 256), StreamMemory.SMOOTH.sizes)
        StreamMemory.entries.forEach { assertEquals(it, it.sizes.level) }
        assertNull(StreamMemorySizes(100, 200).level)
    }

    @Test
    fun customSizesStayInRange() {
        StreamMemorySizes(16, 8)
        StreamMemorySizes(1024, 1024)
        assertThrows(IllegalArgumentException::class.java) { StreamMemorySizes(15, 128) }
        assertThrows(IllegalArgumentException::class.java) { StreamMemorySizes(128, 7) }
        assertThrows(IllegalArgumentException::class.java) { StreamMemorySizes(1025, 128) }
        assertThrows(IllegalArgumentException::class.java) { StreamMemorySizes(128, 1025) }
    }
}
