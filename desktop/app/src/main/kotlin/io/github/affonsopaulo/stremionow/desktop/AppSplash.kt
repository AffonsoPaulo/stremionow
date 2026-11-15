// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.desktop

import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontVariation
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import org.jetbrains.skia.TextLine
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * The picture the installer shows while it installs (Velopack's splash): the app's icon and its
 * wordmark, "Stremio" in white and "Now" in the accent, on the app's background, as the app
 * draws them. Drawn with Skia, the app's own renderer, so the wordmark is Outfit ExtraBold like
 * in the app (Java2D cannot pick a weight of a variable font). The installer draws its progress
 * bar along the bottom, in the accent.
 */
internal object AppSplash {
    const val WIDTH = 600
    const val HEIGHT = 300

    private const val BACKGROUND = 0xFF0A0B10.toInt()
    private const val FOREGROUND = 0xFFF4F5F8.toInt()
    private const val ACCENT = 0xFF8B7CFF.toInt()

    /** The icon's side and the wordmark's size, in pixels. */
    private const val ICON = 104
    private const val WORDMARK_SIZE = 54f
    private const val GAP = 28f

    /** The splash as PNG bytes. */
    fun png(): ByteArray {
        val surface = Surface.makeRasterN32Premul(WIDTH, HEIGHT)
        val canvas = surface.canvas
        canvas.clear(BACKGROUND)

        val font = Font(wordmarkTypeface(), WORDMARK_SIZE)
        val stremio = TextLine.make("Stremio", font)
        val now = TextLine.make("Now", font)
        val wordmarkWidth = stremio.width + now.width
        val left = (WIDTH - (ICON + GAP + wordmarkWidth)) / 2f
        val top = (HEIGHT - ICON) / 2f

        val icon = Image.makeFromEncoded(iconPng())
        canvas.drawImageRect(
            icon,
            Rect.makeWH(icon.width.toFloat(), icon.height.toFloat()),
            Rect.makeXYWH(left, top, ICON.toFloat(), ICON.toFloat()),
            SamplingMode.MITCHELL,
            Paint(),
            true,
        )

        // The wordmark centred on the icon's middle, by its cap height.
        val x = left + ICON + GAP
        val baseline = HEIGHT / 2f + font.metrics.capHeight / 2f
        canvas.drawTextLine(stremio, x, baseline, Paint().apply { color = FOREGROUND })
        canvas.drawTextLine(now, x + stremio.width, baseline, Paint().apply { color = ACCENT })

        val data =
            surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG) ?: error("Could not encode the splash")
        return data.bytes
    }

    /** Outfit at ExtraBold, the wordmark's face, from the shared interface's fonts. */
    private fun wordmarkTypeface() =
        FontMgr.default
            .makeFromData(Data.makeFromBytes(resource("font/outfit.ttf")))
            ?.makeClone(FontVariation("wght", EXTRA_BOLD))
            ?: error("Could not read the wordmark's font")

    /** The icon, drawn larger than shown so it stays sharp when scaled down. */
    private fun iconPng(): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            ImageIO.write(AppIcon.render(ICON * 2), "png", bytes)
            bytes.toByteArray()
        }

    private fun resource(name: String): ByteArray =
        AppSplash::class.java.classLoader
            .getResourceAsStream(name)
            ?.use { it.readBytes() }
            ?: error("Missing resource $name")

    private const val EXTRA_BOLD = 800f
}
