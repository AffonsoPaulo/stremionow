// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.desktop

import io.github.affonsopaulo.stremionow.ui.desktop.IcoFile
import java.io.File
import javax.imageio.ImageIO

/** Size of the PNG the Linux packages use. */
private const val LINUX_ICON_SIZE = 512

/**
 * Writes the installers' pictures into the folder given: `StremioNow.ico` for Windows and
 * `StremioNow.png` for Linux, drawn by [AppIcon], and the Windows installer's own ([AppSplash]):
 * `StremioNow-splash.png` while it installs, `StremioNow-dialog.bmp` behind its first and last
 * pages and `StremioNow-banner.bmp` across the others. Run it after changing the icon with
 * `./gradlew :app:writeAppIcons`; the files are kept in `desktop/app/icons/`.
 */
fun main(args: Array<String>) {
    val folder = File(args.single()).apply { mkdirs() }
    File(folder, "StremioNow.ico").outputStream().use { IcoFile.write(AppIcon.images, it) }
    ImageIO.write(AppIcon.render(LINUX_ICON_SIZE), "png", File(folder, "StremioNow.png"))
    File(folder, "StremioNow-splash.png").writeBytes(AppSplash.splash())
    File(folder, "StremioNow-dialog.bmp").writeBytes(AppSplash.bmp(AppSplash.dialog()))
    File(folder, "StremioNow-banner.bmp").writeBytes(AppSplash.bmp(AppSplash.banner()))
}
