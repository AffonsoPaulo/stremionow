// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import java.util.Locale

/** Compose Multiplatform turns Esc into a back event on the desktop; this handles it while [enabled]. */
@Composable
actual fun SnBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    val currentOnBack by rememberUpdatedState(onBack)
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = enabled,
        onBackCompleted = { currentOnBack() },
    )
}

@Composable
@ReadOnlyComposable
actual fun deviceLanguage(): String = Locale.getDefault().language

/** Desktop windows always use the large layout: header pills, avatar, pointer and keyboard. */
@Composable
@ReadOnlyComposable
internal actual fun isLargeScreen(): Boolean = true

@Composable
@ReadOnlyComposable
internal actual fun screenHeightDp(): Int =
    with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height
            .toDp()
            .value
            .toInt()
    }

/** Decimal units, as Android shows them: "850 kB", "2.4 MB", "12 GB". */
@Composable
internal actual fun formatShortFileSize(bytes: Long): String {
    var value = bytes.coerceAtLeast(0).toDouble()
    var unit = 0
    while (value >= 1000 && unit < FILE_SIZE_UNITS.lastIndex) {
        value /= 1000
        unit++
    }
    val pattern = if (unit == 0 || value >= 100) "%.0f %s" else "%.1f %s"
    return String.format(Locale.getDefault(), pattern, value, FILE_SIZE_UNITS[unit])
}

private val FILE_SIZE_UNITS = listOf("B", "kB", "MB", "GB", "TB")

/** Desktop systems do not gate the local network behind a permission. */
@Composable
internal actual fun rememberLocalNetworkAccess(onResult: (granted: Boolean) -> Unit): (url: String) -> Unit {
    val currentOnResult by rememberUpdatedState(onResult)
    return { _ -> currentOnResult(true) }
}
