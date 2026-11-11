// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import io.github.affonsopaulo.stremionow.ui.R

actual object SnFontFamilies {
    actual val display: FontFamily =
        FontFamily(
            variableFont(R.font.outfit, FontWeight.SemiBold),
            variableFont(R.font.outfit, FontWeight.Bold),
            variableFont(R.font.outfit, FontWeight.ExtraBold),
        )

    actual val text: FontFamily =
        FontFamily(
            variableFont(R.font.inter, FontWeight.Normal),
            variableFont(R.font.inter, FontWeight.Medium),
            variableFont(R.font.inter, FontWeight.SemiBold),
            variableFont(R.font.inter, FontWeight.Bold),
            variableFont(R.font.inter, FontWeight.ExtraBold),
        )
}

@OptIn(ExperimentalTextApi::class)
private fun variableFont(
    resId: Int,
    weight: FontWeight,
): Font =
    Font(
        resId = resId,
        weight = weight,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
    )
