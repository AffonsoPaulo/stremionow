// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class BackStepTest {
    @Test
    fun fullScreenGoesFirstByDefault() {
        assertEquals(BackStep.LeaveFullScreen, backStep(askingHdr = false, fullScreen = true, panelOpen = true))
        assertEquals(BackStep.ClosePanel, backStep(askingHdr = false, fullScreen = false, panelOpen = true))
        assertEquals(BackStep.Leave, backStep(askingHdr = false, fullScreen = false, panelOpen = false))
        assertEquals(BackStep.DismissHdr, backStep(askingHdr = true, fullScreen = true, panelOpen = true))
    }

    @Test
    fun withoutEscLeavingFullScreenItStepsBackAsOutOfIt() {
        assertEquals(
            BackStep.ClosePanel,
            backStep(askingHdr = false, fullScreen = true, panelOpen = true, leavesFullScreen = false),
        )
        assertEquals(
            BackStep.Leave,
            backStep(askingHdr = false, fullScreen = true, panelOpen = false, leavesFullScreen = false),
        )
        assertEquals(
            BackStep.DismissHdr,
            backStep(askingHdr = true, fullScreen = true, panelOpen = false, leavesFullScreen = false),
        )
    }
}
