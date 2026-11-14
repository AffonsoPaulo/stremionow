// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import io.github.affonsopaulo.stremionow.ui.StremioNowApp
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.TitleLink
import io.github.affonsopaulo.stremionow.ui.data.parse
import io.github.affonsopaulo.stremionow.ui.l10n.AppLanguage
import kotlinx.coroutines.launch

/** Entry point of the Android TV and Fire TV app. */
class MainActivity : ComponentActivity() {
    /** A title to open, from the home screen's Watch Next row. */
    private val link = mutableStateOf<TitleLink.Target?>(null)

    // The language picked in Settings, before Android 13 (see AppLanguage).
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) link.value = TitleLink.parse(intent?.data)
        setContent { StremioNowApp(isTv = true, link = link.value, onLinkOpened = { link.value = null }) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        link.value = TitleLink.parse(intent.data)
    }

    override fun onStop() {
        super.onStop()
        // Keep the home screen's Watch Next row in step with Continue watching.
        val context = applicationContext
        AppGraph.scope.launch {
            runCatching { AppGraph.core(context).continueWatching() }.onSuccess { WatchNext.publish(context, it) }
        }
    }
}
