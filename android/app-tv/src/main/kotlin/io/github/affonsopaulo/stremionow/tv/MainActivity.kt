// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import io.github.affonsopaulo.stremionow.core.CoreException
import io.github.affonsopaulo.stremionow.ui.StremioNowApp
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.TitleLink
import io.github.affonsopaulo.stremionow.ui.data.parse
import io.github.affonsopaulo.stremionow.ui.l10n.AppLanguage
import io.github.affonsopaulo.stremionow.ui.screens.CoreUnavailable
import kotlinx.coroutines.launch

/**
 * Entry point of the Android TV and Fire TV app. A single task (`singleTask` in the manifest):
 * a Watch Next tile picked while the app runs comes here through [onNewIntent] instead of
 * stacking a second copy of the app over the first.
 */
class MainActivity : ComponentActivity() {
    /** A title to open, from the home screen's Watch Next row. */
    private val link = mutableStateOf<TitleLink.Target?>(null)

    // The language picked in Settings, before Android 13 (see AppLanguage).
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) link.value = TitleLink.parse(intent?.data)
        // Every screen needs the core: a data folder it cannot open is explained here, rather
        // than failing inside the first screen. Other failures are bugs, and crash as before.
        val opened = AppGraph.openCore(applicationContext)
        opened.exceptionOrNull()?.let { if (it !is CoreException) throw it }
        setContent {
            if (opened.isSuccess) {
                StremioNowApp(isTv = true, link = link.value, onLinkOpened = { link.value = null })
            } else {
                CoreUnavailable(AppGraph.coreFolder(applicationContext))
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Only a title link changes anything; the launcher's own intent leaves the app as it was.
        TitleLink.parse(intent.data)?.let { link.value = it }
    }

    override fun onStop() {
        super.onStop()
        // Keep the home screen's Watch Next row in step with Continue watching.
        val context = applicationContext
        AppGraph.scope.launch { WatchNext.update(context) }
    }
}
