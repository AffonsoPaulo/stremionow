// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.player

import java.util.Locale

/**
 * How subtitles look, from Settings › Playback: their size and place, the colours of the text,
 * of the box behind it and of its outline, and whether styled subtitles (ASS) keep the file's
 * own look. Colours are ARGB (`0xAARRGGBB`); a fully transparent one draws nothing.
 */
data class SubtitleStyle(
    /** Size in percent of the normal size, within [SIZE_RANGE] on [SIZE_STEP] steps. */
    val sizePercent: Int = 100,
    /** Place in percent of the picture's height from its top, within [POSITION_RANGE]: 100 is the bottom. */
    val position: Int = 100,
    val textColor: Int = WHITE,
    val backgroundColor: Int = TRANSPARENT,
    val outlineColor: Int = BLACK,
    /** Styled subtitles keep the file's own fonts, colours and places; the size still applies. */
    val useFileStyling: Boolean = false,
) {
    /** The size as mpv's `sub-scale`, 1 being the normal size. */
    val scale: Double get() = sizePercent / 100.0

    /**
     * The mpv options for this style, each with the names to try, newest first: mpv 0.39
     * renamed `sub-border-*` to `sub-outline-*`, and `sub-border-style` only exists from 0.38.
     * Older versions already draw `sub-back-color` as a box behind the text, so they need no
     * border style; each option is set under the first of its names that mpv knows.
     */
    fun mpvOptions(): List<MpvOption> =
        listOf(
            MpvOption("sub-scale", String.format(Locale.ROOT, "%.2f", scale)),
            MpvOption("sub-pos", position.coerceIn(POSITION_RANGE).toString()),
            MpvOption("sub-color", mpvColor(textColor)),
            MpvOption("sub-back-color", mpvColor(backgroundColor)),
            MpvOption(listOf("sub-outline-color", "sub-border-color"), mpvColor(outlineColor)),
            // The box behind the text, in mpv's own style rather than the outline's shadow colour.
            MpvOption("sub-border-style", if (alpha(backgroundColor) == 0) "outline-and-shadow" else "background-box"),
            // "force" applies the colours and place above to styled subtitles too; "scale", mpv's
            // default, keeps their look and only applies the size.
            MpvOption("sub-ass-override", if (useFileStyling) "scale" else "force"),
        )

    /** An mpv option under the first of [names] that this mpv knows. */
    data class MpvOption(
        val names: List<String>,
        val value: String,
    ) {
        constructor(name: String, value: String) : this(listOf(name), value)
    }

    companion object {
        /** The same as the player's size buttons. */
        val SIZE_RANGE = 50..200
        const val SIZE_STEP = 10

        /** From a third of the way down the picture to the bottom, as the player's buttons go. */
        val POSITION_RANGE = 30..100
        const val POSITION_STEP = 5

        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val TRANSPARENT = 0

        /** mpv's colour syntax, `#AARRGGBB`. */
        fun mpvColor(argb: Int): String = String.format(Locale.ROOT, "#%08X", argb)

        private fun alpha(argb: Int): Int = (argb ushr 24) and 0xFF
    }
}
