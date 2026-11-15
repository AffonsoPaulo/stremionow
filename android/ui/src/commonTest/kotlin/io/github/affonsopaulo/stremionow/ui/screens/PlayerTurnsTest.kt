// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerTurnsTest {
    @Test
    fun `work of a video left no longer counts`() {
        val turns = VideoTurns()
        val first = turns.next()
        assertTrue(turns.isCurrent(first))
        // Moving on to the next episode: the first video's late answers are dropped.
        val lookup = turns.next()
        assertFalse(turns.isCurrent(first))
        assertTrue(turns.isCurrent(lookup))
        assertEquals(lookup, turns.current)
        val second = turns.next()
        assertFalse(turns.isCurrent(lookup))
        assertTrue(turns.isCurrent(second))
    }

    @Test
    fun `the player is left once`() {
        var left = 0
        var navigations = 0
        val leave = LeaveOnce { left++ }
        assertFalse(leave.hasLeft)
        // Back, then a lookup that ends after it and would show the episode's sources.
        leave.to { navigations++ }
        leave.to { navigations++ }
        assertTrue(leave.hasLeft)
        assertEquals(1, left)
        assertEquals(1, navigations)
    }

    @Test
    fun `waiting for the provider ends`() {
        assertEquals(ProviderWait.Ready, providerWait(ready = true, failuresInARow = 0, waitedMs = 0))
        assertEquals(ProviderWait.Wait, providerWait(ready = false, failuresInARow = 0, waitedMs = 60_000))
        assertEquals(ProviderWait.Wait, providerWait(ready = false, failuresInARow = 4, waitedMs = 60_000))
        assertEquals(ProviderWait.GiveUp, providerWait(ready = false, failuresInARow = 5, waitedMs = 75_000))
        assertEquals(ProviderWait.GiveUp, providerWait(ready = false, failuresInARow = 0, waitedMs = 30 * 60_000L))
        assertEquals(ProviderWait.Ready, providerWait(ready = true, failuresInARow = 0, waitedMs = 31 * 60_000L))
    }

    @Test
    fun `a source refused for good ends the wait at once`() {
        assertEquals(ProviderWait.GiveUp, providerWait(ready = false, failuresInARow = 0, waitedMs = 0, failed = true))
    }
}
