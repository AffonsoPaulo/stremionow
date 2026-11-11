// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Colors of the design system (design/tokens.json) that the player uses. The Android app
 * generates these from the tokens; the desktop app will share that generated theme once the
 * `ui` module is multiplatform.
 */
internal object Colors {
    val fg = Color(0xFFF4F5F8)
    val fgSecondary = Color(0xFFC7CBD6)
    val accent = Color(0xFF8B7CFF)
    val scrim = Color(0xB8050608)
    val overlayLight = Color(0x29FFFFFF)
    val progressTrack = Color(0x38FFFFFF)
}

/** Icons of the design system, drawn after Lucide (ISC license): 24 × 24, 2 px round strokes. */
internal object Icons {
    val ChevronLeft = stroke("chevron-left", "M15 18l-6 -6l6 -6")
    val Play = fill("play", "M7 4.5v15l13 -7.5z")
    val Pause = fill("pause", "M6 4.5h4v15h-4z", "M14 4.5h4v15h-4z")
    val Back10 = stroke("back-10", "M3 12a9 9 0 1 0 3 -6.7L3 8", "M3 3v5h5")
    val Forward10 = stroke("forward-10", "M21 12a9 9 0 1 1 -3 -6.7L21 8", "M21 3v5h-5")
    val Minus = stroke("minus", "M5 12h14")
    val Maximize =
        stroke(
            "maximize",
            "M8 3H5a2 2 0 0 0 -2 2v3",
            "M21 8V5a2 2 0 0 0 -2 -2h-3",
            "M3 16v3a2 2 0 0 0 2 2h3",
            "M16 21h3a2 2 0 0 0 2 -2v-3",
        )
    val Minimize =
        stroke(
            "minimize",
            "M8 3v3a2 2 0 0 1 -2 2H3",
            "M21 8h-3a2 2 0 0 1 -2 -2V3",
            "M3 16h3a2 2 0 0 1 2 2v3",
            "M16 21v-3a2 2 0 0 1 2 -2h3",
        )

    private fun stroke(
        name: String,
        vararg paths: String,
    ): ImageVector = build(name, paths, filled = false)

    private fun fill(
        name: String,
        vararg paths: String,
    ): ImageVector = build(name, paths, filled = true)

    private fun build(
        name: String,
        paths: Array<out String>,
        filled: Boolean,
    ): ImageVector =
        ImageVector
            .Builder(
                name = name,
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                for (path in paths) {
                    addPath(
                        pathData = addPathNodes(path),
                        fill = if (filled) SolidColor(Color.White) else null,
                        stroke = if (filled) null else SolidColor(Color.White),
                        strokeLineWidth = if (filled) 0f else 2f,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }.build()
}
