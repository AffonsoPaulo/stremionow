// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInTest {
    @Test
    fun `an address and a password can be sent`() {
        assertTrue(canSubmitEmail("me@example.com", "secret", busy = false))
    }

    @Test
    fun `nothing is sent without an address, a password, or while sending`() {
        assertFalse(canSubmitEmail("me.example.com", "secret", busy = false))
        assertFalse(canSubmitEmail("me@example.com", "", busy = false))
        assertFalse(canSubmitEmail("", "", busy = false))
        assertFalse(canSubmitEmail("me@example.com", "secret", busy = true))
    }
}
