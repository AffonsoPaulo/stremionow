// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class CatchingTest {
    @Test
    fun `failures are caught and results kept`() {
        assertEquals(2, runCatchingUnlessCancelled { 1 + 1 }.getOrNull())
        assertTrue(runCatchingUnlessCancelled { error("offline") }.exceptionOrNull() is IllegalStateException)
    }

    @Test(expected = CancellationException::class)
    fun `a cancellation goes on through`() {
        runCatchingUnlessCancelled { throw CancellationException("replaced by a newer load") }
    }
}
