// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.platform

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle

/** ClipDescription.EXTRA_IS_SENSITIVE, which Android 13 added; older versions ignore it. */
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

internal actual fun copyToClipboard(
    context: PlatformContext,
    label: String,
    text: String,
    sensitive: Boolean,
) {
    runCatching {
        val clip = ClipData.newPlainText(label, text)
        if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    }
}

internal actual fun canShareText(context: PlatformContext): Boolean = true

internal actual fun shareText(
    context: PlatformContext,
    subject: String,
    text: String,
): Boolean {
    val send =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, text)
    return try {
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** Only to apps that open web links (CATEGORY_BROWSABLE). */
internal actual fun openWebPage(
    context: PlatformContext,
    url: String,
    allowHttp: Boolean,
): Boolean {
    if (!isWebPage(url, allowHttp)) return false
    val intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
