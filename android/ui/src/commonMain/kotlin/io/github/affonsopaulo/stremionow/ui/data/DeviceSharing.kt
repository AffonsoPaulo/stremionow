// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data

import io.github.affonsopaulo.stremionow.core.OtherDevice
import io.github.affonsopaulo.stremionow.core.SecretSink
import io.github.affonsopaulo.stremionow.core.ServeStatus
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.ui.platform.PlatformContext
import io.github.affonsopaulo.stremionow.ui.platform.logWarning
import kotlinx.coroutines.launch

/**
 * Serving torrents to other devices on the home network, and using another device for them
 * (ADR 0008). A device does one or the other.
 *
 * The core hands its secrets (the server's TLS key and paired devices, or this device's
 * pairing token) to the [SecureStore] through [SecretSink]s, and gets them back here at launch.
 */
object DeviceSharing {
    /** Restores what was on when the app last ran: serving, or the device used for torrents. */
    fun restore(
        context: PlatformContext,
        core: StremioCore,
    ) {
        val app = DevicePlatform.applicationContext(context)
        DevicePlatform.watchNetwork(app, core)
        AppGraph.scope.launch {
            val serving = runCatching { core.settings().serveToDevices }.getOrDefault(false)
            if (serving) {
                runCatching { startServing(app, core) }.onFailure { logWarning("Could not serve to other devices", it) }
            } else {
                useSavedDevice(app, core)
            }
        }
    }

    /** Starts serving under this device's name, with the identity kept from earlier runs. */
    suspend fun startServing(
        context: PlatformContext,
        core: StremioCore,
    ): ServeStatus {
        val app = DevicePlatform.applicationContext(context)
        val status =
            core.startServing(
                DevicePlatform.deviceName(app),
                SecureStore.load(app, SecureStore.SERVE_STATE),
                Sink(app, SecureStore.SERVE_STATE),
            )
        DevicePlatform.setServing(app, true)
        return status
    }

    /** Stops serving and goes back to the device used for torrents before, if any. */
    suspend fun stopServing(
        context: PlatformContext,
        core: StremioCore,
    ) {
        val app = DevicePlatform.applicationContext(context)
        core.stopServing()
        DevicePlatform.setServing(app, false)
        useSavedDevice(app, core)
    }

    /** Pairs with the device at [address] and uses it for torrents from now on. */
    suspend fun pair(
        context: PlatformContext,
        core: StremioCore,
        address: String,
        code: String,
    ): OtherDevice {
        val app = DevicePlatform.applicationContext(context)
        return core.pairWithDevice(
            address,
            code,
            DevicePlatform.deviceName(app),
            Sink(app, SecureStore.OTHER_DEVICE),
        )
    }

    /** Stops using the other device, asks it to forget this one and deletes the pairing. */
    suspend fun forget(
        context: PlatformContext,
        core: StremioCore,
    ) {
        core.forgetOtherDevice()
        SecureStore.clear(DevicePlatform.applicationContext(context), SecureStore.OTHER_DEVICE)
    }

    private fun useSavedDevice(
        context: PlatformContext,
        core: StremioCore,
    ) {
        val saved = SecureStore.load(context, SecureStore.OTHER_DEVICE) ?: return
        runCatching { core.useOtherDevice(saved, Sink(context, SecureStore.OTHER_DEVICE)) }
            .onFailure {
                // A pairing this version cannot read is useless; the user pairs again.
                SecureStore.clear(context, SecureStore.OTHER_DEVICE)
            }
    }

    /** Keeps what the core hands over in the secret store. Holds the application context only. */
    private class Sink(
        private val context: PlatformContext,
        private val name: String,
    ) : SecretSink {
        override fun save(value: String) = SecureStore.save(context, name, value)
    }
}

/** What serving to other devices needs from the platform. */
internal expect object DevicePlatform {
    /** The context that outlives screens: the core keeps it for its whole life. */
    fun applicationContext(context: PlatformContext): PlatformContext

    /** The name other devices see: the one the user gave this device, or its model. */
    fun deviceName(context: PlatformContext): String

    /**
     * Keeps the core told whether the device is on mobile data, so "Only on Wi-Fi" applies to
     * torrents started for other devices and to taking over from a server that went away.
     */
    fun watchNetwork(
        context: PlatformContext,
        core: StremioCore,
    )

    /** While serving, Android must let multicast through Wi-Fi for mDNS announcements. */
    fun setServing(
        context: PlatformContext,
        serving: Boolean,
    )

    /** Runs [block] with multicast let through Wi-Fi, for discovery. */
    suspend fun <T> withMulticast(
        context: PlatformContext,
        block: suspend () -> T,
    ): T
}
