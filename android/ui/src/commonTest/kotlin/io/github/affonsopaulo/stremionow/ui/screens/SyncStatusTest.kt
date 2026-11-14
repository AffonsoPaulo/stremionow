// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class SyncStatusTest {
    private val utc = TimeZone.getTimeZone("UTC")

    /** 2026-11-14 15:30 UTC. */
    private val now = 1_794_670_200_000L

    @Test
    fun aMinuteAgoIsJustNow() {
        assertEquals(SyncStatus.JustNow, syncStatus(now - 59_000, now, Locale.US, utc))
        assertEquals(SyncStatus.Never, syncStatus(null, now, Locale.US, utc))
        assertEquals(SyncStatus.Never, syncStatus(0, now, Locale.US, utc))
    }

    @Test
    fun todayShowsTheTimeInTheRegionsFormat() {
        val twoHoursAgo = now - 2 * 3_600_000
        val brazil = syncStatus(twoHoursAgo, now, Locale.forLanguageTag("pt-BR"), utc) as SyncStatus.At
        assertEquals("13:30", brazil.text)
        val us = syncStatus(twoHoursAgo, now, Locale.US, utc) as SyncStatus.At
        assertTrue(us.text, us.text.startsWith("1:30"))
    }

    @Test
    fun earlierDaysShowTheDateToo() {
        val lastWeek = now - 7 * 86_400_000L
        val brazil = syncStatus(lastWeek, now, Locale.forLanguageTag("pt-BR"), utc) as SyncStatus.At
        assertTrue(brazil.text, brazil.text.startsWith("07/11/2026"))
    }
}
