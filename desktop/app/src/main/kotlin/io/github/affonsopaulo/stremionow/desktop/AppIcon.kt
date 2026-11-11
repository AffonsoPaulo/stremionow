// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.desktop

import java.awt.Color
import java.awt.Image
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage

/**
 * Window and taskbar icon until the brand assets exist: a white play mark on the accent color,
 * drawn at the sizes Windows asks for so it is never scaled up from a small bitmap.
 */
internal object AppIcon {
    private val ACCENT = Color(0x8B, 0x7C, 0xFF)
    private val SIZES = listOf(16, 20, 24, 32, 40, 48, 64, 128, 256)

    val images: List<Image> by lazy { SIZES.map(::draw) }

    private fun draw(size: Int): Image {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val s = size.toDouble()
            graphics.color = ACCENT
            graphics.fill(RoundRectangle2D.Double(0.0, 0.0, s, s, s * 0.45, s * 0.45))
            graphics.color = Color.WHITE
            val play =
                Path2D.Double().apply {
                    moveTo(s * 0.38, s * 0.28)
                    lineTo(s * 0.38, s * 0.72)
                    lineTo(s * 0.74, s * 0.50)
                    closePath()
                }
            graphics.fill(play)
        } finally {
            graphics.dispose()
        }
        return image
    }
}
