// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.placeholder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import io.github.affonsopaulo.stremionow.ui.R
import io.github.affonsopaulo.stremionow.ui.components.SnWordmark
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography

/**
 * Temporary first screen of phase 0. It proves that the theme, the bundled fonts, the
 * translated strings and the Rust core are wired together on phones and TVs.
 */
@Composable
fun PlaceholderScreen(
    coreVersion: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SnColors.bg)
            .safeDrawingPadding(),
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space4, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SnWordmark(fontSize = 56.sp)
        BasicText(
            text = stringResource(R.string.placeholder_core_version, coreVersion),
            style = SnTypography.caption.copy(color = SnColors.fgMuted),
        )
    }
}

@Preview(widthDp = 960, heightDp = 540)
@Composable
private fun PlaceholderScreenPreview() {
    PlaceholderScreen(coreVersion = "0.0.1")
}
