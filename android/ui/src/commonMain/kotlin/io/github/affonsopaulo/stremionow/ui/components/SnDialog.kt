// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
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
    SnDialogFrame(title = title, onDismiss = onDismiss) {
        val cancelFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
        BasicText(body, style = SnTypography.body.copy(color = SnColors.fgSecondary))
        SnDialogActions {
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

/**
 * The panel every dialog shares: a raised surface with [title] on top and [content] below.
 * Back and a tap outside call [onDismiss]. [wide] gives the panel most of the screen, for
 * long content such as a report; its content should then scroll inside a weighted child.
 * [maxWidth] widens a regular panel, for content side by side such as a QR code and its text.
 */
@Composable
fun SnDialogFrame(
    title: String,
    onDismiss: () -> Unit,
    wide: Boolean = false,
    maxWidth: Dp = 440.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    // The platform's default width would cap a widened panel.
    val platformWidth = !wide && maxWidth <= 440.dp
    // Shortcuts that reach for the header leave it alone while a dialog is in front.
    DisposableEffect(Unit) {
        OpenDialogs.count++
        onDispose { OpenDialogs.count-- }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = platformWidth)) {
        val shape = RoundedCornerShape(SnRadius.radiusXl)
        Column(
            modifier =
                (
                    if (wide) {
                        Modifier
                            .padding(horizontal = SnLayout.gutter, vertical = SnSpacing.space6)
                            .widthIn(max = 880.dp)
                            .fillMaxWidth()
                            .fillMaxHeight()
                    } else if (platformWidth) {
                        Modifier.widthIn(max = maxWidth).fillMaxWidth()
                    } else {
                        Modifier
                            .padding(horizontal = SnLayout.gutter)
                            .widthIn(max = maxWidth)
                            .fillMaxWidth()
                    }
                ).semantics { paneTitle = title }
                    .background(SnColors.surfaceRaised, shape)
                    .border(1.dp, SnColors.border, shape)
                    .padding(SnSpacing.space6),
            verticalArrangement = Arrangement.spacedBy(SnSpacing.space3),
        ) {
            BasicText(title, style = SnTypography.headingMd.copy(color = SnColors.fg))
            content()
        }
    }
}

/**
 * The buttons at the foot of a dialog, aligned to the end. They wrap onto more lines when they
 * do not fit, as longer translations need on phones.
 */
@Composable
fun SnDialogActions(content: @Composable RowScope.() -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = SnSpacing.space3),
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space2, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space2),
        itemVerticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
