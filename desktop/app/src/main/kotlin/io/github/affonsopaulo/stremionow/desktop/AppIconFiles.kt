// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.desktop

import io.github.affonsopaulo.stremionow.ui.desktop.IcoFile
import java.io.File
import javax.imageio.ImageIO

/** Size of the PNG the Linux packages use. */
private const val LINUX_ICON_SIZE = 512

/**
 * Writes the installers' icons, drawn by [AppIcon], into the folder given: `StremioNow.ico`
 * for Windows and `StremioNow.png` for Linux. Run it after changing the icon with
 * `./gradlew :app:writeAppIcons`; the files are kept in `desktop/app/icons/`.
 */
fun main(args: Array<String>) {
    val folder = File(args.single()).apply { mkdirs() }
    File(folder, "StremioNow.ico").outputStream().use { IcoFile.write(AppIcon.images, it) }
    ImageIO.write(AppIcon.render(LINUX_ICON_SIZE), "png", File(folder, "StremioNow.png"))
}
