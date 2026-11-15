// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data

import io.github.affonsopaulo.stremionow.ui.platform.PlatformContext
import io.github.affonsopaulo.stremionow.ui.platform.logWarning
import java.io.File
import kotlin.concurrent.thread
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

internal actual fun coreDataDirectory(context: PlatformContext): File = File(DesktopPaths.appData, "core")

internal actual fun coreCacheDirectory(): File? {
    val local = DesktopPaths.localData ?: return null
    DesktopPaths.removeRoamingCache()
    return File(local, "streams")
}

/** Where the desktop app keeps its data. */
internal object DesktopPaths {
    private val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    private val home = File(System.getProperty("user.home"))

    /**
     * The user's application data folder for the app, created on first use:
     * `%APPDATA%\StremioNow` on Windows, `~/Library/Application Support/StremioNow` on macOS and
     * `$XDG_DATA_HOME/stremionow` (by default `~/.local/share/stremionow`) elsewhere.
     */
    val appData: File by lazy {
        val os = System.getProperty("os.name").lowercase()
        val folder =
            when {
                isWindows -> File(environmentFolder("APPDATA") ?: File(home, "AppData/Roaming"), "StremioNow")
                os.contains("mac") -> File(home, "Library/Application Support/StremioNow")
                else -> File(environmentFolder("XDG_DATA_HOME") ?: File(home, ".local/share"), "stremionow")
            }
        folder.also(::create)
    }

    /**
     * On Windows, the app's folder in the local profile, `%LOCALAPPDATA%\StremioNow`, for what
     * must not roam with the user's profile between computers: the torrent cache, which can hold
     * gigabytes. Null elsewhere, where [appData] is local already.
     */
    val localData: File? by lazy {
        if (!isWindows) return@lazy null
        File(environmentFolder("LOCALAPPDATA") ?: File(home, "AppData/Local"), "StremioNow").also(::create)
    }

    /**
     * Deletes, in the background, the torrent cache that earlier versions kept in the roaming
     * profile; only a cache, but it can hold gigabytes that Windows would copy at every sign-in.
     */
    @OptIn(ExperimentalPathApi::class)
    fun removeRoamingCache() {
        val old = File(appData, "core/streams")
        if (!old.isDirectory) return
        thread(name = "old-cache-cleanup", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            // Path.deleteRecursively does not follow links or junctions out of the folder.
            runCatching { old.toPath().deleteRecursively() }
                .onFailure { logWarning("Could not delete the old torrent cache", it) }
        }
    }

    /**
     * Creates [folder] if needed. A failure is only logged here: whatever needs the folder
     * (the core, crash reports) reports its own error when it cannot write there.
     */
    private fun create(folder: File) {
        if (!folder.isDirectory && !folder.mkdirs() && !folder.isDirectory) {
            logWarning("Could not create one of the app's data folders")
        }
    }

    /** An absolute folder from an environment variable; relative values are ignored, as XDG requires. */
    private fun environmentFolder(name: String): File? =
        System
            .getenv(name)
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf(File::isAbsolute)
}
