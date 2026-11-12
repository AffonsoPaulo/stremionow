// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data.secrets

import com.sun.jna.Memory
import com.sun.jna.Platform
import io.github.affonsopaulo.stremionow.ui.data.DesktopPaths
import io.github.affonsopaulo.stremionow.ui.platform.logWarning
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One operating system's secret storage. Names are validated by the caller. Every method may
 * block (a keyring unlock prompt, a D-Bus round trip) and throws [SecretStoreUnavailableException]
 * when the store cannot be used at all.
 */
internal interface SecretBackend {
    /** Short name for logs, such as "Keychain". */
    val description: String

    fun save(
        name: String,
        value: String,
    )

    /** The stored value, or null when there is none. */
    fun load(name: String): String?

    /** Removes the value; nothing happens when there is none. */
    fun clear(name: String)
}

/** The store cannot be used: missing library or service, locked or denied keychain. */
internal class SecretStoreUnavailableException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Keeps secrets in the system store when it works, and otherwise keeps nothing: the session then
 * lasts until the app closes. Nothing is ever written in plain text. The first failure is logged;
 * later ones are not, so a missing keyring does not flood the log.
 */
internal class SessionFallback(
    private val backend: SecretBackend?,
) {
    private val warned = AtomicBoolean(false)

    fun save(
        name: String,
        value: String,
    ) {
        attempt("save \"$name\"") { it.save(name, value) }
    }

    fun load(name: String): String? = attempt("read \"$name\"") { it.load(name) }

    fun clear(name: String) {
        attempt("clear \"$name\"") { it.clear(name) }
    }

    private fun <T> attempt(
        what: String,
        block: (SecretBackend) -> T,
    ): T? {
        if (backend == null) {
            warnOnce("No secure storage on this system; the session lasts until the app closes", null)
            return null
        }
        val failure =
            try {
                return block(backend)
            } catch (failure: SecretStoreUnavailableException) {
                failure
            } catch (failure: RuntimeException) {
                failure
            } catch (failure: LinkageError) {
                // A native library or one of its symbols is missing (UnsatisfiedLinkError).
                failure
            }
        warnOnce("${backend.description} could not $what; the session lasts until the app closes", failure)
        return null
    }

    private fun warnOnce(
        message: String,
        failure: Throwable?,
    ) {
        if (warned.compareAndSet(false, true)) logWarning(message, failure)
    }
}

/** The store for the running system: DPAPI, the macOS Keychain or the Secret Service. */
internal fun systemSecretBackend(): SecretBackend? =
    when {
        Platform.isWindows() -> DpapiSecrets(File(DesktopPaths.appData, "secrets"))
        Platform.isMac() -> KeychainSecrets(KeychainSecrets.SERVICE)
        Platform.isLinux() || Platform.isFreeBSD() -> LibsecretSecrets(LibsecretSecrets.SCHEMA)
        else -> null
    }

/** Copies [value] into native memory as NUL-terminated UTF-8; [wipe] it once the call returns. */
internal fun nativeUtf8(value: String): Memory {
    val bytes = value.toByteArray(Charsets.UTF_8)
    return Memory(bytes.size + 1L).apply {
        write(0, bytes, 0, bytes.size)
        setByte(bytes.size.toLong(), 0)
        bytes.fill(0)
    }
}

/** Overwrites native memory that held a secret before it is freed. */
internal fun Memory.wipe() {
    clear()
    close()
}
