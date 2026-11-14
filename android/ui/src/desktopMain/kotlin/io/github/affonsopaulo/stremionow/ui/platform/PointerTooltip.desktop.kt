// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.platform

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography

/** How long the pointer rests on the text before all of it shows, as for the player's controls. */
private const val POINTER_TOOLTIP_DELAY_MS = 600

/** Widest the bubble grows; longer text wraps, as long file names do. */
private val PointerTooltipMaxWidth = 480.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal actual fun SnPointerTooltip(
    text: String,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(SnRadius.radiusSm)
    TooltipArea(
        tooltip = {
            BasicText(
                text,
                modifier =
                    Modifier
                        .clearAndSetSemantics {}
                        .widthIn(max = PointerTooltipMaxWidth)
                        .background(SnColors.surfaceRaised, shape)
                        .border(1.dp, SnColors.border, shape)
                        .padding(horizontal = SnSpacing.space3, vertical = SnSpacing.space2),
                style = SnTypography.label.copy(color = SnColors.fg),
            )
        },
        delayMillis = POINTER_TOOLTIP_DELAY_MS,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, SnSpacing.space4)),
        content = content,
    )
}
