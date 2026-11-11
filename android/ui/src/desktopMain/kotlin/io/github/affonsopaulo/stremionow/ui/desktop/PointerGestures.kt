// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isPrimaryPressed

/**
 * Reports a primary-button drag from the first press, with no touch slop, until the button is
 * released. [onMove] gets no offset: window gestures read the pointer's screen position, since
 * positions inside the window shift as the window itself moves. [onEnd] receives true when the
 * gesture was cancelled (the handler restarted) instead of ended by a release.
 */
internal suspend fun PointerInputScope.trackPrimaryDrag(
    onStart: () -> Unit,
    onMove: () -> Unit,
    onEnd: (cancelled: Boolean) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
        down.consume()
        onStart()
        var released = false
        try {
            while (!released) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                released = change == null || !change.pressed
                if (!released) {
                    change?.consume()
                    onMove()
                }
            }
        } finally {
            onEnd(!released)
        }
    }
}
