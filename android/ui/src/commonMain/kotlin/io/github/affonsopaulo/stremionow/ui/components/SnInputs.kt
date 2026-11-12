// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSize
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography

/**
 * The large search field. Search runs as the user types, so the keyboard's search key only
 * closes the keyboard to reveal the results.
 */
@Composable
fun SnSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    label: String,
    hint: String,
    clearLabel: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = FocusRequester(),
) {
    val shape = RoundedCornerShape(SnRadius.radiusLg)
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier =
            modifier
                .padding(horizontal = SnLayout.gutter, vertical = SnSpacing.space2)
                .fillMaxWidth()
                .height(64.dp)
                .background(SnColors.surfaceInput, shape)
                .border(2.dp, SnColors.accent, shape)
                .padding(start = SnSpacing.space6, end = SnSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
    ) {
        Image(SnIcons.Search, null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(SnColors.fgSecondary))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                BasicText(hint, style = SnTypography.sectionTitle.copy(color = SnColors.fgMuted))
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .dpadExits()
                        .semantics { contentDescription = label },
                textStyle = SnTypography.sectionTitle.copy(color = SnColors.fg),
                singleLine = true,
                cursorBrush = SolidColor(SnColors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            )
        }
        if (query.isNotEmpty()) {
            SnIconButton(SnIcons.Close, clearLabel, onClick = { onQueryChange("") })
        }
    }
}

/**
 * On/off switch. The visible track is 52 × 30 inside a 48 dp tall touch target, and the
 * state is announced with [onText] or [offText].
 */
@Composable
fun SnSwitch(
    checked: Boolean,
    label: String,
    onText: String,
    offText: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pill = RoundedCornerShape(SnRadius.radiusFull)
    val state = if (checked) onText else offText
    Box(
        modifier
            .height(SnSize.controlMd)
            .semantics {
                contentDescription = label
                stateDescription = state
            }.snClickable(pill, role = Role.Switch, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 52.dp, height = 30.dp)
                .background(if (checked) SnColors.accent else SnColors.borderStrong, pill)
                .padding(3.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(24.dp).background(SnColors.inverse, pill))
        }
    }
}

/** A row that opens a page, as in settings: icon, title, optional current value and a chevron. */
@Composable
fun SnListRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    val shape = RoundedCornerShape(SnRadius.radiusLg)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .snClickable(shape, onClick = onClick)
                .background(SnColors.surface, shape)
                .padding(horizontal = SnSpacing.space4, vertical = SnSpacing.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space4),
    ) {
        Image(icon, null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(SnColors.fgSecondary))
        BasicText(title, Modifier.weight(1f), style = SnTypography.bodyLg.copy(color = SnColors.fg))
        if (detail != null) {
            BasicText(detail, style = SnTypography.body.copy(color = SnColors.fgMuted))
        }
        Image(SnIcons.ChevronRight, null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(SnColors.fgMuted))
    }
}
