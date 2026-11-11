// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.ui.platform.LocalContext
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
