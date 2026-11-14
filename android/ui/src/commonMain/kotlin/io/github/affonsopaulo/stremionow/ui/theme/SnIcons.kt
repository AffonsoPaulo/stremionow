// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Icons of the design system, drawn after Lucide (ISC license): 24 × 24, 2 px strokes with
 * round caps. Tint them with `ColorFilter.tint`; the white base color is only a placeholder.
 */
object SnIcons {
    val Search = stroke("search", "M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0 -14z", "M20 20l-3.5 -3.5")
    val Play = fill("play", "M7 4.5v15l13 -7.5z")
    val Pause = fill("pause", "M6 4.5h4v15h-4z", "M14 4.5h4v15h-4z")
    val Plus = stroke("plus", "M12 5v14", "M5 12h14")
    val Check = stroke("check", "M5 12l5 5l9 -10")
    val Close = stroke("close", "M18 6L6 18", "M6 6l12 12")
    val ChevronUp = stroke("chevron-up", "M6 15l6 -6l6 6")
    val ChevronDown = stroke("chevron-down", "M6 9l6 6l6 -6")
    val ChevronLeft = stroke("chevron-left", "M15 18l-6 -6l6 -6")
    val ChevronRight = stroke("chevron-right", "M9 18l6 -6l-6 -6")
    val Info = stroke("info", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0 -18z", "M12 11v6", "M12 7.5v0.01")
    val Trash = stroke("trash", "M4 7h16", "M10 11v6", "M14 11v6", "M6 7l1 13h10l1 -13", "M9 7V4h6v3")
    val Layers = stroke("layers", "M12 3l9 5l-9 5l-9 -5z", "M3 13l9 5l9 -5", "M3 17.5l9 5l9 -5")
    val Episodes =
        stroke(
            "episodes",
            "M5 8h10a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-8a2 2 0 0 1 2 -2z",
            "M7 4h12a2 2 0 0 1 2 2v10",
        )
    val Next = fill("next", "M5 5v14l11 -7z", "M18 5h2v14h-2z")
    val Volume =
        stroke(
            "volume",
            "M11 5l-5 4h-4v6h4l5 4z",
            "M15.5 8.5a5 5 0 0 1 0 7",
            "M19 5a10 10 0 0 1 0 14",
        )
    val VolumeMuted = stroke("volume-muted", "M11 5l-5 4h-4v6h4l5 4z", "M22 9l-6 6", "M16 9l6 6")
    val Back10 = stroke("back-10", "M3 12a9 9 0 1 0 3 -6.7L3 8", "M3 3v5h5")
    val Forward10 = stroke("forward-10", "M21 12a9 9 0 1 1 -3 -6.7L21 8", "M21 3v5h-5")
    val Subtitles =
        stroke(
            "subtitles",
            "M21 15a2 2 0 0 1 -2 2H8l-5 4V5a2 2 0 0 1 2 -2h14a2 2 0 0 1 2 2z",
            "M7 9h4",
            "M14 9h3",
            "M7 13h7",
        )
    val Bolt = fill("bolt", "M13 2L4 14h7l-1 8l9 -12h-7z")
    val Peers =
        stroke(
            "peers",
            "M8 3a4 4 0 1 0 0 8a4 4 0 1 0 0 -8z",
            "M2 21a6 6 0 0 1 12 0",
            "M17 5a3 3 0 1 1 0 6",
            "M22 21a5 5 0 0 0 -5 -5",
        )
    val Settings =
        stroke("settings", "M4 6h10", "M18 6h2", "M4 12h4", "M12 12h8", "M4 18h12", "M16 4v4", "M10 10v4", "M18 16v4")

    /** A screen with bars above and below the picture. */
    val PictureFit = stroke("picture-fit", "M3 5h18v14h-18z", "M3 8.5h18", "M3 15.5h18")

    /** Corners pushed out to the edges. */
    val PictureFill =
        stroke(
            "picture-fill",
            "M8 3h-3a2 2 0 0 0 -2 2v3",
            "M21 8v-3a2 2 0 0 0 -2 -2h-3",
            "M3 16v3a2 2 0 0 0 2 2h3",
            "M16 21h3a2 2 0 0 0 2 -2v-3",
        )

    /** Arrows pulling sideways. */
    val PictureStretch = stroke("picture-stretch", "M18 8l4 4l-4 4", "M6 8l-4 4l4 4", "M2 12h20")
    val Gear =
        stroke(
            "gear",
            "M12.22 2h-0.44a2 2 0 0 0 -2 2v0.18a2 2 0 0 1 -1 1.73l-0.43 0.25a2 2 0 0 1 -2 0l-0.15 -0.08" +
                "a2 2 0 0 0 -2.73 0.73l-0.22 0.38a2 2 0 0 0 0.73 2.73l0.15 0.1a2 2 0 0 1 1 1.72v0.51" +
                "a2 2 0 0 1 -1 1.74l-0.15 0.09a2 2 0 0 0 -0.73 2.73l0.22 0.38a2 2 0 0 0 2.73 0.73l0.15 -0.08" +
                "a2 2 0 0 1 2 0l0.43 0.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h0.44a2 2 0 0 0 2 -2v-0.18" +
                "a2 2 0 0 1 1 -1.73l0.43 -0.25a2 2 0 0 1 2 0l0.15 0.08a2 2 0 0 0 2.73 -0.73l0.22 -0.39" +
                "a2 2 0 0 0 -0.73 -2.73l-0.15 -0.08a2 2 0 0 1 -1 -1.74v-0.5a2 2 0 0 1 1 -1.74l0.15 -0.09" +
                "a2 2 0 0 0 0.73 -2.73l-0.22 -0.38a2 2 0 0 0 -2.73 -0.73l-0.15 0.08a2 2 0 0 1 -2 0l-0.43 -0.25" +
                "a2 2 0 0 1 -1 -1.73V4a2 2 0 0 0 -2 -2z",
            "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0 -6z",
        )
    val Skip = fill("skip", "M5 5v14l9 -7z", "M14 5v14l7 -7z")
    val Home =
        stroke(
            "home",
            "M3 10a2 2 0 0 1 0.709 -1.528l7 -5.999a2 2 0 0 1 2.582 0l7 5.999A2 2 0 0 1 21 10v9a2 2 0 0 1 -2 2H5" +
                "a2 2 0 0 1 -2 -2z",
            "M15 21v-8a1 1 0 0 0 -1 -1h-4a1 1 0 0 0 -1 1v8",
        )
    val Tv =
        stroke(
            "tv",
            "M4 7h16a2 2 0 0 1 2 2v10a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-10a2 2 0 0 1 2 -2z",
            "M17 2l-5 5l-5 -5",
        )
    val Film =
        stroke(
            "film",
            "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2z",
            "M7 3v18",
            "M17 3v18",
            "M3 12h18",
            "M3 7.5h4",
            "M3 16.5h4",
            "M17 7.5h4",
            "M17 16.5h4",
        )
    val Monitor =
        stroke(
            "monitor",
            "M4 4h16a1 1 0 0 1 1 1v10a1 1 0 0 1 -1 1H4a1 1 0 0 1 -1 -1V5a1 1 0 0 1 1 -1z",
            "M8 20h8",
            "M12 16v4",
        )
    val Shield = stroke("shield", "M12 3l8 3v6c0 5 -3.5 8 -8 9c-4.5 -1 -8 -4 -8 -9V6z")
    val LogOut = stroke("log-out", "M9 21H5a2 2 0 0 1 -2 -2V5a2 2 0 0 1 2 -2h4", "M16 17l5 -5l-5 -5", "M21 12H9")
    val User = stroke("user", "M12 3a4 4 0 1 0 0 8a4 4 0 1 0 0 -8z", "M19 21v-2a4 4 0 0 0 -4 -4H9a4 4 0 0 0 -4 4v2")
    val Bookmark = stroke("bookmark", "M19 21l-7 -4l-7 4V5a2 2 0 0 1 2 -2h10a2 2 0 0 1 2 2z")
    val Minus = stroke("minus", "M5 12h14")
    val Share =
        stroke(
            "share",
            "M18 2a3 3 0 1 0 0 6a3 3 0 1 0 0 -6z",
            "M6 9a3 3 0 1 0 0 6a3 3 0 1 0 0 -6z",
            "M18 16a3 3 0 1 0 0 6a3 3 0 1 0 0 -6z",
            "M8.59 13.51l6.83 3.98",
            "M15.41 6.51l-6.82 3.98",
        )
    val ThumbsUp =
        stroke(
            "thumbs-up",
            "M7 10v12",
            "M15 5.88L14 10h5.83a2 2 0 0 1 1.92 2.56l-2.33 8A2 2 0 0 1 17.5 22H4a2 2 0 0 1 -2 -2v-8" +
                "a2 2 0 0 1 2 -2h2.76a2 2 0 0 0 1.79 -1.11L12 2a3.13 3.13 0 0 1 3 3.88z",
        )
    val ThumbsUpFilled =
        fill(
            "thumbs-up-filled",
            "M15 5.88L14 10h5.83a2 2 0 0 1 1.92 2.56l-2.33 8A2 2 0 0 1 17.5 22H8V10.6l4 -8.6a3.13 3.13 0 0 1 3 3.88z",
            "M2 12a2 2 0 0 1 2 -2h2v12H4a2 2 0 0 1 -2 -2z",
        )
    val Heart =
        stroke(
            "heart",
            "M19 14c1.49 -1.46 3 -3.21 3 -5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0 -3 0.5 -4.5 2c-1.5 -1.5 -2.74 -2 -4.5 -2" +
                "A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7z",
        )
    val HeartFilled =
        fill(
            "heart-filled",
            "M19 14c1.49 -1.46 3 -3.21 3 -5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0 -3 0.5 -4.5 2c-1.5 -1.5 -2.74 -2 -4.5 -2" +
                "A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7z",
        )
    val Eye =
        stroke(
            "eye",
            "M2.06 12.35a1 1 0 0 1 0 -0.7a10.75 10.75 0 0 1 19.88 0a1 1 0 0 1 0 0.7a10.75 10.75 0 0 1 -19.88 0",
            "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0 -6z",
        )
    val CheckCircle = stroke("check-circle", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0 -18z", "M8.5 12l2.5 2.5l4.5 -5")

    /** [CheckCircle] filled: the disc covers the outline's stroke, with the check cut out of it. */
    val CheckCircleFilled =
        fill(
            "check-circle-filled",
            "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0 -20z" +
                "M7.79 12.71L11.04 15.95L16.24 10.17L14.76 8.83L10.96 13.05L9.21 11.29z",
            evenOdd = true,
        )

    /** Seeders of a torrent: people sharing it. */
    val Seeds = stroke("seeds", "M12 19V5", "M5 12l7 -7l7 7")
    val ExternalLink =
        stroke(
            "external-link",
            "M15 3h6v6",
            "M10 14L21 3",
            "M18 13v6a2 2 0 0 1 -2 2H5a2 2 0 0 1 -2 -2V8a2 2 0 0 1 2 -2h6",
        )
    val FullScreen =
        stroke(
            "full-screen",
            "M8 3H5a2 2 0 0 0 -2 2v3",
            "M21 8V5a2 2 0 0 0 -2 -2h-3",
            "M3 16v3a2 2 0 0 0 2 2h3",
            "M16 21h3a2 2 0 0 0 2 -2v-3",
        )
    val ExitFullScreen =
        stroke(
            "exit-full-screen",
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
        evenOdd: Boolean = false,
    ): ImageVector = build(name, paths, filled = true, evenOdd = evenOdd)

    private fun build(
        name: String,
        paths: Array<out String>,
        filled: Boolean,
        // Holes, such as a check cut out of a disc, where a path's inner shapes are left empty.
        evenOdd: Boolean = false,
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
                        pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
                        fill = if (filled) SolidColor(Color.White) else null,
                        stroke = if (filled) null else SolidColor(Color.White),
                        strokeLineWidth = if (filled) 0f else 2f,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }.build()
}
