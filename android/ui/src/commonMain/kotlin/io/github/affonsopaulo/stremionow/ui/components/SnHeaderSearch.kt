// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.platform.rememberReducedMotion
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSize
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography
import kotlin.math.roundToInt

/** Longest query the header field takes, so a pasted wall of text cannot flood every addon. */
const val MAX_SEARCH_QUERY_LENGTH = 100

/**
 * The header's search field on large pointer screens: its text, and whether the user opened
 * it. The app shows it expanded while [open] or while the Search page is showing.
 */
@Stable
class HeaderSearchState internal constructor(
    initialQuery: String,
) {
    /** What the user typed; it drives the Search page. */
    var query by mutableStateOf(initialQuery)

    /** Opened from its icon or a shortcut, and not dismissed since. */
    var open by mutableStateOf(false)

    /** Whether the field has keyboard focus. */
    var focused by mutableStateOf(false)
        internal set

    /** Set to move focus into the field once it shows; the field clears it. */
    internal var focusPending by mutableStateOf(false)

    /** Opens the field and moves focus into it, with its text selected. */
    fun focus() {
        open = true
        focusPending = true
    }

    companion object {
        /** Keeps the query across configuration changes; the rest starts closed. */
        val Saver: Saver<HeaderSearchState, String> = Saver({ it.query }, { HeaderSearchState(it) })
    }
}

@Composable
fun rememberHeaderSearchState(): HeaderSearchState =
    rememberSaveable(saver = HeaderSearchState.Saver) { HeaderSearchState("") }

/** Width the field grows to when there is room, and the least it keeps when there is not. */
internal val HeaderSearchWidth = 320.dp
internal val HeaderSearchMinWidth = 220.dp

/**
 * The search icon of the large header, which expands into a field in place. The field grows to
 * [HeaderSearchWidth] or what its slot allows, whichever is less, and shrinks back to the icon.
 *
 * [onQueryChange] runs on every edit; [onClear] when the clear button is used and [onDismiss]
 * on Esc. Both collapse the field.
 */
@Composable
fun SnHeaderSearch(
    state: HeaderSearchState,
    expanded: Boolean,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = rememberReducedMotion()
    val fraction by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(if (reducedMotion) 0 else EXPAND_MS),
        label = "header-search",
    )
    val label = stringResource(Res.string.nav_search)
    if (!expanded && fraction == 0f) {
        SnIconButton(
            icon = SnIcons.Search,
            label = label,
            onClick = state::focus,
            modifier = modifier,
            tint = SnColors.fgSecondary,
        )
        return
    }
    val pill = RoundedCornerShape(SnRadius.radiusFull)
    val interaction = remember { MutableInteractionSource() }
    val ringShows = state.focused && LocalSnFeedback.current.showFocus
    Row(
        modifier =
            modifier
                // From the icon's size to the full width, within what the header allows.
                .layout { measurable, constraints ->
                    val icon = SnSize.controlMd.roundToPx()
                    val full = HeaderSearchWidth.roundToPx().coerceAtMost(constraints.maxWidth)
                    val width = lerp(icon, full.coerceAtLeast(icon), fraction)
                    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }.height(SnSize.controlMd)
                .snFocusRingFor(interaction, pill)
                .background(SnColors.surfaceInput, pill)
                // One sign of focus at a time: the ring for keyboard users, the accent otherwise.
                .border(1.dp, if (state.focused && !ringShows) SnColors.accent else SnColors.border, pill)
                .clip(pill)
                .pointerHoverIcon(PointerIcon.Text),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(SnSize.controlMd), contentAlignment = Alignment.Center) {
            Image(SnIcons.Search, null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(SnColors.fgSecondary))
        }
        Box(Modifier.weight(1f).alpha(fraction), contentAlignment = Alignment.CenterStart) {
            HeaderSearchField(state, label, interaction, onQueryChange, onDismiss)
        }
        if (state.query.isNotEmpty()) {
            SnIconButton(
                icon = SnIcons.Close,
                label = stringResource(Res.string.search_clear),
                onClick = onClear,
                modifier = Modifier.alpha(fraction),
                tint = SnColors.fgSecondary,
            )
        }
    }
}

