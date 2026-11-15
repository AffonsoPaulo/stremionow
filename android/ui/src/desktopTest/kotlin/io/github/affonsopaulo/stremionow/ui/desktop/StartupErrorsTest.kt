// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupErrorsTest {
    @Test
    fun longTextIsWrappedAtSpaces() {
        val text = "Check that this folder exists, that it can be written to and that the disk has free space."
        val lines = StartupErrors.wrap(text)
        assertTrue(lines.size > 1)
        assertTrue(lines.all { it.length <= 64 })
        assertEquals(text, lines.joinToString(" "))
    }

    @Test
    fun aWordLongerThanALineStaysWhole() {
        val word = "x".repeat(100)
        assertEquals(listOf("a", word, "b"), StartupErrors.wrap("a $word b"))
    }
}
