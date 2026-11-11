// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.mobile

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.affonsopaulo.stremionow.core.coreVersion
import io.github.affonsopaulo.stremionow.ui.placeholder.PlaceholderScreen

/** Entry point of the phone app. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The interface is always dark, so system bar icons stay light regardless of the system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val version = coreVersion()
        setContent {
            PlaceholderScreen(coreVersion = version)
        }
    }
}
