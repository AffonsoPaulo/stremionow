// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data

import com.sun.jna.Platform
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt
import io.github.affonsopaulo.stremionow.ui.platform.PlatformContext
import io.github.affonsopaulo.stremionow.ui.platform.logWarning
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Secrets on the desktop.
 *
 * - Windows: encrypted with DPAPI for the current user (`CryptProtectData`), with app-specific
 *   entropy, one file per secret in the app's data folder. Only the same Windows account on the
 *   same machine can decrypt them.
 * - macOS and Linux: not implemented yet (Keychain and libsecret are the plan). Saving does
 *   nothing, so a session lasts until the app closes; nothing is ever written in plain text.
 */
internal actual object PlatformSecretStore {
    private val NAME = Regex("[a-z0-9_]{1,64}")
    private val ENTROPY = "StremioNow secrets".toByteArray(Charsets.UTF_8)
    private const val DESCRIPTION = "StremioNow"

    private val folder: File get() = File(DesktopPaths.appData, "secrets")

    actual fun save(
        context: PlatformContext,
        name: String,
        value: String,
    ) {
        if (!Platform.isWindows()) {
            logWarning("No secure storage on this system yet; \"$name\" is kept for this session only")
            return
        }
        val sealed =
            Crypt32Util.cryptProtectData(
                value.toByteArray(Charsets.UTF_8),
                ENTROPY,
                WinCrypt.CRYPTPROTECT_UI_FORBIDDEN,
                DESCRIPTION,
                null,
            )
        val target = file(name)
        target.parentFile.mkdirs()
        // Write next to the target and move it into place, so a crash never leaves half a file.
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeBytes(sealed)
        Files.move(
            temporary.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    actual fun load(
        context: PlatformContext,
        name: String,
    ): String? {
        if (!Platform.isWindows()) return null
        val file = file(name).takeIf(File::isFile) ?: return null
        return runCatching {
            val data =
                Crypt32Util.cryptUnprotectData(
                    file.readBytes(),
                    ENTROPY,
                    WinCrypt.CRYPTPROTECT_UI_FORBIDDEN,
                    null,
                )
            String(data, Charsets.UTF_8)
        }.getOrElse {
            // Copied from another account or machine: unreadable, so forget it.
            clear(context, name)
            null
        }
    }

    actual fun clear(
        context: PlatformContext,
        name: String,
    ) {
        file(name).delete()
    }

    private fun file(name: String): File {
        require(NAME.matches(name)) { "Invalid secret name" }
        return File(folder, "$name.dpapi")
    }
}
