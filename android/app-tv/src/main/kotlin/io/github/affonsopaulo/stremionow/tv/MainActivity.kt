// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.affonsopaulo.stremionow.core.coreVersion
import io.github.affonsopaulo.stremionow.ui.placeholder.PlaceholderScreen

/** Entry point of the Android TV and Fire TV app. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val version = coreVersion()
        setContent {
            PlaceholderScreen(coreVersion = version)
        }
    }
}
