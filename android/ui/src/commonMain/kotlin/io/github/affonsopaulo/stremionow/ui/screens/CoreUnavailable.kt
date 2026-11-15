// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.affonsopaulo.stremionow.ui.components.SnMessage
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.theme.SnColors

/**
 * Shown instead of the app when the core cannot open its data in [folder] (see
 * `AppGraph.openCore`): every screen needs it, so there is nothing else to show. Opening the
 * app again tries again.
 */
@Composable
fun CoreUnavailable(folder: String) {
    SnMessage(
        title = stringResource(Res.string.core_unavailable_title),
        body = stringResource(Res.string.core_unavailable_body) + "\n\n" + folder,
        modifier = Modifier.fillMaxSize().background(SnColors.bg),
    )
}
