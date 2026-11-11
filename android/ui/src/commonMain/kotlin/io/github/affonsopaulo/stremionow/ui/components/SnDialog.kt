// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography

/**
 * Confirmation dialog. Back, a tap outside and [dismissText] all cancel. The cancel button
 * takes the initial focus, so a remote's OK press never confirms a destructive action by
 * accident; set [destructive] to paint the confirm button in the danger color.
 */
@Composable
fun SnDialog(
    title: String,
    body: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(SnRadius.radiusXl)
        val cancelFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
        Column(
            modifier =
                Modifier
                    .widthIn(max = 440.dp)
                    .fillMaxWidth()
                    .semantics { paneTitle = title }
                    .background(SnColors.surfaceRaised, shape)
                    .border(1.dp, SnColors.border, shape)
                    .padding(SnSpacing.space6),
            verticalArrangement = Arrangement.spacedBy(SnSpacing.space3),
        ) {
            BasicText(title, style = SnTypography.headingMd.copy(color = SnColors.fg))
            BasicText(body, style = SnTypography.body.copy(color = SnColors.fgSecondary))
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = SnSpacing.space3),
                horizontalArrangement = Arrangement.spacedBy(SnSpacing.space2, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SnButton(
                    text = dismissText,
                    onClick = onDismiss,
                    style = SnButtonStyle.Ghost,
                    modifier = Modifier.focusRequester(cancelFocus),
                )
                SnButton(
                    text = confirmText,
                    onClick = onConfirm,
                    style = if (destructive) SnButtonStyle.Danger else SnButtonStyle.Primary,
                )
            }
        }
    }
}
