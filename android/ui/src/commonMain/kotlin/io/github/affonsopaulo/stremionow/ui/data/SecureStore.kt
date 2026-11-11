// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data

import io.github.affonsopaulo.stremionow.ui.platform.PlatformContext

/**
 * Small secrets, such as the Stremio session key, encrypted with a key the operating system
 * keeps for the app or the user: the Android Keystore on Android, DPAPI on Windows. Where no
 * such store is available the secret is not kept at all, never written in plain text.
 */
object SecureStore {
    /** The Stremio session key, when signed in. */
    const val STREMIO_AUTH_KEY = "stremio_auth_key"

    fun save(
        context: PlatformContext,
        name: String,
        value: String,
    ) = PlatformSecretStore.save(context, name, value)

    /** The stored value, or null if there is none or it can no longer be decrypted. */
    fun load(
        context: PlatformContext,
        name: String,
    ): String? = PlatformSecretStore.load(context, name)

    fun clear(
        context: PlatformContext,
        name: String,
    ) = PlatformSecretStore.clear(context, name)
}

/** The platform's encrypted storage behind [SecureStore]. */
internal expect object PlatformSecretStore {
    fun save(
        context: PlatformContext,
        name: String,
        value: String,
    )

    fun load(
        context: PlatformContext,
        name: String,
    ): String?

    fun clear(
        context: PlatformContext,
        name: String,
    )
}
