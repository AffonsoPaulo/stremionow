// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import io.github.affonsopaulo.stremionow.core.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class SeekStepsTest {
    @Test
    fun theDefaultsAreStremios() {
        assertEquals(SeekSteps(step = 10, shortStep = 3), SeekSteps.DEFAULT)
        assertEquals(SeekSteps.DEFAULT, SeekSteps.from(defaultSettings()))
        assertEquals(SeekSteps.DEFAULT, SeekSteps.from(null as AppSettings?))
    }

    @Test
    fun shiftTakesTheShorterStep() {
        val steps = SeekSteps.from(defaultSettings().copy(seekStepSeconds = 30u, seekShortStepSeconds = 5u))
        assertEquals(30.0, steps.forArrow(shift = false), 0.0)
        assertEquals(5.0, steps.forArrow(shift = true), 0.0)
    }

    @Test
    fun aZeroStepFallsBackToTheDefault() {
        val steps = SeekSteps.from(defaultSettings().copy(seekStepSeconds = 0u, seekShortStepSeconds = 0u))
        assertEquals(SeekSteps.DEFAULT, steps)
    }
}
