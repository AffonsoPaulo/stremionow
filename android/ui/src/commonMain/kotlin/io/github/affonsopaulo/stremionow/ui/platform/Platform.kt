// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.platform

import androidx.compose.runtime.ProvidableCompositionLocal

/**
 * Where platform services come from: Android's `Context`, or a single instance on the desktop.
 * Shared code passes it along without looking inside.
 */
expect abstract class PlatformContext

/** The [PlatformContext] of the current composition: `LocalContext` on Android. */
expect val LocalPlatformContext: ProvidableCompositionLocal<PlatformContext>

/**
 * Logs a warning under the app's tag. Never put stream or addon addresses in [message]: they
 * can carry debrid keys.
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
