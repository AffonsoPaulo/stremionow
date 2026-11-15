// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.ui.platform.LocalContext
import io.github.affonsopaulo.stremionow.player.StreamMemory
import io.github.affonsopaulo.stremionow.ui.crash.RecentLog

actual typealias PlatformContext = Context

actual val LocalPlatformContext: ProvidableCompositionLocal<PlatformContext> = LocalContext

private const val TAG = "StremioNow"

internal actual fun logWarning(
    message: String,
    error: Throwable?,
) {
    Log.w(TAG, message, error)
    RecentLog.add('W', message, error)
}

internal actual fun logInfo(message: String) {
    Log.i(TAG, message)
}

internal actual fun openExternalLink(
    context: PlatformContext,
    url: String,
) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No app can open web links; there is nothing more useful to do.
    }
}

internal actual fun appVersion(context: PlatformContext): String? =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()

internal actual fun displaySwitchingSupported(): Boolean = false

internal actual fun automaticStreamMemory(): StreamMemory? = null

internal actual fun isDesktopApp(): Boolean = false

internal actual fun externalPlayerSupported(): Boolean = true

/** The `title` extra is read by VLC, MX Player and Just Player, among others. */
internal actual fun openInExternalPlayer(
    context: PlatformContext,
    url: String,
    title: String,
    chooserTitle: String,
): Boolean {
    val view =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(url), "video/*")
            .putExtra(EXTRA_TITLE, title)
    return try {
        context.startActivity(Intent.createChooser(view, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** The extra most video players read for the title they show. */
private const val EXTRA_TITLE = "title"
