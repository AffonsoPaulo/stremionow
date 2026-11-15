// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SerialQueueTest {
    @Test
    fun appliesEveryChangeOnTopOfTheLastInOrder() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val queue = SerialQueue(scope)
        // Stands in for the saved settings: each job reads, waits a little, then writes.
        var saved = 100
        val order = mutableListOf<Int>()
        repeat(20) { step ->
            queue.post {
                val read = saved
                Thread.sleep(1)
                saved = read + 5
                order += step
            }
        }
        val last = runBlocking { queue.run { saved } }
        assertEquals(200, last)
        assertEquals((0 until 20).toList(), order)
        scope.cancel()
    }

    @Test
    fun aFailureReachesItsCallerAndLaterJobsStillRun() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val queue = SerialQueue(scope)
        queue.post { error("posted jobs only log") }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { queue.run { error("refused") } }
        }
        assertEquals(42, runBlocking { queue.run { 42 } })
        scope.cancel()
    }
}
