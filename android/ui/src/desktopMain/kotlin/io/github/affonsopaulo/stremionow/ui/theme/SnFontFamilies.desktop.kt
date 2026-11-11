// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

// The build copies Android's font resources to `font/` on the desktop classpath.

actual object SnFontFamilies {
    actual val display: FontFamily =
        FontFamily(
            variableFont("font/outfit.ttf", FontWeight.SemiBold),
            variableFont("font/outfit.ttf", FontWeight.Bold),
            variableFont("font/outfit.ttf", FontWeight.ExtraBold),
        )

    actual val text: FontFamily =
        FontFamily(
            variableFont("font/inter.ttf", FontWeight.Normal),
            variableFont("font/inter.ttf", FontWeight.Medium),
            variableFont("font/inter.ttf", FontWeight.SemiBold),
            variableFont("font/inter.ttf", FontWeight.Bold),
            variableFont("font/inter.ttf", FontWeight.ExtraBold),
        )
}

private fun variableFont(
    resource: String,
    weight: FontWeight,
): Font =
    Font(
        resource = resource,
        weight = weight,
        style = FontStyle.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
    )
