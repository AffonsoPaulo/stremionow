// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class InstanceGuardTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun aSecondCopyBringsTheFirstToTheFront() {
        val launches = CountDownLatch(1)
        val first = InstanceGuard(folder.root) { launches.countDown() }
        first.use {
            assertTrue(first.claim())
            val second = InstanceGuard(folder.root) {}
            assertFalse(second.claim())
            val processes = mutableListOf<Long>()
            assertTrue(second.signalRunning { processes += it })
            assertTrue(launches.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(ProcessHandle.current().pid()), processes)
        }
    }

    @Test
    fun theLockIsFreeAgainOnceTheFirstCopyEnds() {
        InstanceGuard(folder.root) {}.use { assertTrue(it.claim()) }
        InstanceGuard(folder.root) {}.use { assertTrue(it.claim()) }
    }

    @Test
    fun aConnectionWithoutTheTokenIsIgnored() {
        val launches = AtomicInteger()
        val signalled = CountDownLatch(1)
        InstanceGuard(folder.root) {
            launches.incrementAndGet()
            signalled.countDown()
        }.use { guard ->
            assertTrue(guard.claim())
            val port = File(folder.root, "instance.address").readLines()[0].toInt()
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                socket.getOutputStream().write("not the token\n".toByteArray())
            }
            // A real signal afterwards still arrives, and is the only one counted.
            assertTrue(InstanceGuard(folder.root) {}.signalRunning())
            assertTrue(signalled.await(5, TimeUnit.SECONDS))
            assertEquals(1, launches.get())
        }
    }

    @Test
    fun withNobodyListeningTheSignalGivesUp() {
        assertFalse(InstanceGuard(folder.root) {}.signalRunning())
    }
}
