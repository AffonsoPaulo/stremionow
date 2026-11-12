// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.affonsopaulo.stremionow.ui.theme.LocalIsTv
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
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier =
            modifier
                .padding(horizontal = SnLayout.gutter, vertical = SnSpacing.space2)
                .fillMaxWidth()
                .height(64.dp)
                .snFocusRingFor(interaction, shape)
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
                        .trackTextInputFocus()
                        .dpadExits()
                        .semantics { contentDescription = label },
                textStyle = SnTypography.sectionTitle.copy(color = SnColors.fg),
                singleLine = true,
                cursorBrush = SolidColor(SnColors.accent),
                interactionSource = interaction,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            )
        }
        if (query.isNotEmpty()) {
            SnIconButton(SnIcons.Close, clearLabel, onClick = { onQueryChange("") })
        }
    }
}

/** Edits remembered while their echo is on its way back (see [SnTextField]). */
private const val MAX_UNANSWERED_EDITS = 64

/**
 * Single-line text field of the design system: an input box with an optional leading icon and
 * trailing content, and the focus ring around the box while the field has focus.
 *
 * On TV, focus passing through does not raise the keyboard; OK does, as on the system's own
 * fields. The remote's arrows leave the field: up and down always, left and right at either
 * end of the text, so a D-pad user is never trapped in it.
 */
@Composable
fun SnTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    isError: Boolean = false,
    leadingIcon: ImageVector? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    secret: Boolean = false,
    focusRequester: FocusRequester? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(SnRadius.radiusMd)
    val isTv = LocalIsTv.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val interaction = remember { MutableInteractionSource() }
    // The field keeps its own state (text and cursor; the arrows need the cursor to tell when
    // they reach an end) and stays in step with [value] both ways. [value] comes back a frame
    // or more after each edit, so edits still on their way back are remembered: their echoes
    // are not mistaken for changes from outside, which would drop what was typed meanwhile.
    val state = rememberTextFieldState(value)
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val sent = remember { ArrayDeque<String>() }
    LaunchedEffect(value) {
        val echo = sent.indexOf(value)
        if (echo >= 0) {
            repeat(echo + 1) { sent.removeFirst() }
        } else {
            sent.clear()
            if (state.text.toString() != value) state.setTextAndPlaceCursorAtEnd(value)
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { text ->
            if (text != currentValue) {
                if (sent.size >= MAX_UNANSWERED_EDITS) sent.removeFirst()
                sent.addLast(text)
                currentOnValueChange(text)
            }
        }
    }
    // On TV the keyboard opens only on OK. The field cannot raise it once focused, so OK lets
    // the keyboard open on focus and focuses the field again; leaving the field resets that.
    val requester = focusRequester ?: remember { FocusRequester() }
    var keyboardOnFocus by remember { mutableStateOf(!isTv) }
    var reopen by remember { mutableIntStateOf(0) }
    val refocusing = remember { booleanArrayOf(false) }
    LaunchedEffect(reopen) {
        if (reopen == 0) return@LaunchedEffect
        refocusing[0] = true
        focusManager.clearFocus(force = true)
        withFrameNanos { }
        runCatching { requester.requestFocus() }
        refocusing[0] = false
    }
    Row(
        modifier =
            modifier
                .heightIn(min = SnSize.controlLg)
                .snFocusRingFor(interaction, shape)
                .background(SnColors.surfaceInput, shape)
                .border(1.dp, if (isError) SnColors.danger else SnColors.border, shape)
                .padding(start = if (leadingIcon != null) SnSpacing.space3 else SnSpacing.space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space2),
    ) {
        if (leadingIcon != null) {
            Image(leadingIcon, null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(SnColors.fgSecondary))
        }
        Box(
            Modifier
                .weight(1f)
                .padding(end = if (trailing == null) SnSpacing.space4 else 0.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty() && hint != null) {
                BasicText(
                    hint,
                    style = SnTypography.body.copy(color = SnColors.fgMuted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val fieldModifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(requester)
                    .trackTextInputFocus()
                    .onFocusChanged { if (!it.isFocused && isTv && !refocusing[0]) keyboardOnFocus = false }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val caret = state.selection
                        when (event.key) {
                            Key.DirectionUp -> {
                                focusManager.moveFocus(FocusDirection.Up)
                            }

                            Key.DirectionDown -> {
                                focusManager.moveFocus(FocusDirection.Down)
                            }

                            Key.DirectionLeft -> {
                                isTv && caret.collapsed && caret.start == 0 &&
                                    focusManager.moveFocus(FocusDirection.Left)
                            }

                            Key.DirectionRight -> {
                                isTv && caret.collapsed && caret.end == state.text.length &&
                                    focusManager.moveFocus(FocusDirection.Right)
                            }

                            Key.DirectionCenter -> {
                                if (isTv && !keyboardOnFocus) {
                                    keyboardOnFocus = true
                                    reopen++
                                }
                                isTv
                            }

                            else -> {
                                false
                            }
                        }
                    }.semantics { contentDescription = label }
            val keyboardOptions =
                KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = imeAction,
                    autoCorrectEnabled = false,
                    showKeyboardOnFocus = keyboardOnFocus,
                )
            // Next keeps its default, moving to the following field.
            val onKeyboardAction =
                KeyboardActionHandler { performDefault ->
                    if (imeAction == ImeAction.Next) performDefault() else onImeAction()
                }
            val textStyle = SnTypography.body.copy(color = SnColors.fg)
            val cursor = SolidColor(SnColors.accent)
            if (secret) {
                BasicSecureTextField(
                    state = state,
                    modifier = fieldModifier,
                    textStyle = textStyle,
                    keyboardOptions = keyboardOptions,
                    onKeyboardAction = onKeyboardAction,
                    interactionSource = interaction,
                    cursorBrush = cursor,
                )
            } else {
                BasicTextField(
                    state = state,
                    modifier = fieldModifier,
                    textStyle = textStyle,
                    keyboardOptions = keyboardOptions,
                    onKeyboardAction = onKeyboardAction,
                    lineLimits = TextFieldLineLimits.SingleLine,
                    interactionSource = interaction,
                    cursorBrush = cursor,
                )
            }
        }
        trailing?.invoke()
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
