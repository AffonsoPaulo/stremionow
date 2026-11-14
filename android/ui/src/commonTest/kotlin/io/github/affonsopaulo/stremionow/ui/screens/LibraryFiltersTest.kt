// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import io.github.affonsopaulo.stremionow.core.LibraryItem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LibraryFiltersTest {
    private fun item(
        id: String,
        name: String = id,
        kind: String = "movie",
        addedAt: Long = 0,
        lastWatchedAt: Long? = null,
        timesWatched: UInt = 0u,
        watched: Boolean = timesWatched > 0u,
    ) = LibraryItem(
        id = id,
        kind = kind,
        name = name,
        poster = null,
        addedAt = addedAt,
        lastWatchedAt = lastWatchedAt,
        timesWatched = timesWatched,
        watched = watched,
        progress = null,
    )

    private val portuguese = nameCollator(Locale.forLanguageTag("pt-BR"))

    private fun ids(
        items: List<LibraryItem>,
        type: LibraryType = LibraryType.All,
        sort: LibrarySort,
    ) = arrangeLibrary(items, type, sort, portuguese).map { it.id }

    private val library =
        listOf(
            item("old", addedAt = 1, lastWatchedAt = 100, timesWatched = 3u),
            item("recent", kind = "series", addedAt = 2, lastWatchedAt = 300, timesWatched = 1u),
            item("started", kind = "series", addedAt = 3, lastWatchedAt = 200),
            item("fresh", addedAt = 5),
            item("unseen", kind = "series", addedAt = 4),
            item("marked", kind = "series", addedAt = 6, lastWatchedAt = 50, watched = true),
        )

    @Test
    fun `last watched puts the latest watch first and never watched by date added`() {
        assertEquals(
            listOf("recent", "started", "old", "marked", "fresh", "unseen"),
            ids(library, sort = LibrarySort.LastWatched),
        )
    }

    @Test
    fun `most watched counts the times watched to the end`() {
        assertEquals(
            listOf("old", "recent", "started", "marked", "fresh", "unseen"),
            ids(library, sort = LibrarySort.MostWatched),
        )
    }

    @Test
    fun `watched and not watched filter by Stremio's rule or a watched mark`() {
        assertEquals(listOf("recent", "old", "marked"), ids(library, sort = LibrarySort.Watched))
        assertEquals(listOf("started", "fresh", "unseen"), ids(library, sort = LibrarySort.NotWatched))
    }

    @Test
    fun `the type menu keeps movies or series`() {
        assertEquals(listOf("old", "fresh"), ids(library, LibraryType.Movies, LibrarySort.LastWatched))
        assertEquals(
            listOf("recent", "started", "marked", "unseen"),
            ids(library, LibraryType.Series, LibrarySort.LastWatched),
        )
        assertEquals(listOf("recent", "marked"), ids(library, LibraryType.Series, LibrarySort.Watched))
    }

    @Test
    fun `names sort by the language's collation, ignoring accents and case`() {
        val names =
            listOf(
                item("b", name = "bacurau"),
                item("e", name = "Élite"),
                item("a", name = "Amor"),
                item("o", name = "o auto da compadecida"),
                item("z", name = "Zelda"),
                item("c", name = "Cidade de Deus"),
            )
        assertEquals(listOf("a", "b", "c", "e", "o", "z"), ids(names, sort = LibrarySort.AToZ))
        assertEquals(listOf("z", "o", "e", "c", "b", "a"), ids(names, sort = LibrarySort.ZToA))
    }

    @Test
    fun `ties keep a stable order`() {
        val twins = listOf(item("b", name = "Same", addedAt = 1), item("a", name = "same", addedAt = 1))
        assertEquals(listOf("a", "b"), ids(twins, sort = LibrarySort.AToZ))
        assertEquals(listOf("a", "b"), ids(twins, sort = LibrarySort.ZToA))
        assertEquals(listOf("a", "b"), ids(twins, sort = LibrarySort.LastWatched))
    }

    @Test
    fun `filters can leave nothing to show`() {
        assertEquals(emptyList<String>(), ids(emptyList(), sort = LibrarySort.Watched))
        val watchedMovies = listOf(item("old", timesWatched = 1u))
        assertEquals(emptyList<String>(), ids(watchedMovies, LibraryType.Movies, LibrarySort.NotWatched))
        assertEquals(emptyList<String>(), ids(watchedMovies, LibraryType.Series, LibrarySort.LastWatched))
    }
}
