// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter

// Android's emoji font draws flags itself.
@Composable
internal actual fun rememberFlagPainter(code: String): Painter? = null
