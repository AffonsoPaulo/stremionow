// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import io.github.affonsopaulo.stremionow.core.TitleRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class DetailsActionsTest {
    private fun actions(
        onTrailer: (() -> Unit)? = {},
        onShare: (() -> Unit)? = {},
    ) = DetailActions(
        onTrailer = onTrailer,
        inLibrary = false,
        onToggleLibrary = {},
        watched = false,
        onWatched = {},
        rating = TitleRating.NONE,
        onRate = {},
        onShare = onShare,
    )

    @Test
    fun `every action shows in the official app's order`() {
        assertEquals(
            listOf(
                DetailAction.Trailer,
                DetailAction.MyList,
                DetailAction.Watched,
                DetailAction.Like,
                DetailAction.Love,
                DetailAction.Share,
            ),
            shownActions(actions()),
        )
    }

    @Test
    fun `missing trailer and share links leave their tiles out`() {
        assertEquals(
            listOf(DetailAction.MyList, DetailAction.Watched, DetailAction.Like, DetailAction.Love),
            shownActions(actions(onTrailer = null, onShare = null)),
        )
    }

    @Test
    fun `only a series has a trailer tile, since a movie's second button is the trailer`() {
        val trailer = {}
        assertSame(trailer, rowTrailer(isSeries = true, trailer = trailer))
        assertNull(rowTrailer(isSeries = false, trailer = trailer))
        assertNull(rowTrailer<() -> Unit>(isSeries = true, trailer = null))
    }

    @Test
    fun `choosing a rating sets it, and choosing it again clears it`() {
        assertEquals(TitleRating.LIKED, ratingAfter(TitleRating.NONE, TitleRating.LIKED))
        assertEquals(TitleRating.NONE, ratingAfter(TitleRating.LIKED, TitleRating.LIKED))
        assertEquals(TitleRating.LOVED, ratingAfter(TitleRating.LIKED, TitleRating.LOVED))
        assertEquals(TitleRating.LIKED, ratingAfter(TitleRating.LOVED, TitleRating.LIKED))
        assertEquals(TitleRating.NONE, ratingAfter(TitleRating.LOVED, TitleRating.LOVED))
    }
}
