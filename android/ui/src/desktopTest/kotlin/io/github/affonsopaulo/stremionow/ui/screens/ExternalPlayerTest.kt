// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import io.github.affonsopaulo.stremionow.core.AppSettings
import io.github.affonsopaulo.stremionow.core.PlayerType
import org.junit.Assert.assertFalse
import org.junit.Test

class ExternalPlayerTest {
    @Test
    fun theDesktopAlwaysPlaysInItsOwnPlayer() {
        assertFalse(defaultSettings().playsInOtherApp())
        assertFalse(defaultSettings().copy(playerType = PlayerType.EXTERNAL).playsInOtherApp())
        assertFalse((null as AppSettings?).playsInOtherApp())
    }
}
