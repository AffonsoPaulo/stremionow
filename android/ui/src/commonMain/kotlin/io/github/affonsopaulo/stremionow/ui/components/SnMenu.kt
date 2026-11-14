// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSize
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography

private val MenuChipShape = RoundedCornerShape(SnRadius.radiusFull)

/**
 * A chip that shows the chosen option with a chevron and opens a menu of the [options] below
 * it, like the type menu on Stremio's library. [menuLabel] names the choice for screen readers
 * ("Type"). The menu takes the keyboard and remote focus while open; Back, Escape or a tap
 * outside closes it.
 */
@Composable
fun <T> SnMenuChip(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    menuLabel: String,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val state = interaction.collectSnInteraction()
    val current = label(selected)
    Box(modifier) {
        Row(
            Modifier
                .heightIn(min = SnSize.controlSm)
                .semantics { contentDescription = "$menuLabel: $current" }
                .snClickable(
                    MenuChipShape,
                    role = Role.DropdownList,
                    highlight = false,
                    interactionSource = interaction,
                ) { open = true }
                .snStateBackground(SnColors.surfaceMuted, MenuChipShape, state)
                .padding(start = SnSpacing.space4, end = SnSpacing.space3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space1),
        ) {
            BasicText(current, style = SnTypography.label.copy(color = SnColors.fg), maxLines = 1)
            Image(
                SnIcons.ChevronDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                colorFilter = ColorFilter.tint(SnColors.fgSecondary),
            )
        }
        if (open) {
            SnMenuPopup(
                options = options,
                selected = selected,
                label = label,
                onSelect = {
                    open = false
                    onSelect(it)
                },
                onDismiss = { open = false },
            )
        }
    }
}

@Composable
private fun <T> SnMenuPopup(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val gap = with(LocalDensity.current) { SnSpacing.space2.roundToPx() }
    val position = remember(gap) { BelowAnchorStart(gap) }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        val shape = RoundedCornerShape(SnRadius.radiusLg)
        val chosen = remember { FocusRequester() }
        // Keyboard and remote users land on the current choice.
        LaunchedEffect(Unit) { runCatching { chosen.requestFocus() } }
        Column(
            Modifier
                .widthIn(min = 200.dp)
                .width(IntrinsicSize.Max)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                }.background(SnColors.surfaceRaised, shape)
                .border(1.dp, SnColors.border, shape)
                .padding(vertical = SnSpacing.space2),
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                MenuOption(
                    text = label(option),
                    selected = isSelected,
                    modifier = if (isSelected) Modifier.focusRequester(chosen) else Modifier,
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}

@Composable
private fun MenuOption(
    text: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(SnRadius.radiusMd)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = SnSpacing.space2)
            .heightIn(min = 48.dp)
            .semantics { this.selected = selected }
            .snClickable(shape, onClick = onClick)
            .padding(horizontal = SnSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
    ) {
        BasicText(
            text,
            modifier = Modifier.weight(1f),
            style = SnTypography.body.copy(color = if (selected) SnColors.fg else SnColors.fgSecondary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (selected) {
            Image(SnIcons.Check, null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(SnColors.fg))
        } else {
            Spacer(Modifier.size(18.dp))
        }
    }
}

/** Below the anchor, its start edge lined up with the anchor's, kept inside the window. */
private class BelowAnchorStart(
    private val gap: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x =
            if (layoutDirection == LayoutDirection.Ltr) {
                anchorBounds.left
            } else {
                anchorBounds.right - popupContentSize.width
            }
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x.coerceIn(0, maxX), (anchorBounds.bottom + gap).coerceIn(0, maxY))
    }
}
