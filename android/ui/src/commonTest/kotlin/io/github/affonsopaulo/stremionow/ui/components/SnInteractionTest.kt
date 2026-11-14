// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnInteractionTest {
    private val android = SnFeedback()
    private val desktopMouse = SnFeedback(showFocus = false, mouseFirst = true)
    private val desktopKeyboard = SnFeedback(showFocus = true, mouseFirst = true)

    private fun state(
        feedback: SnFeedback,
        isTv: Boolean = false,
        enabled: Boolean = true,
        focused: Boolean = false,
        hovered: Boolean = false,
        pressed: Boolean = false,
    ) = snInteractionState(feedback, isTv, enabled, focused, hovered, pressed)

    @Test
    fun `a TV shows only the focus ring`() {
        val tv = state(android, isTv = true, focused = true, hovered = true, pressed = true)
        assertEquals(SnInteractionState(ring = true), tv)
        assertFalse(snShowsHandCursor(android, isTv = true, enabled = true))
    }

    @Test
    fun `a touch screen shows the pressed state`() {
        assertEquals(SnInteractionState(press = true), state(android, pressed = true))
    }

    @Test
    fun `a mouse on a phone or tablet hovers too`() {
        assertEquals(SnInteractionState(hover = true), state(android, hovered = true))
        assertTrue(snShowsHandCursor(android, isTv = false, enabled = true))
    }

    @Test
    fun `the desktop shows the ring only after keyboard input, and hover always`() {
        assertEquals(SnInteractionState(hover = true), state(desktopMouse, focused = true, hovered = true))
        assertEquals(
            SnInteractionState(ring = true, hover = true),
            state(desktopKeyboard, focused = true, hovered = true),
        )
        assertTrue(snShowsHandCursor(desktopMouse, isTv = false, enabled = true))
    }

    @Test
    fun `a disabled element keeps its ring but neither hovers nor presses`() {
        val disabled = state(desktopKeyboard, enabled = false, focused = true, hovered = true, pressed = true)
        assertEquals(SnInteractionState(ring = true), disabled)
        assertFalse(snShowsHandCursor(desktopKeyboard, isTv = false, enabled = false))
    }

    @Test
    fun `a filled element lightens on hover and darkens when pressed`() {
        val base = SnColors.surfaceMuted
        val hover = snStateColor(base, SnInteractionState(hover = true))
        val press = snStateColor(base, SnInteractionState(hover = true, press = true))
        assertTrue(hover.luminance() > base.luminance())
        assertTrue(press.luminance() < base.luminance())
        assertEquals(1f, hover.alpha)
        assertEquals(base, snStateColor(base, SnInteractionState(ring = true)))
    }

    @Test
    fun `a white fill keeps its look on hover`() {
        assertEquals(SnColors.inverse, snStateColor(SnColors.inverse, SnInteractionState(hover = true)))
        assertTrue(
            snStateColor(SnColors.inverse, SnInteractionState(press = true)).luminance() <
                SnColors.inverse.luminance(),
        )
    }

    @Test
    fun `a transparent element gets a translucent circle, darker when pressed, also by touch`() {
        val page = SnColors.bg
        val hover = snStateColor(Color.Transparent, SnInteractionState(hover = true))
        val press = snStateColor(Color.Transparent, SnInteractionState(hover = true, press = true))
        val touch = snStateColor(Color.Transparent, SnInteractionState(press = true))
        assertTrue(hover.alpha in 0.01f..0.99f)
        val hoverOnPage = hover.compositeOver(page).luminance()
        val pressOnPage = press.compositeOver(page).luminance()
        assertTrue(hoverOnPage > page.luminance())
        assertTrue(pressOnPage < hoverOnPage)
        // Still visible against the page, so a tap on an icon button shows.
        assertTrue(pressOnPage > page.luminance())
        assertEquals(press, touch)
    }

    @Test
    fun `the hover border gives way to the focus ring`() {
        val base = SnColors.border
        val hover = snStateBorderColor(base, SnInteractionState(hover = true))
        assertTrue(hover.luminance() > base.luminance())
        assertEquals(base, snStateBorderColor(base, SnInteractionState(ring = true, hover = true)))
        assertEquals(base, snStateBorderColor(base, SnInteractionState()))
    }

    @Test
    fun `cards grow and lift under the pointer and settle back when pressed`() {
        val hover = snCardMotion(SnInteractionState(hover = true), true, true, reducedMotion = false)
        assertEquals(SnCardMotion(grow = true, lift = true), hover)
        val press = snCardMotion(SnInteractionState(hover = true, press = true), true, true, reducedMotion = false)
        assertEquals(SnCardMotion(grow = false, lift = false), press)
    }

    @Test
    fun `with reduced motion the pointer moves nothing`() {
        val hover = snCardMotion(SnInteractionState(hover = true), true, true, reducedMotion = true)
        assertEquals(SnCardMotion(grow = false, lift = false), hover)
    }

    @Test
    fun `focus still grows a card as on TV, without a shadow`() {
        val focus = snCardMotion(SnInteractionState(ring = true), true, true, reducedMotion = true)
        assertEquals(SnCardMotion(grow = true, lift = false), focus)
        // A wide card that only lifts under the pointer does not grow on focus.
        assertEquals(
            SnCardMotion(grow = false, lift = false),
            snCardMotion(SnInteractionState(ring = true), false, true, reducedMotion = false),
        )
    }

    @Test
    fun `wide cards grow by a few pixels at most`() {
        assertEquals(GROW_FRACTION, snGrowFraction(160f, 12f, capped = false))
        assertEquals(GROW_FRACTION, snGrowFraction(200f, 12f, capped = true))
        assertEquals(12f / 800f, snGrowFraction(800f, 12f, capped = true), 1e-6f)
        assertEquals(0f, snGrowFraction(0f, 12f, capped = true))
    }
}
