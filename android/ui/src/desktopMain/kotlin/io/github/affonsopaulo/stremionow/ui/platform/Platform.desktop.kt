// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.platform

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI

/** The desktop has no per-window context; services are process-wide, so a single instance stands in. */
actual abstract class PlatformContext private constructor() {
    companion object INSTANCE : PlatformContext()
}

actual val LocalPlatformContext: ProvidableCompositionLocal<PlatformContext> =
    staticCompositionLocalOf { PlatformContext.INSTANCE }

private val log: System.Logger = System.getLogger("StremioNow")

internal actual fun logWarning(
    message: String,
    error: Throwable?,
) {
    log.log(System.Logger.Level.WARNING, message, error)
}

/** Web pages only: links come from addons, and other schemes could start any program. */
internal actual fun openExternalLink(
    context: PlatformContext,
    url: String,
) {
    val uri = runCatching { URI(url) }.getOrNull() ?: return
    if (uri.scheme?.lowercase() !in setOf("http", "https")) return
    if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) return
    // Starting the browser can take a moment; keep it off the interface thread.
    AppGraph.scope.launch {
        runCatching { Desktop.getDesktop().browse(uri) }.onFailure { logWarning("Could not open a link", it) }
    }
}

/** Set by the desktop app's launcher (`-Dstremionow.version=...`). */
internal actual fun appVersion(context: PlatformContext): String? = System.getProperty("stremionow.version")
