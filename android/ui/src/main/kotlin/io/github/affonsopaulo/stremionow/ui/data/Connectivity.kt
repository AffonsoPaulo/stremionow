// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** What kind of network the device is on, for the "Only on Wi-Fi" torrent preference. */
object Connectivity {
    /**
     * True when the active network is mobile data. Wi-Fi and Ethernet count as fine even when
     * the device also has a cellular link; unknown states are treated as fine so the setting
     * never blocks playback by mistake.
     */
    fun onMobileData(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
