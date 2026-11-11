// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/**
 * Local network access. Since Android 17 apps need a runtime permission to reach devices on
 * the user's network, such as a self-hosted addon on a home server.
 */
object LocalNetwork {
    /** The permission, or null on Android versions that do not have it. */
    val permission: String?
        get() = if (Build.VERSION.SDK_INT >= 37) Manifest.permission.ACCESS_LOCAL_NETWORK else null

    /** Whether reaching [url] needs the permission and the app does not have it yet. */
    fun needsPermission(
        context: Context,
        url: String,
    ): Boolean {
        val permission = permission ?: return false
        return isLocalAddress(url) && context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
    }

    /** Whether [url] points at the local network: private IP ranges or local host names. */
    fun isLocalAddress(url: String): Boolean {
        val host =
            Uri
                .parse(url.trim())
                .host
                ?.lowercase()
                ?.removeSurrounding("[", "]") ?: return false
        if (host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa")) return true
        val octets = host.split('.').map { it.toIntOrNull() ?: return isLocalIpv6(host) }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        val (a, b) = octets
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
    }

    private fun isLocalIpv6(host: String): Boolean =
        ':' in host && (
            host.startsWith("fe8") || host.startsWith("fe9") || host.startsWith("fea") ||
                host.startsWith("feb") || host.startsWith("fc") || host.startsWith("fd")
        )
}
