// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.platform

/**
 * Puts [text] on the clipboard; [label] names it where the system shows clips. A [sensitive]
 * clip (a link with a private key) is kept out of the system's clipboard preview where it has
 * one (Android 13 and later).
 */
internal expect fun copyToClipboard(
    context: PlatformContext,
    label: String,
    text: String,
    sensitive: Boolean = false,
)

/** Whether [shareText] can show the system's share sheet here. */
internal expect fun canShareText(context: PlatformContext): Boolean

/** Opens the system's share sheet with [text]; false when it cannot. */
internal expect fun shareText(
    context: PlatformContext,
    subject: String,
    text: String,
): Boolean

/**
 * Opens [url] in the browser; false when nothing can open it. Only HTTPS links open, and plain
 * HTTP ones too when [allowHttp] is set (an addon on the user's own network has no HTTPS).
 * Nothing else ever opens, since links can come from addons.
 */
internal expect fun openWebPage(
    context: PlatformContext,
    url: String,
    allowHttp: Boolean = false,
): Boolean

/** Whether [openWebPage] would open [url]. */
internal fun isWebPage(
    url: String,
    allowHttp: Boolean,
): Boolean {
    val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
    return scheme == "https" || (allowHttp && scheme == "http")
}
