// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PageTransitionsTest {
    private fun move(
        from: PageKind,
        to: PageKind,
        back: Boolean = false,
        ownWindow: Boolean = false,
    ) = pageMove(from, to, back, ownWindow)

    @Test
    fun openingAPageFromATabOrAnotherPageOpensIt() {
        assertEquals(PageMove.Open, move(PageKind.Tab, PageKind.Page))
        assertEquals(PageMove.Open, move(PageKind.Page, PageKind.Page))
    }

    @Test
    fun goingBackToThePageUnderneathIsBack() {
        assertEquals(PageMove.Back, move(PageKind.Page, PageKind.Tab, back = true))
        assertEquals(PageMove.Back, move(PageKind.Page, PageKind.Page, back = true))
    }

    @Test
    fun switchingTabsCrossfadesEvenFromAPageInsideOne() {
        assertEquals(PageMove.Tab, move(PageKind.Tab, PageKind.Tab))
        assertEquals(PageMove.Tab, move(PageKind.Page, PageKind.Tab))
    }

    @Test
    fun backFromOneTabToTheTabUnderItIsSwitchingTabs() {
        assertEquals(PageMove.Tab, move(PageKind.Tab, PageKind.Tab, back = true))
    }

    @Test
    fun thePlayerFadesThroughTheBackgroundBothWays() {
        assertEquals(PageMove.Player, move(PageKind.Page, PageKind.Player))
        assertEquals(PageMove.Player, move(PageKind.Player, PageKind.Page, back = true))
        // The next episode replaces the player with another.
        assertEquals(PageMove.Player, move(PageKind.Player, PageKind.Player))
    }

    @Test
    fun aPlayerInItsOwnWindowCutsWithoutAnimating() {
        assertEquals(PageMove.Cut, move(PageKind.Page, PageKind.Player, ownWindow = true))
        assertEquals(PageMove.Cut, move(PageKind.Player, PageKind.Page, back = true, ownWindow = true))
        assertEquals(PageMove.Cut, move(PageKind.Player, PageKind.Player, ownWindow = true))
    }

    @Test
    fun theOwnWindowPlayerLeavesOtherMovesAlone() {
        assertEquals(PageMove.Open, move(PageKind.Tab, PageKind.Page, ownWindow = true))
        assertEquals(PageMove.Tab, move(PageKind.Tab, PageKind.Tab, ownWindow = true))
    }
}
