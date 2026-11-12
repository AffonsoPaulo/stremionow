// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data

import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.ui.platform.PlatformContext
import java.net.InetAddress

internal actual object DevicePlatform {
    actual fun applicationContext(context: PlatformContext): PlatformContext = context

    /** The computer's name: `COMPUTERNAME` on Windows, the host name elsewhere. */
    actual fun deviceName(context: PlatformContext): String {
        System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }?.let { return it }
        val host = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
        return host?.substringBefore('.')?.takeIf { it.isNotBlank() } ?: "StremioNow"
    }

    actual fun kind(context: PlatformContext): DeviceKind = DeviceKind.Computer

    // Desktops are never on mobile data as far as the app can tell (see Connectivity), and
    // the core reads the gateway itself, so there is no network hint to give.
    actual fun watchNetwork(
        context: PlatformContext,
        core: StremioCore,
    ) = Unit

    actual fun setServing(
        context: PlatformContext,
        serving: Boolean,
    ) = Unit

    actual suspend fun <T> withMulticast(
        context: PlatformContext,
        block: suspend () -> T,
    ): T = block()
}
