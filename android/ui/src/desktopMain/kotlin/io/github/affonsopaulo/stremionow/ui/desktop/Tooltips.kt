// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography

/** How long the pointer rests on a control before its tooltip shows, as on most desktops. */
private const val TOOLTIP_DELAY_MS = 600

/** Where a tooltip opens relative to its control, so it stays inside the window. */
internal enum class TooltipSide { Above, Below }

/**
 * Shows [label] and, when the action has one, its keyboard [shortcut] in a small bubble once the
 * pointer rests on [content]. Screen readers already get the label from the control itself, so
 * the bubble adds nothing to the semantics tree.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WithTooltip(
    label: String,
    shortcut: String? = null,
    side: TooltipSide = TooltipSide.Above,
    content: @Composable () -> Unit,
) {
    val gap = SnSpacing.space2
    TooltipArea(
        tooltip = { TooltipBubble(label, shortcut) },
        delayMillis = TOOLTIP_DELAY_MS,
        tooltipPlacement =
            when (side) {
                TooltipSide.Above -> {
                    TooltipPlacement.ComponentRect(
                        anchor = Alignment.TopCenter,
                        alignment = Alignment.TopCenter,
                        offset = DpOffset(0.dp, -gap),
                    )
                }

                TooltipSide.Below -> {
                    TooltipPlacement.ComponentRect(
                        anchor = Alignment.BottomCenter,
                        alignment = Alignment.BottomCenter,
                        offset = DpOffset(0.dp, gap),
                    )
                }
            },
        content = content,
    )
}

@Composable
private fun TooltipBubble(
    label: String,
    shortcut: String?,
) {
    val shape = RoundedCornerShape(SnRadius.radiusSm)
    Row(
        Modifier
            .clearAndSetSemantics {}
            .background(SnColors.surfaceRaised, shape)
            .border(1.dp, SnColors.border, shape)
            .padding(horizontal = SnSpacing.space3, vertical = SnSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space2),
    ) {
        BasicText(label, style = SnTypography.label.copy(color = SnColors.fg))
        if (shortcut != null) {
            val keyShape = RoundedCornerShape(SnRadius.radiusSm)
            Box(
                Modifier
                    .background(SnColors.surfaceMuted, keyShape)
                    .border(1.dp, SnColors.borderStrong, keyShape)
                    .padding(horizontal = SnSpacing.space2, vertical = 1.dp),
            ) {
                BasicText(shortcut, style = SnTypography.caption.copy(color = SnColors.fgSecondary))
            }
        }
    }
}
