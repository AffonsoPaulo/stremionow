// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import io.github.affonsopaulo.stremionow.core.AppSettings
import io.github.affonsopaulo.stremionow.core.StreamMemoryChoice
import io.github.affonsopaulo.stremionow.player.StreamMemory
import io.github.affonsopaulo.stremionow.player.StreamMemorySizes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoMemoryTest {
    private fun settings(
        choice: StreamMemoryChoice,
        ahead: UInt = 128u,
        behind: UInt = 128u,
    ) = defaultSettings().copy(
        streamMemory = choice,
        streamMemoryAheadMib = ahead,
        streamMemoryBehindMib = behind,
    )

    @Test
    fun choicesBecomePlayerOptions() {
        assertEquals(null to null, settings(StreamMemoryChoice.AUTOMATIC).streamMemoryOptions())
        assertEquals(null to null, (null as AppSettings?).streamMemoryOptions())
        assertEquals(StreamMemory.LIGHT to null, settings(StreamMemoryChoice.LIGHT).streamMemoryOptions())
        assertEquals(StreamMemory.BALANCED to null, settings(StreamMemoryChoice.BALANCED).streamMemoryOptions())
        assertEquals(StreamMemory.SMOOTH to null, settings(StreamMemoryChoice.SMOOTH).streamMemoryOptions())
        assertEquals(
            null to StreamMemorySizes(300, 40),
            settings(StreamMemoryChoice.CUSTOM, 300u, 40u).streamMemoryOptions(),
        )
    }

    @Test
    fun customSizesOutOfRangeAreBroughtIn() {
        assertEquals(StreamMemorySizes(16, 1024), settings(StreamMemoryChoice.CUSTOM, 1u, 5000u).customStreamMemory())
    }

    @Test
    fun theSizesInEffectFollowTheChoice() {
        assertEquals(
            StreamMemory.SMOOTH.sizes,
            settings(StreamMemoryChoice.AUTOMATIC).effectiveStreamMemory(StreamMemory.SMOOTH),
        )
        assertEquals(
            StreamMemory.LIGHT.sizes,
            settings(StreamMemoryChoice.LIGHT).effectiveStreamMemory(StreamMemory.SMOOTH),
        )
        assertEquals(
            StreamMemorySizes(200, 100),
            settings(StreamMemoryChoice.CUSTOM, 200u, 100u).effectiveStreamMemory(StreamMemory.SMOOTH),
        )
    }

    @Test
    fun exactSizesOfALevelAreThatLevel() {
        val base = settings(StreamMemoryChoice.AUTOMATIC)
        assertEquals(StreamMemoryChoice.BALANCED, base.withStreamMemory(StreamMemorySizes(128, 128)).streamMemory)
        val custom = base.withStreamMemory(StreamMemorySizes(200, 64))
        assertEquals(StreamMemoryChoice.CUSTOM, custom.streamMemory)
        assertEquals(200u, custom.streamMemoryAheadMib)
        assertEquals(64u, custom.streamMemoryBehindMib)
    }

    @Test
    fun eachLevelSaysWhatItKeeps() {
        // 128 MiB is 43 s at 25 Mbit/s (4K) and 3.6 min at 5 Mbit/s (1080p).
        assertEquals(RoughDuration.Seconds(40) to RoughDuration.Minutes(4), StreamMemory.BALANCED.sizes.keepsAbout())
        // 32 MiB: 10.7 s and 54 s.
        assertEquals(RoughDuration.Seconds(10) to RoughDuration.Seconds(50), StreamMemory.LIGHT.sizes.keepsAbout())
        // 256 MiB: 86 s and 7.2 min.
        assertEquals(RoughDuration.Minutes(1) to RoughDuration.Minutes(7), StreamMemory.SMOOTH.sizes.keepsAbout())
    }

    @Test
    fun durationsAreRounded() {
        assertEquals(RoughDuration.Seconds(1), roughDuration(0.2))
        assertEquals(RoughDuration.Seconds(3), roughDuration(2.7))
        assertEquals(RoughDuration.Seconds(10), roughDuration(10.0))
        assertEquals(RoughDuration.Seconds(55), roughDuration(59.9))
        assertEquals(RoughDuration.Minutes(1), roughDuration(60.0))
        assertEquals(RoughDuration.Minutes(2), roughDuration(90.0))
        assertEquals(43.0, playingSeconds(128, UHD_BITS_PER_SECOND), 0.1)
    }

    @Test
    fun sizesAreParsedWithinTheirRange() {
        assertEquals(128, parseMiB("128", StreamMemorySizes.AHEAD_RANGE))
        assertEquals(64, parseMiB(" 64 ", StreamMemorySizes.AHEAD_RANGE))
        assertNull(parseMiB("", StreamMemorySizes.AHEAD_RANGE))
        assertNull(parseMiB("15", StreamMemorySizes.AHEAD_RANGE))
        assertNull(parseMiB("1025", StreamMemorySizes.AHEAD_RANGE))
        assertNull(parseMiB("-20", StreamMemorySizes.AHEAD_RANGE))
        assertNull(parseMiB("1e3", StreamMemorySizes.AHEAD_RANGE))
        assertNull(parseMiB("99999999999", StreamMemorySizes.AHEAD_RANGE))
        assertEquals(8, parseMiB("8", StreamMemorySizes.BEHIND_RANGE))
    }

    @Test
    fun onlyValidChangedSizesWaitToBeSaved() {
        val effective = StreamMemorySizes(128, 128)
        assertNull(pendingSizes(128, 128, effective))
        assertNull(pendingSizes(null, 64, effective))
        assertNull(pendingSizes(256, null, effective))
        assertEquals(StreamMemorySizes(256, 64), pendingSizes(256, 64, effective))
    }
}
