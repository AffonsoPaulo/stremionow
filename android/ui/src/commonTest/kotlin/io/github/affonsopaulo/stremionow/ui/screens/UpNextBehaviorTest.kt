// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import io.github.affonsopaulo.stremionow.core.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpNextBehaviorTest {
    @Test
    fun theDefaultsCountDownTenSeconds() {
        assertEquals(UpNextBehavior(countdownSeconds = 10, playsAtEnd = false), UpNextBehavior.DEFAULT)
        assertEquals(UpNextBehavior.DEFAULT, UpNextBehavior.from(defaultSettings()))
        assertEquals(UpNextBehavior.DEFAULT, UpNextBehavior.from(null as AppSettings?))
    }

    @Test
    fun theCountdownFollowsTheSetting() {
        assertEquals(
            UpNextBehavior(countdownSeconds = 35, playsAtEnd = false),
            UpNextBehavior.from(defaultSettings().copy(upNextCountdownSeconds = 35u)),
        )
    }

    @Test
    fun withoutACountdownTheNextEpisodePlaysAtTheEnd() {
        assertEquals(
            UpNextBehavior(countdownSeconds = null, playsAtEnd = true),
            UpNextBehavior.from(defaultSettings().copy(upNextCountdownSeconds = 0u)),
        )
    }

    @Test
    fun turnedOffTheCardOnlyOffersTheNextEpisode() {
        val off = UpNextBehavior(countdownSeconds = null, playsAtEnd = false)
        assertEquals(off, UpNextBehavior.from(defaultSettings().copy(autoplayNext = false)))
        assertEquals(
            off,
            UpNextBehavior.from(defaultSettings().copy(autoplayNext = false, upNextCountdownSeconds = 0u)),
        )
    }

    @Test
    fun theChoicesStartWithNoneAndStayInTheCoresRange() {
        assertEquals(0, UpNextCountdownChoices.first())
        assertTrue(UpNextCountdownChoices.drop(1).all { it in 5..90 })
        assertTrue(10 in UpNextCountdownChoices)
    }
}
