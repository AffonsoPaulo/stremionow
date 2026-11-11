// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data

import io.github.affonsopaulo.stremionow.ui.platform.PlatformContext

/** What kind of network the device is on, for the "Only on Wi-Fi" torrent preference. */
expect object Connectivity {
    /**
     * True when the active network is mobile data. Wi-Fi and Ethernet count as fine even when
     * the device also has a cellular link; unknown states are treated as fine so the setting
     * never blocks playback by mistake.
     */
    fun onMobileData(context: PlatformContext): Boolean
}
