// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data

import io.github.affonsopaulo.stremionow.ui.data.secrets.SessionFallback
import io.github.affonsopaulo.stremionow.ui.data.secrets.systemSecretBackend
import io.github.affonsopaulo.stremionow.ui.platform.PlatformContext

/**
 * Secrets on the desktop, in the operating system's own store (see `data/secrets`):
 *
 * - Windows: DPAPI for the current user, one encrypted file per secret in the app's data folder;
 * - macOS: the login keychain (generic passwords, service "StremioNow");
 * - Linux: the Secret Service (GNOME Keyring, KWallet and others) through libsecret.
 *
 * When the store is missing or refuses (no libsecret or Secret Service, a locked or denied
 * keychain), nothing is kept and the session lasts until the app closes. Nothing is ever written
 * in plain text.
 */
internal actual object PlatformSecretStore {
    private val NAME = Regex("[a-z0-9_]{1,64}")

    private val store by lazy { SessionFallback(systemSecretBackend()) }

    actual fun save(
        context: PlatformContext,
        name: String,
        value: String,
    ) = store.save(checkName(name), value)

    actual fun load(
        context: PlatformContext,
        name: String,
    ): String? = store.load(checkName(name))

    actual fun clear(
        context: PlatformContext,
        name: String,
    ) = store.clear(checkName(name))

    private fun checkName(name: String): String = name.also { require(NAME.matches(it)) { "Invalid secret name" } }
}
