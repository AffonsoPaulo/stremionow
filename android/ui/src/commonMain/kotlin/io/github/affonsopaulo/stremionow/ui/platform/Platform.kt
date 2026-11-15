// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.platform

import androidx.compose.runtime.ProvidableCompositionLocal
import io.github.affonsopaulo.stremionow.player.StreamMemory
import kotlinx.coroutines.flow.StateFlow

/**
 * Where platform services come from: Android's `Context`, or a single instance on the desktop.
 * Shared code passes it along without looking inside.
 */
expect abstract class PlatformContext

/** The [PlatformContext] of the current composition: `LocalContext` on Android. */
expect val LocalPlatformContext: ProvidableCompositionLocal<PlatformContext>

/**
 * Logs a warning under the app's tag, and keeps it for crash reports (redacted before they are
 * written). Never put stream or addon addresses in [message]: they can carry debrid keys.
 */
internal expect fun logWarning(
    message: String,
    error: Throwable? = null,
)

/**
 * Opens [url] in the system's browser or the app registered for it, such as a streaming
 * service's page. Does nothing when nothing can open it.
 */
internal expect fun openExternalLink(
    context: PlatformContext,
    url: String,
)

/** The app's version name, for example "0.0.1", or null when it is unknown. */
internal expect fun appVersion(context: PlatformContext): String?

/**
 * Whether the app may change display settings for a video (HDR, refresh rate), so Settings
 * offers them: on Windows desktops. Android switches HDR by itself and matches the refresh rate
 * through the system's own setting.
 */
internal expect fun displaySwitchingSupported(): Boolean

/**
 * The level of "Memory for the video" the player picks on this device when the user has not
 * chosen one, where the user can choose: on the desktop. Null on Android, which keeps its own
 * sizes until it gets levels of its own.
 */
internal expect fun automaticStreamMemory(): StreamMemory?

/**
 * Whether this is the desktop app, with a keyboard and windows of its own: Settings offers the
 * shorter Shift+arrow seek step and how the window behaves there.
 */
internal expect fun isDesktopApp(): Boolean

/**
 * Whether sources can play in another app on this device (Settings' "Play in"): on Android,
 * phones and TVs, where VLC, MX Player, Just Player and others take video addresses. The
 * desktop's player is always its own.
 */
internal expect fun externalPlayerSupported(): Boolean

/**
 * Hands [url], the local server's playback address and never an addon's or debrid's, to another
 * app that plays video, through the system's chooser titled [chooserTitle]; [title] is what that
 * app shows, for the apps that read it. False when nothing can play it or the platform has no
 * such apps.
 */
internal expect fun openInExternalPlayer(
    context: PlatformContext,
    url: String,
    title: String,
    chooserTitle: String,
): Boolean

/**
 * The newer version of the app that was downloaded and installs when the app quits, or null
 * while there is none: the Windows app installed by its installer updates itself this way.
 */
internal expect fun downloadedUpdate(): StateFlow<String?>

/** Installs the version of [downloadedUpdate] now; the app closes and opens again. */
internal expect fun installUpdateNow()
