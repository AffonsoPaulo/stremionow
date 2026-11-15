// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import io.github.affonsopaulo.stremionow.core.AppSettings

/**
 * How far the player moves per press, from Settings › Playback: [step] for the arrow keys, the
 * skip buttons and a remote's fast-forward and rewind keys, and [shortStep] for Shift and the
 * arrow keys on the desktop. Both in seconds.
 */
internal data class SeekSteps(
    val step: Int,
    val shortStep: Int,
) {
    /** The step of an arrow key, the shorter one with Shift held. */
    fun forArrow(shift: Boolean): Double = (if (shift) shortStep else step).toDouble()

    companion object {
        /** Stremio's: 10 seconds, and 3 with Shift. */
        val DEFAULT = SeekSteps(step = 10, shortStep = 3)

        fun from(settings: AppSettings?): SeekSteps {
            if (settings == null) return DEFAULT
            // The core keeps them to its choices; a zero would make the keys do nothing.
            return SeekSteps(
                step = settings.seekStepSeconds.toInt().takeIf { it > 0 } ?: DEFAULT.step,
                shortStep = settings.seekShortStepSeconds.toInt().takeIf { it > 0 } ?: DEFAULT.shortStep,
            )
        }
    }
}
