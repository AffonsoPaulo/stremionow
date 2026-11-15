// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.desktop

import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import kotlin.math.abs

/**
 * The app's icon: a white play mark and a dot in the accent color, "play, now", the way the
 * wordmark sets "Stremio" in white and "Now" in the accent, on a dark tile in the app's
 * background colors. The same marks are the Android launcher icon (sn_mark.xml).
 *
 * Each size is drawn on its own, four times larger and then halved twice, so even 16 px stays
 * smooth and nothing is scaled up from a small bitmap. The installers' copies in `icons/` come
 * from here too: `./gradlew :app:writeAppIcons`.
 */
internal object AppIcon {
    private val TILE_TOP = Color(0x1C, 0x1E, 0x2A)
    private val TILE_BOTTOM = Color(0x0E, 0x0F, 0x16)
    private val FG = Color(0xF4, 0xF5, 0xF8)
    private val ACCENT = Color(0x8B, 0x7C, 0xFF)

    /** Every size Windows uses for windows, the taskbar and Alt+Tab, up to 250% scale. */
    private val SIZES = listOf(16, 20, 24, 32, 40, 48, 64, 96, 128, 256)

    /** Drawn this many times larger, then halved down to the size. */
    private const val SUPERSAMPLE = 4

    // In fractions of the tile's side.
    private const val CORNER = 0.225
    private val PLAY = listOf(Point2D.Double(0.25, 0.285), Point2D.Double(0.25, 0.715), Point2D.Double(0.62, 0.5))
    private const val PLAY_ROUNDING = 0.06
    private val DOT = Point2D.Double(0.755, 0.5)
    private const val DOT_RADIUS = 0.08

    val images: List<BufferedImage> by lazy { SIZES.map(::render) }

    /** The icon at [size] pixels square. */
    fun render(size: Int): BufferedImage {
        var image = draw(size * SUPERSAMPLE)
        while (image.width > size) image = half(image)
        return BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).also { out ->
            out.createGraphics().apply {
                drawImage(image, 0, 0, null)
                dispose()
            }
        }
    }

    private fun draw(size: Int): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB_PRE)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            val s = size.toDouble()
            graphics.paint = GradientPaint(0f, 0f, TILE_TOP, 0f, size.toFloat(), TILE_BOTTOM)
            graphics.fill(RoundRectangle2D.Double(0.0, 0.0, s, s, s * CORNER * 2, s * CORNER * 2))

            // A triangle with round corners: a smaller one, grown back by a round-joined stroke.
            val rounding = s * PLAY_ROUNDING
            val play =
                Path2D.Double().apply {
                    inset(PLAY.map { Point2D.Double(it.x * s, it.y * s) }, rounding).forEachIndexed { index, point ->
                        if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                    }
                    closePath()
                }
            graphics.color = FG
            graphics.fill(play)
            graphics.stroke = BasicStroke((rounding * 2).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            graphics.draw(play)

            val radius = s * DOT_RADIUS
            graphics.color = ACCENT
            graphics.fill(Ellipse2D.Double(DOT.x * s - radius, DOT.y * s - radius, radius * 2, radius * 2))
        } finally {
            graphics.dispose()
        }
        return image
    }

    /** [triangle] shrunk toward its incenter so that its sides move in by [by]. */
    private fun inset(
        triangle: List<Point2D.Double>,
        by: Double,
    ): List<Point2D.Double> {
        val (a, b, c) = triangle
        val sideA = b.distance(c)
        val sideB = a.distance(c)
        val sideC = a.distance(b)
        val perimeter = sideA + sideB + sideC
        val centerX = (sideA * a.x + sideB * b.x + sideC * c.x) / perimeter
        val centerY = (sideA * a.y + sideB * b.y + sideC * c.y) / perimeter
        val area = abs((b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y)) / 2
        val inradius = area / (perimeter / 2)
        val scale = (inradius - by) / inradius
        return triangle.map { Point2D.Double(centerX + (it.x - centerX) * scale, centerY + (it.y - centerY) * scale) }
    }

    /** Half the size, each pixel the average of four: bilinear at exactly half. */
    private fun half(image: BufferedImage): BufferedImage {
        val out = BufferedImage(image.width / 2, image.height / 2, BufferedImage.TYPE_INT_ARGB_PRE)
        val graphics = out.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.drawImage(image, 0, 0, out.width, out.height, null)
        graphics.dispose()
        return out
    }
}
