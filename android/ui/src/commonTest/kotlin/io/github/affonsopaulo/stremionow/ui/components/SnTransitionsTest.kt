// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import io.github.affonsopaulo.stremionow.core.MetaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnTransitionsTest {
    private val title = TitleArtKey("movie", "tt1")
    private val other = TitleArtKey("movie", "tt2")

    @Test
    fun theTitlePageClaimsTheArtOfTheCardThatOpenedIt() {
        val board = FlightBoard(enabled = true)
        board.launch(title, "poster.jpg")
        assertEquals("poster.jpg", board.claim(title))
    }

    @Test
    fun theArtIsClaimedOnce() {
        val board = FlightBoard(enabled = true)
        board.launch(title, "poster.jpg")
        board.claim(title)
        assertNull(board.claim(title))
    }

    @Test
    fun aPageOfAnotherTitleFindsNothingAndLeavesNothingBehind() {
        val board = FlightBoard(enabled = true)
        board.launch(title, "poster.jpg")
        assertNull(board.claim(other))
        assertNull(board.claim(title))
    }

    @Test
    fun aCardWithoutPictureClearsAnEarlierOne() {
        val board = FlightBoard(enabled = true)
        board.launch(title, "poster.jpg")
        board.launch(title, null)
        assertNull(board.claim(title))
    }

    @Test
    fun nothingFliesWithReducedMotion() {
        val board = FlightBoard(enabled = false)
        board.launch(title, "poster.jpg")
        assertNull(board.claim(title))
    }

    @Test
    fun aCardFliesTheBackdropOrElseThePoster() {
        val both = titleOrigin("row", item(poster = "p.jpg", background = "b.jpg"))
        assertEquals(TitleOrigin("row/movie/tt1", "movie", "tt1", "b.jpg"), both)
        assertEquals("p.jpg", titleOrigin("row", item(poster = "p.jpg", background = null)).art)
    }

    @Test
    fun aCardWithoutPosterDoesNotFly() {
        assertNull(titleOrigin("row", item(poster = null, background = "b.jpg")).art)
    }

    @Test
    fun theSameTitleInTwoRowsHasTwoSlots() {
        val card = item(poster = "p.jpg", background = null)
        assertEquals("popular/movie/tt1", titleOrigin("popular", card).slot)
        assertEquals("new/movie/tt1", titleOrigin("new", card).slot)
    }

    private fun item(
        poster: String?,
        background: String?,
    ) = MetaItem("tt1", "movie", "Title", poster, background, null, null, null, null, emptyList())
}
