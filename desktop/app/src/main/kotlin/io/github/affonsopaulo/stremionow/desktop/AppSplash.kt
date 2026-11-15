// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.desktop

import org.jetbrains.skia.Canvas
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
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * The pictures of the Windows installer (Velopack's MSI), drawn with Skia, the app's own
 * renderer, so the wordmark is Outfit ExtraBold like in the app (Java2D cannot pick a weight of
 * a variable font): the icon and the wordmark, "Stremio" in white and "Now" in the accent, on
 * the app's background.
 *
 * - [splash]: shown while the app installs or updates, with the progress bar in the accent.
 * - [dialog]: the background of the installer's first and last pages. Windows draws their text
 *   in black on the right, so only the left column is dark.
 * - [banner]: the strip across the top of the other pages, under black text on the left.
 */
internal object AppSplash {
    private const val BACKGROUND = 0xFF0A0B10.toInt()
    private const val FOREGROUND = 0xFFF4F5F8.toInt()
    private const val ACCENT = 0xFF8B7CFF.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    /** The installer's progress window: the icon beside the wordmark. */
    fun splash(): ByteArray =
        draw(600, 300) { canvas ->
            canvas.clear(BACKGROUND)
            val font = Font(wordmarkTypeface(), 54f)
            val icon = 104f
            val gap = 28f
            val left = (600 - (icon + gap + wordmarkWidth(font))) / 2f
            drawIcon(canvas, left, (300 - icon) / 2f, icon)
            drawWordmark(canvas, font, left + icon + gap, 300 / 2f + font.metrics.capHeight / 2f)
        }

    /** The first and last pages' background (493 × 312, WiX's size): a dark column on the left. */
    fun dialog(): ByteArray =
        draw(493, 312) { canvas ->
            canvas.clear(WHITE)
            val column = 164f
            canvas.drawRect(Rect.makeWH(column, 312f), Paint().apply { color = BACKGROUND })
            val icon = 72f
            drawIcon(canvas, (column - icon) / 2f, 96f, icon)
            val font = Font(wordmarkTypeface(), 22f)
            drawWordmark(canvas, font, (column - wordmarkWidth(font)) / 2f, 96f + icon + 34f)
        }

    /** The other pages' top strip (493 × 58, WiX's size): white, with the icon on the right. */
    fun banner(): ByteArray =
        draw(493, 58) { canvas ->
            canvas.clear(WHITE)
            val icon = 40f
            drawIcon(canvas, 493f - icon - 12f, (58f - icon) / 2f, icon)
        }

    /** PNG bytes as BMP, the only format Windows Installer's pages take. */
    fun bmp(png: ByteArray): ByteArray {
        val source = ImageIO.read(ByteArrayInputStream(png))
        val rgb = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply {
            drawImage(source, 0, 0, null)
            dispose()
        }
        return ByteArrayOutputStream().use { bytes ->
            check(ImageIO.write(rgb, "bmp", bytes)) { "No BMP writer" }
            bytes.toByteArray()
        }
    }

    private fun draw(
        width: Int,
        height: Int,
        paint: (Canvas) -> Unit,
    ): ByteArray {
        val surface = Surface.makeRasterN32Premul(width, height)
        paint(surface.canvas)
        val data =
            surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG) ?: error("Could not encode the picture")
        return data.bytes
    }

    private fun drawIcon(
        canvas: Canvas,
        x: Float,
        y: Float,
        size: Float,
    ) {
        // Drawn larger than shown so it stays sharp when scaled down.
        val icon = Image.makeFromEncoded(iconPng((size * 2).toInt()))
        canvas.drawImageRect(
            icon,
            Rect.makeWH(icon.width.toFloat(), icon.height.toFloat()),
            Rect.makeXYWH(x, y, size, size),
            SamplingMode.MITCHELL,
            Paint(),
            true,
        )
    }

    private fun wordmarkWidth(font: Font) = TextLine.make("Stremio", font).width + TextLine.make("Now", font).width

    private fun drawWordmark(
        canvas: Canvas,
        font: Font,
        x: Float,
        baseline: Float,
    ) {
        val stremio = TextLine.make("Stremio", font)
        canvas.drawTextLine(stremio, x, baseline, Paint().apply { color = FOREGROUND })
        canvas.drawTextLine(TextLine.make("Now", font), x + stremio.width, baseline, Paint().apply { color = ACCENT })
    }

    /** Outfit at ExtraBold, the wordmark's face, from the shared interface's fonts. */
    private fun wordmarkTypeface() =
        FontMgr.default
            .makeFromData(Data.makeFromBytes(resource("font/outfit.ttf")))
            ?.makeClone(FontVariation("wght", EXTRA_BOLD))
            ?: error("Could not read the wordmark's font")

    private fun iconPng(size: Int): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            ImageIO.write(AppIcon.render(size), "png", bytes)
            bytes.toByteArray()
        }

    private fun resource(name: String): ByteArray =
        AppSplash::class.java.classLoader
            .getResourceAsStream(name)
            ?.use { it.readBytes() }
            ?: error("Missing resource $name")

    private const val EXTRA_BOLD = 800f
}
