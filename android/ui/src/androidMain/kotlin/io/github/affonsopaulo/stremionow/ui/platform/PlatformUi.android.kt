// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.platform

import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import io.github.affonsopaulo.stremionow.ui.data.LocalNetwork

@Composable
actual fun SnBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    BackHandler(enabled = enabled, onBack = onBack)
}

@Composable
@ReadOnlyComposable
actual fun deviceLanguage(): String = LocalConfiguration.current.locales[0].language

// The smallest width does not change with rotation, so a phone stays a phone in landscape
// (about 900 dp wide) instead of switching layouts mid-video.
@Composable
@ReadOnlyComposable
internal actual fun isLargeScreen(): Boolean = LocalConfiguration.current.smallestScreenWidthDp >= 600

@Composable
@ReadOnlyComposable
internal actual fun screenHeightDp(): Int = LocalConfiguration.current.screenHeightDp

/** Android has no separate setting: turning animations off (scale 0) is how users ask for it. */
@Composable
internal actual fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

@Composable
internal actual fun formatShortFileSize(bytes: Long): String =
    Formatter.formatShortFileSize(LocalContext.current, bytes)

@Composable
internal actual fun rememberLocalNetworkAccess(onResult: (granted: Boolean) -> Unit): (url: String) -> Unit {
    val context = LocalContext.current
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onResult)
    return { url ->
        val permission = LocalNetwork.permission
        when {
            permission != null && LocalNetwork.needsPermission(context, url) -> request.launch(permission)
            else -> onResult(true)
        }
    }
}

/** Phones and TVs have no pointer to rest on anything. */
@Composable
internal actual fun SnPointerTooltip(
    text: String,
    content: @Composable () -> Unit,
) {
    content()
}
