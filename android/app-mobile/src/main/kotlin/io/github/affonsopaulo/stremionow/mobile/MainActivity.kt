// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.mobile

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.affonsopaulo.stremionow.core.CoreException
import io.github.affonsopaulo.stremionow.ui.StremioNowApp
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.l10n.AppLanguage
import io.github.affonsopaulo.stremionow.ui.screens.CoreUnavailable

/** Entry point of the phone app. */
class MainActivity : ComponentActivity() {
    // The language picked in Settings, before Android 13 (see AppLanguage).
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The interface is always dark, so system bar icons stay light regardless of the system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // Every screen needs the core: a data folder it cannot open is explained here, rather
        // than failing inside the first screen. Other failures are bugs, and crash as before.
        val opened = AppGraph.openCore(applicationContext)
        opened.exceptionOrNull()?.let { if (it !is CoreException) throw it }
        setContent {
            if (opened.isSuccess) {
                StremioNowApp(isTv = false)
            } else {
                CoreUnavailable(AppGraph.coreFolder(applicationContext))
            }
        }
    }
}
