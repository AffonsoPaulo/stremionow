// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import androidx.compose.ui.input.key.Key
import io.github.affonsopaulo.stremionow.ui.screens.Panel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelKeysTest {
    @Test
    fun aFocusedPanelGetsSpaceEnterAndTheArrows() {
        val state = OverlayState()
        state.panel = Panel.Tracks
        state.panelFocused = true
        for (key in listOf(Key.Spacebar, Key.Enter, Key.DirectionUp, Key.DirectionLeft)) {
            assertTrue("$key", keyBelongsToPanel(key, state))
        }
        // The other shortcuts keep working with the focus in a panel.
        for (key in listOf(Key.F, Key.M, Key.N, Key.Escape, Key.K)) {
            assertFalse("$key", keyBelongsToPanel(key, state))
        }
    }

    @Test
    fun withoutTheFocusInAPanelTheKeysAreThePlayers() {
        val state = OverlayState()
        assertFalse(keyBelongsToPanel(Key.Spacebar, state))
        state.panel = Panel.Episodes
        assertFalse("open, but the focus is elsewhere", keyBelongsToPanel(Key.Spacebar, state))
        state.panelFocused = true
        state.panel = null
        assertFalse("closed with the focus in it", keyBelongsToPanel(Key.DirectionRight, state))
    }
}
