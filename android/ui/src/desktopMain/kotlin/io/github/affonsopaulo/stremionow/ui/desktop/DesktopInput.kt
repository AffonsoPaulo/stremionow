// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import io.github.affonsopaulo.stremionow.ui.components.LocalSnFeedback
import io.github.affonsopaulo.stremionow.ui.components.SnFeedback

/**
 * Whether the user is driving a window with the keyboard or the mouse. Focus rings show from
 * the first key press until the next click, as with the web's `:focus-visible`; hover feedback
 * is always on. Feed it the window's key events with [onPreviewKeyEvent] and wrap the window's
 * content in [ProvideDesktopInput].
 */
@Stable
class DesktopInput {
    var isKeyboard by mutableStateOf(false)
        private set

    /** Pass every key event of the window here; it never consumes them. */
    fun onPreviewKeyEvent(event: KeyEvent): Boolean {
        if (event.type == KeyEventType.KeyDown) isKeyboard = true
        return false
    }

    internal fun onPointerPress() {
        isKeyboard = false
    }
}

/**
 * Provides the desktop's [SnFeedback] for [content], following [input], and makes the mouse's
 * back button (the thumb button, `XButton1` on Windows) go back: through [onBack] when given,
 * otherwise as a back event to the window's navigation, the same one Esc sends.
 */
@Composable
fun ProvideDesktopInput(
    input: DesktopInput,
    onBack: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val navigationBack = rememberNavigationBack()
    val currentOnBack by rememberUpdatedState(onBack ?: navigationBack)
    Box(
        Modifier.pointerInput(input) {
            // Sees presses before the content handles them, and consumes none but the back button.
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type != PointerEventType.Press) continue
                    if (event.buttons.isBackPressed) {
                        event.changes.forEach { it.consume() }
                        currentOnBack()
                    } else {
                        input.onPointerPress()
                    }
                }
            }
        },
    ) {
        CompositionLocalProvider(
            LocalSnFeedback provides SnFeedback(showFocus = input.isKeyboard, mouseFirst = true),
            content = content,
        )
    }
}

/** Sends a back event to the navigation of the current window, as Esc does. */
@Composable
private fun rememberNavigationBack(): () -> Unit {
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    val back = remember { DirectNavigationEventInput() }
    DisposableEffect(dispatcher) {
        dispatcher?.addInput(back)
        onDispose { dispatcher?.removeInput(back) }
    }
    return back::backCompleted
}
