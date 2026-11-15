// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data.secrets

import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.Win32Exception
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
        val sealed = file.readBytes()
        val data =
            try {
                Crypt32Util.cryptUnprotectData(sealed, ENTROPY, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null)
            } catch (failure: Win32Exception) {
                // Copied from another account or machine, or damaged: it can never be read, so
                // forget it. Anything else (a busy system) may pass; the file stays for next time.
                if (unreadableForever(failure.errorCode)) clear(name) else throw failure
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

    internal companion object {
        private val ENTROPY = "StremioNow secrets".toByteArray(Charsets.UTF_8)
        private const val DESCRIPTION = "StremioNow"

        /** ERROR_INVALID_DATA: a damaged blob, or one sealed with other entropy. */
        private const val ERROR_INVALID_DATA = 13

        /** NTE_BAD_KEY_STATE: sealed by another Windows account or machine. */
        private const val NTE_BAD_KEY_STATE = 0x8009000B.toInt()

        /** Whether DPAPI's error [code] means the value can never be decrypted here. */
        fun unreadableForever(code: Int): Boolean = code == ERROR_INVALID_DATA || code == NTE_BAD_KEY_STATE
    }
}
