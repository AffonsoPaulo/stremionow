// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadSvgPainter

/** Bundled flags (third_party/flags), by two-letter country code. */
private const val FLAGS_FOLDER = "/flags/"

private object FlagResources

@Composable
internal actual fun rememberFlagPainter(code: String): Painter? {
    val density = LocalDensity.current
    return remember(code, density) {
        // Only plain two-letter codes name a bundled file; anything else stays text.
        if (code.length != 2 || !code.all { it in 'a'..'z' }) return@remember null
        FlagResources::class.java.getResourceAsStream("$FLAGS_FOLDER$code.svg")?.use { stream ->
            runCatching { loadSvgPainter(stream, density) }.getOrNull()
        }
    }
}
