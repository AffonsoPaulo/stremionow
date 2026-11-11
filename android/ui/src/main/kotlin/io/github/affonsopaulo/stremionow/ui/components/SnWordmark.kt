// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnFontFamilies

/** The typographic logo: "Stremio" in the text color and "Now" in the accent color. */
@Composable
fun SnWordmark(
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 26.sp,
) {
    val text = buildAnnotatedString {
        append("Stremio")
        withStyle(SpanStyle(color = SnColors.accent)) { append("Now") }
    }
    BasicText(
        text = text,
        modifier = modifier.semantics { contentDescription = "StremioNow" },
        style = TextStyle(
            color = SnColors.fg,
            fontFamily = SnFontFamilies.display,
            fontWeight = FontWeight.ExtraBold,
            fontSize = fontSize,
            letterSpacing = (-0.02).em,
        ),
    )
}
