// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSize
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography

/** Visual weight of a [SnButton]. */
enum class SnButtonStyle { Primary, Secondary, Tonal, Ghost, Danger }

private val Pill = RoundedCornerShape(SnRadius.radiusFull)

/** Space the focus ring takes outside an element: its gap plus its width. */
private val FocusRingSpace = 6.dp

/** Pill button of the design system. Only one [SnButtonStyle.Primary] per area. */
@Composable
fun SnButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: SnButtonStyle = SnButtonStyle.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val (background, foreground) =
        when (style) {
            SnButtonStyle.Primary -> SnColors.inverse to SnColors.onInverse
            SnButtonStyle.Secondary -> SnColors.overlayLight to SnColors.fg
            SnButtonStyle.Tonal -> SnColors.surfaceMuted to SnColors.fg
            SnButtonStyle.Ghost -> Color.Transparent to SnColors.fgSecondary
            SnButtonStyle.Danger -> SnColors.dangerSurface to SnColors.danger
        }
    Row(
        modifier =
            modifier
                .heightIn(min = SnSize.controlLg)
                .alpha(if (enabled) 1f else 0.45f)
                .snClickable(Pill, enabled = enabled, onClick = onClick)
                .background(background, Pill)
                .padding(horizontal = SnSpacing.space6),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) {
            Image(
                icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                colorFilter = ColorFilter.tint(foreground),
            )
        }
        BasicText(
            text = text,
            style = SnTypography.buttonLg.copy(color = foreground),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Round button with only an icon; [label] is announced by screen readers. */
@Composable
fun SnIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = SnColors.fg,
    background: Color = Color.Transparent,
    enabled: Boolean = true,
) {
    Box(
        modifier =
            modifier
                .size(SnSize.controlMd)
                .alpha(if (enabled) 1f else 0.45f)
                .semantics { contentDescription = label }
                .snClickable(Pill, enabled = enabled, onClick = onClick)
                .background(background, Pill),
        contentAlignment = Alignment.Center,
    ) {
        Image(icon, contentDescription = null, modifier = Modifier.size(22.dp), colorFilter = ColorFilter.tint(tint))
    }
}

/** Filter pill. Selected chips use the light fill. */
@Composable
fun SnChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .heightIn(min = SnSize.controlSm)
                .semantics { this.selected = selected }
                .snClickable(Pill, role = Role.Tab, onClick = onClick)
                .background(if (selected) SnColors.fg else SnColors.surfaceMuted, Pill)
                .padding(horizontal = SnSpacing.space4),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style = SnTypography.label.copy(color = if (selected) SnColors.onInverse else SnColors.fgSecondary),
            maxLines = 1,
        )
    }
}

/** A horizontally scrolling row of chips, one of which is selected. */
@Composable
fun <T> SnChipRow(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // Keep the selection in view, for example when a screen opens already filtered.
    val selectedIndex = options.indexOf(selected)
    LaunchedEffect(selectedIndex) { if (selectedIndex > 0) listState.animateScrollToItem(selectedIndex) }
    // Room for the focus ring around the first and last chips; the offset keeps the first
    // chip aligned with whatever sits above the row.
    LazyRow(
        modifier = modifier.offset(x = -FocusRingSpace),
        state = listState,
        contentPadding = PaddingValues(horizontal = FocusRingSpace, vertical = FocusRingSpace),
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space2),
    ) {
        itemsIndexed(options) { _, option ->
            SnChip(text = label(option), selected = option == selected, onClick = { onSelect(option) })
        }
    }
}

/** Underlined tabs for sections of one screen. */
@Composable
fun SnTabs(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        // Scrolls when translated labels do not fit, instead of squeezing them.
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space2),
        ) {
            tabs.forEachIndexed { index, title ->
                val selected = index == selectedIndex
                Column(
                    modifier =
                        Modifier
                            // The indicator below takes the label's width, not the whole row.
                            .width(IntrinsicSize.Max)
                            .semantics { this.selected = selected }
                            .snClickable(RoundedCornerShape(SnRadius.radiusSm), role = Role.Tab) { onSelect(index) }
                            .padding(horizontal = SnSpacing.space4),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BasicText(
                        text = title,
                        modifier = Modifier.padding(vertical = SnSpacing.space3),
                        style = SnTypography.tab.copy(color = if (selected) SnColors.fg else SnColors.fgMuted),
                        maxLines = 1,
                    )
                    Box(
                        Modifier
                            .height(3.dp)
                            .fillMaxWidth()
                            .background(if (selected) SnColors.fg else Color.Transparent),
                    )
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(SnColors.border),
        )
    }
}
