// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable

/**
 * Runs [onBack] instead of the default back action while [enabled]: the system back button or
 * gesture on Android, Esc on the desktop.
 */
@Composable
expect fun SnBackHandler(
    enabled: Boolean = true,
    onBack: () -> Unit,
)

/** The language the device or system is set to, as an ISO 639 code such as "pt". */
@Composable
@ReadOnlyComposable
expect fun deviceLanguage(): String

/** Whether the screen is large enough for the large layout (tablets, desktop windows). */
@Composable
@ReadOnlyComposable
internal expect fun isLargeScreen(): Boolean

/** Height of the screen (Android) or of the window (desktop), in dp. */
@Composable
@ReadOnlyComposable
internal expect fun screenHeightDp(): Int

/**
 * Whether the user asked the system for less motion: animations off on Android, "Show
 * animations in Windows" off on Windows. Read when the screen appears. Elsewhere, false.
 */
@Composable
internal expect fun rememberReducedMotion(): Boolean

/** A file size for display, such as "2.4 MB", in the platform's short format. */
@Composable
internal expect fun formatShortFileSize(bytes: Long): String

/**
 * Asks for access to the local network when an address needs it (Android 17 and later), then
 * calls [onResult] with whether the app may connect. Returns the function to call with the
 * address; where no permission exists, it calls [onResult] with true right away.
 */
@Composable
internal expect fun rememberLocalNetworkAccess(onResult: (granted: Boolean) -> Unit): (url: String) -> Unit

/**
 * Shows [text] in a small bubble once the pointer rests on [content], for text cut short with
 * an ellipsis: on the desktop. Elsewhere, where nothing hovers, only [content]. Screen readers
 * get the text from the content itself.
 */
@Composable
internal expect fun SnPointerTooltip(
    text: String,
    content: @Composable () -> Unit,
)
