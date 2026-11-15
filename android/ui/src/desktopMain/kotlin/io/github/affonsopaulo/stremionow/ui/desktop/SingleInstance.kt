// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import io.github.affonsopaulo.stremionow.ui.data.DesktopPaths
import io.github.affonsopaulo.stremionow.ui.platform.logWarning
import java.awt.EventQueue
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Keeps one copy of the desktop app running per user. A second copy (the shortcut clicked while
 * the app sits in the tray) would share the database and the cache, compete for the port other
 * devices play from, and on start turn HDR off under the first one's player. So the second copy
 * only asks the first to come to the front, and exits.
 */
object SingleInstance {
    private var guard: InstanceGuard? = null

    /** What a second launch does in this copy, on the AWT event thread; until set, nothing. */
    @Volatile var onSecondLaunch: (() -> Unit)? = null

    /**
     * Makes this process the app's only copy. Returns false when another copy runs: it was asked
     * to show itself, and this one should exit. Call first thing in `main`.
     */
    @Synchronized
    fun claim(): Boolean {
        if (guard != null) return true
        val claimed =
            InstanceGuard(DesktopPaths.appData) { EventQueue.invokeLater { onSecondLaunch?.invoke() } }
        if (claimed.claim()) {
            guard = claimed
            return true
        }
        claimed.signalRunning(::allowForeground)
        return false
    }

    /**
     * Lets the running copy take the foreground. Windows gives it only to the process the user
     * just started (this one) and hands it on when asked; otherwise the running copy's window
     * would only flash in the taskbar.
     */
    private fun allowForeground(processId: Long) {
        if (!Platform.isWindows()) return
        runCatching { Foreground.INSTANCE.AllowSetForegroundWindow(processId.toInt()) }
    }

    @Suppress("FunctionName", "ktlint:standard:function-naming")
    private interface Foreground : StdCallLibrary {
        fun AllowSetForegroundWindow(processId: Int): Boolean

        companion object {
            val INSTANCE: Foreground = Native.load("user32", Foreground::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }
}

/**
 * The lock behind [SingleInstance], in [folder]: an exclusive lock on `instance.lock`, which the
 * system releases when the process ends however it ends, and a loopback socket on which the
 * running copy hears a second launch. The socket's port, a random token and the process id are
 * in `instance.address`, in the user's own data folder; a connection that does not send the
 * token is ignored, so other programs cannot make the window jump.
 */
internal class InstanceGuard(
    private val folder: File,
    private val onSecondLaunch: () -> Unit,
) : AutoCloseable {
    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    private var server: ServerSocket? = null

    /**
     * Takes the lock: true when this is now the only copy. When the lock cannot even be tried
     * (the folder cannot be written), the app runs anyway: refusing to start would be worse,
     * and whatever needs the folder reports its own error.
     */
    fun claim(): Boolean {
        val opened =
            try {
                FileChannel.open(File(folder, LOCK_FILE).toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            } catch (failure: IOException) {
                logWarning("Could not check for another copy of the app", failure)
                return true
            }
        val taken =
            try {
                opened.tryLock()
            } catch (_: OverlappingFileLockException) {
                // Held by this process already (tests open two guards on one folder).
                null
            } catch (failure: IOException) {
                logWarning("Could not check for another copy of the app", failure)
                opened.close()
                return true
            }
        if (taken == null) {
            opened.close()
            return false
        }
        channel = opened
        lock = taken
        runCatching { listen() }.onFailure { logWarning("Could not listen for a second launch", it) }
        return true
    }

    /**
     * Asks the copy holding the lock to come to the front, after letting it take the foreground
     * with [allowForeground] (its process id). It may have just started and not be listening
     * yet, so this tries for a moment. Returns whether it was reached.
     */
    fun signalRunning(allowForeground: (Long) -> Unit = {}): Boolean {
        repeat(SIGNAL_ATTEMPTS) { attempt ->
            if (attempt > 0) Thread.sleep(SIGNAL_RETRY_MS)
            val address = readAddress() ?: return@repeat
            allowForeground(address.processId)
            val sent =
                runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), address.port), CONNECT_MS)
                        socket.getOutputStream().apply {
                            write((address.token + "\n").toByteArray(Charsets.US_ASCII))
                            flush()
                        }
                    }
                }.isSuccess
            if (sent) return true
        }
        return false
    }

    override fun close() {
        runCatching { server?.close() }
        runCatching { lock?.release() }
        runCatching { channel?.close() }
        server = null
        lock = null
        channel = null
    }

    private fun listen() {
        val socket = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
        server = socket
        val token = newToken()
        writeAddress(Address(socket.localPort, token, ProcessHandle.current().pid()))
        thread(name = "single-instance", isDaemon = true) {
            while (!socket.isClosed) {
                val client =
                    try {
                        socket.accept()
                    } catch (_: IOException) {
                        // Closed (tests), or a failed connection: wait a little rather than spin.
                        if (!socket.isClosed) Thread.sleep(SIGNAL_RETRY_MS)
                        continue
                    }
                client.use {
                    it.soTimeout = READ_MS
                    val received = runCatching { readLine(it) }.getOrNull()
                    if (received != null && sameToken(received, token)) onSecondLaunch()
                }
            }
        }
    }

    /** One short line; anything longer cannot be the token and is cut off. */
    private fun readLine(socket: Socket): String {
        val input = socket.getInputStream()
        val bytes = StringBuilder()
        while (bytes.length < MAX_LINE) {
            val next = input.read()
            if (next < 0 || next == '\n'.code) break
            bytes.append(next.toChar())
        }
        return bytes.toString().trim()
    }

    private fun sameToken(
        received: String,
        token: String,
    ) = MessageDigest.isEqual(received.toByteArray(Charsets.US_ASCII), token.toByteArray(Charsets.US_ASCII))

    private fun writeAddress(address: Address) {
        val target = File(folder, ADDRESS_FILE).toPath()
        val temporary = File(folder, "$ADDRESS_FILE.tmp").toPath()
        Files.writeString(temporary, "${address.port}\n${address.token}\n${address.processId}\n")
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun readAddress(): Address? =
        runCatching {
            val lines = File(folder, ADDRESS_FILE).readLines()
            Address(lines[0].trim().toInt(), lines[1].trim(), lines[2].trim().toLong())
        }.getOrNull()?.takeIf { it.port in 1..MAX_PORT && it.token.isNotEmpty() }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private class Address(
        val port: Int,
        val token: String,
        val processId: Long,
    )

    private companion object {
        const val LOCK_FILE = "instance.lock"
        const val ADDRESS_FILE = "instance.address"
        const val TOKEN_BYTES = 16
        const val MAX_LINE = 128
        const val MAX_PORT = 65_535
        const val BACKLOG = 4
        const val CONNECT_MS = 1_000
        const val READ_MS = 2_000
        const val SIGNAL_ATTEMPTS = 20
        const val SIGNAL_RETRY_MS = 100L
    }
}
