// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileEndsTest {
    @Test
    fun aFileThatEndsBeforeLoadingFailedToOpen() {
        val ends = FileEnds()
        ends.onLoad()
        ends.onStart()
        assertTrue(ends.isOpenFailure(loaded = false, failed = null))
        assertTrue(ends.isOpenFailure(loaded = false, failed = true))
        assertFalse("asked for (a stop, a replacement)", ends.isOpenFailure(loaded = false, failed = false))
        assertFalse("it played", ends.isOpenFailure(loaded = true, failed = null))
    }

    @Test
    fun theEndOfAStopIsNoFailure() {
        val ends = FileEnds()
        ends.onLoad()
        ends.onStart()
        ends.onStop()
        assertFalse(ends.isOpenFailure(loaded = false, failed = null))
    }

    @Test
    fun aStopWithNothingOpenDoesNotHideTheNextFilesFailure() {
        val ends = FileEnds()
        // Moving on while the previous video was still being prepared: mpv had nothing to stop,
        // so no end of file followed.
        ends.onStop()
        ends.onLoad()
        ends.onStart()
        assertTrue(ends.isOpenFailure(loaded = false, failed = null))
    }

    @Test
    fun theEndOfTheFileReplacedIsNotTheNewOnes() {
        val ends = FileEnds()
        ends.onLoad()
        ends.onStart()
        ends.onStop()
        // The next file is asked for before the stopped one's end arrives.
        ends.onLoad()
        assertFalse(ends.isOpenFailure(loaded = false, failed = null))
        ends.onStart()
        assertTrue(ends.isOpenFailure(loaded = false, failed = null))
    }
}
