// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.ui.platform.PlatformContext
import java.net.Inet4Address

internal actual object DevicePlatform {
    @Volatile private var watching = false
    private var servingLock: WifiManager.MulticastLock? = null

    actual fun applicationContext(context: PlatformContext): PlatformContext = context.applicationContext

    actual fun deviceName(context: PlatformContext): String =
        Settings.Global
            .getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    actual fun kind(context: PlatformContext): DeviceKind {
        val uiMode = context.getSystemService(UiModeManager::class.java)?.currentModeType
        return when {
            uiMode == Configuration.UI_MODE_TYPE_TELEVISION -> DeviceKind.Tv
            context.resources.configuration.smallestScreenWidthDp >= 600 -> DeviceKind.Tablet
            else -> DeviceKind.Phone
        }
    }

    actual fun watchNetwork(
        context: PlatformContext,
        core: StremioCore,
    ) {
        if (watching) return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        watching = true
        core.setOnMobileData(Connectivity.onMobileData(context))
        core.setNetworkHint(manager.getLinkProperties(manager.activeNetwork)?.let(::networkHint))
        manager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: LinkProperties,
                ) {
                    core.setNetworkHint(networkHint(linkProperties))
                }

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
                    core.setNetworkHint(null)
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

    /**
     * What tells this network from others without location access: Android hides the Wi-Fi
     * name and the gateway's MAC address from apps, so the gateway, DHCP server, DNS servers,
     * search domains and IPv4 prefix stand in. Weaker than a MAC address (two networks set up
     * alike look the same), and documented as such in ADR 0008.
     */
    private fun networkHint(properties: LinkProperties): String {
        val gateways =
            properties.routes
                .filter { it.isDefaultRoute && it.gateway is Inet4Address }
                .mapNotNull { it.gateway?.hostAddress }
                .sorted()
        val dhcp = if (Build.VERSION.SDK_INT >= 30) properties.dhcpServerAddress?.hostAddress else null
        val dns = properties.dnsServers.mapNotNull { it.hostAddress }.sorted()
        val prefixes =
            properties.linkAddresses
                .filter { it.address is Inet4Address }
                .map { "${it.address.hostAddress}/${it.prefixLength}" }
                .map { it.substringBeforeLast('.') + "/" + it.substringAfter('/') }
                .sorted()
        return listOf(
            "gw=${gateways.joinToString(",")}",
            "dhcp=${dhcp.orEmpty()}",
            "dns=${dns.joinToString(",")}",
            "domains=${properties.domains.orEmpty()}",
            "prefix=${prefixes.joinToString(",")}",
        ).joinToString(";")
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
