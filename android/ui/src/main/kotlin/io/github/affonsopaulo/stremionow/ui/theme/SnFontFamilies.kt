// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import io.github.affonsopaulo.stremionow.ui.R

/**
 * Font families of the design system, bundled as variable fonts so the app never depends on a
 * network font provider. Each weight is an instance of the same file, selected through the
 * `wght` axis; without the explicit variation the file's default instance would be used.
 */
object SnFontFamilies {
    /** Outfit, for title logos and page titles. */
    val display: FontFamily = FontFamily(
        variableFont(R.font.outfit, FontWeight.SemiBold),
        variableFont(R.font.outfit, FontWeight.Bold),
        variableFont(R.font.outfit, FontWeight.ExtraBold),
    )

    /** Inter, for everything else. */
    val text: FontFamily = FontFamily(
        variableFont(R.font.inter, FontWeight.Normal),
        variableFont(R.font.inter, FontWeight.Medium),
        variableFont(R.font.inter, FontWeight.SemiBold),
        variableFont(R.font.inter, FontWeight.Bold),
        variableFont(R.font.inter, FontWeight.ExtraBold),
    )
}

@OptIn(ExperimentalTextApi::class)
private fun variableFont(resId: Int, weight: FontWeight): Font =
    Font(
        resId = resId,
        weight = weight,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
    )
