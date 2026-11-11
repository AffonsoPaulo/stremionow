// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChaptersTest {
    private val episode =
        listOf(
            Chapter("Prologue", 0.0),
            Chapter("Opening", 95.0),
            Chapter("Part A", 185.0),
            Chapter("Part B", 900.0),
            Chapter("Ending", 1320.0),
            Chapter("Preview", 1410.0),
        )

    @Test
    fun findsTheIntroAndCredits() {
        assertEquals(Segment(95.0, 185.0), Chapters.intro(episode, 1440.0))
        assertEquals(1320.0, Chapters.creditsStart(episode, 1440.0))
    }

    @Test
    fun matchesShortNamesAndPunctuation() {
        val chapters = listOf(Chapter("Cold open", 0.0), Chapter(" OP: ", 60.0), Chapter("Episode", 150.0))
        val intro = Chapters.intro(chapters, 1400.0)
        assertEquals(Segment(60.0, 150.0), intro)
        assertTrue(100.0 in intro!!)
    }

    @Test
    fun ignoresImplausibleOrMissingChapters() {
        // A chapter called "Intro" that lasts ten minutes is not an opening.
        assertNull(Chapters.intro(listOf(Chapter("Intro", 0.0), Chapter("Main", 600.0)), 3000.0))
        // "Credits" in the first half is not the end credits.
        assertNull(Chapters.creditsStart(listOf(Chapter("Credits", 10.0), Chapter("Story", 90.0)), 3000.0))
        assertNull(Chapters.intro(emptyList(), 1000.0))
        assertNull(Chapters.creditsStart(episode, null))
    }

    @Test
    fun parsesMpvChapterLists() {
        val chapters = Chapters.parse("""[{"title":"Opening","time":95.5},{"time":185},{"title":"Bad","time":-1}]""")
        assertEquals(listOf(Chapter("Opening", 95.5), Chapter("", 185.0)), chapters)
        assertEquals(emptyList<Chapter>(), Chapters.parse("not json"))
    }
}
