// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data

import io.github.affonsopaulo.stremionow.ui.data.WatchNextPlanner.Row
import io.github.affonsopaulo.stremionow.ui.data.WatchNextPlanner.Title
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchNextPlannerTest {
    @Test
    fun aFirstUpdateAddsEveryTitleInOrder() {
        val plan = WatchNextPlanner.plan(emptyList(), listOf(title("a"), title("b")), emptyMap())
        assertEquals(listOf("a", "b"), plan.insert)
        assertTrue(plan.delete.isEmpty() && plan.update.isEmpty() && plan.removed.isEmpty())
    }

    @Test
    fun shownProgramsAreUpdatedInPlaceAndOthersDeleted() {
        val rows = listOf(row(1, "a"), row(2, "gone"), row(3, "b"))
        val plan = WatchNextPlanner.plan(rows, listOf(title("a"), title("b"), title("c")), emptyMap())
        assertEquals(mapOf(1L to "a", 3L to "b"), plan.update)
        assertEquals(listOf(2L), plan.delete)
        assertEquals(listOf("c"), plan.insert)
    }

    @Test
    fun aTitleTheUserRemovedStaysOut() {
        val rows = listOf(row(1, "a", browsable = false, engagedAt = 100))
        val first = WatchNextPlanner.plan(rows, listOf(title("a", updatedAt = 100)), emptyMap())
        assertEquals(listOf(1L), first.delete)
        assertTrue(first.insert.isEmpty())
        assertEquals(mapOf("a" to 100L), first.removed)

        // The removed program is gone now; the app's own memory keeps the title out.
        val second = WatchNextPlanner.plan(emptyList(), listOf(title("a", updatedAt = 100)), first.removed)
        assertTrue(second.insert.isEmpty())
        assertEquals(first.removed, second.removed)
    }

    @Test
    fun watchingARemovedTitleAgainBringsItBack() {
        val plan = WatchNextPlanner.plan(emptyList(), listOf(title("a", updatedAt = 200)), mapOf("a" to 100L))
        assertEquals(listOf("a"), plan.insert)
        assertTrue(plan.removed.isEmpty())
    }

    @Test
    fun aRemovalIsForgottenOnceTheTitleLeavesContinueWatching() {
        val plan = WatchNextPlanner.plan(emptyList(), listOf(title("b")), mapOf("a" to 100L))
        assertEquals(emptyMap<String, Long>(), plan.removed)
    }

    @Test
    fun aRemovedProgramWithoutAnEngagementTimeUsesTheTitlesProgress() {
        val rows = listOf(row(1, "a", browsable = false, engagedAt = 0))
        val plan = WatchNextPlanner.plan(rows, listOf(title("a", updatedAt = 150)), emptyMap())
        assertEquals(mapOf("a" to 150L), plan.removed)
        assertTrue(plan.insert.isEmpty())
    }

    @Test
    fun titlesTheAppCannotOpenAreLeftOut() {
        val rows = listOf(row(1, "bad"))
        val plan = WatchNextPlanner.plan(rows, listOf(title("bad", opens = false), title("a")), emptyMap())
        assertEquals(listOf(1L), plan.delete)
        assertEquals(listOf("a"), plan.insert)
    }

    @Test
    fun duplicateProgramsKeepOne() {
        val rows = listOf(row(1, "a"), row(2, "a"), row(3, null))
        val plan = WatchNextPlanner.plan(rows, listOf(title("a")), emptyMap())
        assertEquals(mapOf(1L to "a"), plan.update)
        assertEquals(listOf(2L, 3L), plan.delete)
        assertTrue(plan.insert.isEmpty())
    }

    private fun title(
        id: String,
        updatedAt: Long = 100,
        opens: Boolean = true,
    ) = Title(id, updatedAt, opens)

    private fun row(
        rowId: Long,
        titleId: String?,
        browsable: Boolean = true,
        engagedAt: Long = 100,
    ) = Row(rowId, titleId, browsable, engagedAt)
}
