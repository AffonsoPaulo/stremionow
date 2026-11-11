// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data

import io.github.affonsopaulo.stremionow.ui.platform.PlatformContext
import java.io.File

internal actual fun coreDataDirectory(context: PlatformContext): File = File(DesktopPaths.appData, "core")

/** Where the desktop app keeps its data. */
internal object DesktopPaths {
    /**
     * The user's application data folder for the app, created on first use:
     * `%APPDATA%\StremioNow` on Windows, `~/Library/Application Support/StremioNow` on macOS and
     * `$XDG_DATA_HOME/stremionow` (by default `~/.local/share/stremionow`) elsewhere.
     */
    val appData: File by lazy {
        val os = System.getProperty("os.name").lowercase()
        val home = File(System.getProperty("user.home"))
        val folder =
            when {
                os.contains(
                    "windows",
                ) -> File(environmentFolder("APPDATA") ?: File(home, "AppData/Roaming"), "StremioNow")

                os.contains("mac") -> File(home, "Library/Application Support/StremioNow")

                else -> File(environmentFolder("XDG_DATA_HOME") ?: File(home, ".local/share"), "stremionow")
            }
        folder.apply { mkdirs() }
    }

    /** An absolute folder from an environment variable; relative values are ignored, as XDG requires. */
    private fun environmentFolder(name: String): File? =
        System
            .getenv(name)
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf(File::isAbsolute)
}