@Composable
private fun HeaderSearchField(
    state: HeaderSearchState,
    label: String,
    interaction: MutableInteractionSource,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = remember { FocusRequester() }
    // The field keeps its own selection; the text follows the state both ways.
    var value by remember { mutableStateOf(TextFieldValue(state.query, TextRange(state.query.length))) }
    if (value.text != state.query) value = TextFieldValue(state.query, TextRange(state.query.length))
    LaunchedEffect(state.focusPending) {
        if (!state.focusPending) return@LaunchedEffect
        state.focusPending = false
        // Typing replaces what was there, as in a browser's address bar.
        value = value.copy(selection = TextRange(0, value.text.length))
        runCatching { requester.requestFocus() }
    }
    if (value.text.isEmpty()) {
        BasicText(
            stringResource(Res.string.search_field_label),
            style = SnTypography.body.copy(color = SnColors.fgMuted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    BasicTextField(
        value = value,
        onValueChange = { edited ->
            val text = edited.text.take(MAX_SEARCH_QUERY_LENGTH)
            value = if (text == edited.text) edited else TextFieldValue(text, TextRange(text.length))
            if (text != state.query) onQueryChange(text)
        },
        modifier =
            Modifier
                .fillMaxWidth()
                .focusRequester(requester)
                .trackTextInputFocus()
                .onFocusChanged { state.focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    // Esc closes the search rather than going back a page.
                    if (event.key != Key.Escape) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown) onDismiss()
                    true
                }.dpadExits()
                .semantics { contentDescription = label },
        textStyle = SnTypography.body.copy(color = SnColors.fg),
        singleLine = true,
        cursorBrush = SolidColor(SnColors.accent),
        interactionSource = interaction,
        // Search runs as the user types; the keyboard's key only reveals the results.
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
    )
}

private const val EXPAND_MS = 180

/**
 * Keyboard shortcuts that reach for the header's search from anywhere in the window: Ctrl+K
 * (Cmd+K on macOS), and "/" while no text field has focus, as on the web. The app sets
 * [onSearch] while the header has a search field; until then nothing is consumed.
 *
 * Feed it the window's key events before the content sees them. Where no host does (Android),
 * the app installs it at its root, which sees keys while anything in the app has focus.
 */
@Stable
class AppShortcuts {
    internal var onSearch: (() -> Unit)? = null

    fun onPreviewKeyEvent(event: KeyEvent): Boolean {
        val search = onSearch ?: return false
        if (event.type != KeyEventType.KeyDown || OpenDialogs.count > 0) return false
        val command =
            if (IsMac) {
                event.isMetaPressed && !event.isCtrlPressed
            } else {
                event.isCtrlPressed &&
                    !event.isMetaPressed
            }
        val triggered =
            when {
                event.key == Key.K && command && !event.isAltPressed && !event.isShiftPressed -> {
                    true
                }

                // By the character typed, since "/" sits on different keys across layouts.
                // AltGr (Ctrl and Alt together on Windows) types it on some layouts.
                (event.utf16CodePoint == '/'.code || event.key == Key.NumPadDivide) &&
                    !event.isMetaPressed && event.isCtrlPressed == event.isAltPressed -> {
                    !TextInputFocus.active
                }

                else -> {
                    false
                }
            }
        if (triggered) search()
        return triggered
    }

    private companion object {
        val IsMac = System.getProperty("os.name").orEmpty().startsWith("Mac")
    }
}

/** How many of the app's dialogs are showing; shortcuts stay off while any is. */
internal object OpenDialogs {
    var count = 0
}

/** Whether a text field of the app has focus, so typed shortcuts leave it alone. */
internal object TextInputFocus {
    private var owner: Any? = null

    val active: Boolean get() = owner != null

    fun gained(field: Any) {
        owner = field
    }

    fun lost(field: Any) {
        if (owner === field) owner = null
    }
}

/** Reports this text field's focus to [TextInputFocus]. */
@Composable
internal fun Modifier.trackTextInputFocus(): Modifier {
    val token = remember { Any() }
    DisposableEffect(token) { onDispose { TextInputFocus.lost(token) } }
    return onFocusChanged { if (it.isFocused) TextInputFocus.gained(token) else TextInputFocus.lost(token) }
}
