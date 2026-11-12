// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data.secrets

import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Windows: each secret is encrypted with DPAPI for the current user (`CryptProtectData`), with
 * app-specific entropy, and kept as one file in [folder]. Only the same Windows account on the
 * same machine can decrypt it.
 */
internal class DpapiSecrets(
    private val folder: File,
) : SecretBackend {
    override val description = "Windows data protection"

    override fun save(
        name: String,
        value: String,
    ) {
        val plain = value.toByteArray(Charsets.UTF_8)
        val sealed =
            try {
                Crypt32Util.cryptProtectData(plain, ENTROPY, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, DESCRIPTION, null)
            } finally {
                plain.fill(0)
            }
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

    override fun load(name: String): String? {
        val file = file(name).takeIf(File::isFile) ?: return null
        val data =
            runCatching {
                Crypt32Util.cryptUnprotectData(file.readBytes(), ENTROPY, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null)
            }.getOrElse {
                // Copied from another account or machine: unreadable, so forget it.
                clear(name)
                return null
            }
        return try {
            String(data, Charsets.UTF_8)
        } finally {
            data.fill(0)
        }
    }

    override fun clear(name: String) {
        file(name).delete()
    }

    private fun file(name: String) = File(folder, "$name.dpapi")

    private companion object {
        val ENTROPY = "StremioNow secrets".toByteArray(Charsets.UTF_8)
        const val DESCRIPTION = "StremioNow"
    }
}
