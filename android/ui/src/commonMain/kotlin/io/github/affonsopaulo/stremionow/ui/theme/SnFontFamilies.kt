// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.theme

import androidx.compose.ui.text.font.FontFamily

/**
 * Font families of the design system, bundled as variable fonts so the app never depends on a
 * network font provider. Each weight is an instance of the same file, selected through the
 * `wght` axis; without the explicit variation the file's default instance would be used. The
 * files are Android font resources, which the desktop reads from its classpath.
 */
expect object SnFontFamilies {
    /** Outfit, for title logos and page titles. */
    val display: FontFamily

    /** Inter, for everything else. */
    val text: FontFamily
}
