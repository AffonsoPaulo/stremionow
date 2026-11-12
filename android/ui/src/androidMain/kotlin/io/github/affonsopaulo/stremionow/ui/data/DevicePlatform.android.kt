// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.ui.platform.PlatformContext

internal actual object DevicePlatform {
    @Volatile private var watching = false
    private var servingLock: WifiManager.MulticastLock? = null

    actual fun applicationContext(context: PlatformContext): PlatformContext = context.applicationContext

    actual fun deviceName(context: PlatformContext): String =
        Settings.Global
            .getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    actual fun watchNetwork(
        context: PlatformContext,
        core: StremioCore,
    ) {
        if (watching) return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        watching = true
        core.setOnMobileData(Connectivity.onMobileData(context))
        manager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    core.setOnMobileData(
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                    )
                }

                override fun onLost(network: Network) {
                    core.setOnMobileData(false)
                }
            },
        )
    }

    @Synchronized
    actual fun setServing(
        context: PlatformContext,
        serving: Boolean,
    ) {
        if (serving && servingLock == null) {
            servingLock = multicastLock(context, "stremionow-serve")?.apply { acquire() }
        } else if (!serving) {
            servingLock?.takeIf { it.isHeld }?.release()
            servingLock = null
        }
    }

    actual suspend fun <T> withMulticast(
        context: PlatformContext,
        block: suspend () -> T,
    ): T {
        val lock = multicastLock(context, "stremionow-discovery")?.apply { acquire() }
        try {
            return block()
        } finally {
            lock?.takeIf { it.isHeld }?.release()
        }
    }

    private fun multicastLock(
        context: Context,
        tag: String,
    ): WifiManager.MulticastLock? =
        context.applicationContext
            .getSystemService(WifiManager::class.java)
            ?.createMulticastLock(tag)
            ?.apply { setReferenceCounted(false) }
}
