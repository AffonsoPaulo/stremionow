// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.Image
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities

/**
 * Puts the app's icon on a window.
 *
 * AWT gives Windows one picture for the taskbar and one for the title bar, chosen for 100%
 * scale, which Windows then scales up on a scaled display: at 150% the taskbar's 48 px came
 * from 32 px, and looked pixelated. On Windows the icon also goes in as an .ico holding every
 * size, loaded at the window's own scale, so each place gets a picture drawn for its size.
 */
object WindowIcon {
    /** Sets [images] (every size of one icon) on [window], now or once it opens. */
    fun apply(
        window: Window,
        images: List<Image>,
    ) {
        window.iconImages = images
        if (!Platform.isWindows()) return
        if (window.isDisplayable) setNative(window, images)
        window.addWindowListener(
            object : WindowAdapter() {
                override fun windowOpened(event: WindowEvent) = setNative(window, images)
            },
        )
        // AWT sets its own pictures again when the window moves to a display of another scale.
        window.addPropertyChangeListener("graphicsConfiguration") {
            SwingUtilities.invokeLater { if (window.isDisplayable) setNative(window, images) }
        }
    }

    private fun setNative(
        window: Window,
        images: List<Image>,
    ) {
        runCatching {
            val file = icoFile(images) ?: return
            val hwnd = HWND(Native.getWindowPointer(window))
            val dpi = Dpi.INSTANCE.GetDpiForWindow(hwnd).takeIf { it > 0 } ?: BASE_DPI
            for ((kind, base) in listOf(ICON_BIG to BIG_SIZE, ICON_SMALL to SMALL_SIZE)) {
                val size = (base * dpi + BASE_DPI / 2) / BASE_DPI
                val icon = loaded.getOrPut(size) { load(file, size) ?: return@runCatching }
                User32.INSTANCE.SendMessage(
                    hwnd,
                    WM_SETICON,
                    WPARAM(kind.toLong()),
                    LPARAM(Pointer.nativeValue(icon.pointer)),
                )
            }
        }
    }

    /** Icons loaded so far by size; Windows keeps them for the life of the app. */
    private val loaded = ConcurrentHashMap<Int, HANDLE>()

    @Volatile private var file: Pair<List<Image>, File>? = null

    /** The .ico of [images], written once to a temporary file for Windows to load. */
    @Synchronized
    private fun icoFile(images: List<Image>): File? {
        file?.takeIf { it.first === images }?.let { return it.second }
        val square = images.filterIsInstance<BufferedImage>()
        if (square.isEmpty()) return null
        val written =
            File.createTempFile("stremionow-icon", ".ico").apply {
                deleteOnExit()
                outputStream().use { IcoFile.write(square, it) }
            }
        file = images to written
        loaded.clear()
        return written
    }

    private fun load(
        file: File,
        size: Int,
    ): HANDLE? =
        User32.INSTANCE.LoadImage(null, file.absolutePath, WinUser.IMAGE_ICON, size, size, WinUser.LR_LOADFROMFILE)

    private const val WM_SETICON = 0x0080
    private const val ICON_SMALL = 0
    private const val ICON_BIG = 1
    private const val SMALL_SIZE = 16
    private const val BIG_SIZE = 32
    private const val BASE_DPI = 96

    @Suppress("FunctionName", "ktlint:standard:function-naming")
    private interface Dpi : StdCallLibrary {
        fun GetDpiForWindow(window: HWND): Int

        companion object {
            val INSTANCE: Dpi = Native.load("user32", Dpi::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }
}

/**
 * Writes Windows .ico files: 32-bit pictures with their alpha, as bitmaps below 256 px and
 * as PNG at 256 px, which is how Windows itself stores them.
 */
object IcoFile {
    private const val PNG_FROM = 256
    private const val HEADER = 6
    private const val ENTRY = 16
    private const val BITMAP_HEADER = 40

    /** Writes [images], one per size up to 256 px; other sizes and non-square ones are left out. */
    fun write(
        images: List<BufferedImage>,
        out: OutputStream,
    ) {
        val entries =
            images
                .filter { it.width == it.height && it.width in 1..PNG_FROM }
                .distinctBy { it.width }
                .sortedBy { it.width }
        val data = entries.map { if (it.width >= PNG_FROM) png(it) else bitmap(it) }
        val directory = ByteBuffer.allocate(HEADER + ENTRY * entries.size).order(ByteOrder.LITTLE_ENDIAN)
        directory.putShort(0).putShort(1).putShort(entries.size.toShort())
        var offset = HEADER + ENTRY * entries.size
        entries.forEachIndexed { index, image ->
            // 0 stands for 256.
            directory.put((image.width % PNG_FROM).toByte()).put((image.height % PNG_FROM).toByte())
            directory
                .put(0)
                .put(0)
                .putShort(1)
                .putShort(32)
            directory.putInt(data[index].size).putInt(offset)
            offset += data[index].size
        }
        out.write(directory.array())
        data.forEach(out::write)
    }

    private fun png(image: BufferedImage): ByteArray =
        java.io.ByteArrayOutputStream().use { bytes ->
            javax.imageio.ImageIO.write(image, "png", bytes)
            bytes.toByteArray()
        }

    /** A bottom-up BGRA bitmap and an empty mask: the alpha decides what shows. */
    private fun bitmap(image: BufferedImage): ByteArray {
        val size = image.width
        val maskRow = (size + 31) / 32 * 4
        val pixels = size * size * 4
        val buffer = ByteBuffer.allocate(BITMAP_HEADER + pixels + maskRow * size).order(ByteOrder.LITTLE_ENDIAN)
        buffer
            .putInt(BITMAP_HEADER)
            .putInt(size)
            .putInt(size * 2)
            .putShort(1)
            .putShort(32)
        buffer
            .putInt(0)
            .putInt(pixels + maskRow * size)
            .putInt(0)
            .putInt(0)
            .putInt(0)
            .putInt(0)
        for (y in size - 1 downTo 0) {
            for (x in 0 until size) {
                val argb = image.getRGB(x, y)
                buffer.put((argb and 0xFF).toByte())
                buffer.put((argb shr 8 and 0xFF).toByte())
                buffer.put((argb shr 16 and 0xFF).toByte())
                buffer.put((argb ushr 24).toByte())
            }
        }
        return buffer.array()
    }
}
