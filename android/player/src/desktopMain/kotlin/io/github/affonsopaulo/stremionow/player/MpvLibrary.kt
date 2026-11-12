// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.player

import com.sun.jna.Platform
import java.io.File

/** libmpv is missing or unusable on this computer; the message says why. */
class MpvUnavailableException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Where libmpv comes from. The app plays only with the libmpv bundled with it, on every system,
 * loaded by absolute path from the app's own native folder: never the working directory, the
 * `PATH`, the system's libraries or Homebrew, which could hold another (or a planted) build.
 * See docs/adr/0005-desktop-ui.md.
 *
 * Development runs on macOS and Linux, where the build does not bundle libmpv yet, may fall back
 * to the system's: only when the `stremionow.libmpv.allowSystem` system property is `true`, which
 * Gradle's `run` task sets and installers never do.
 *
 * - Windows: `libmpv-2.dll` (a pinned, checksum-verified build).
 * - macOS: `libmpv.2.dylib`; in development also Homebrew's (`/opt/homebrew`, `/usr/local`) or
 *   MacPorts' (`/opt/local`).
 * - Linux and others: `libmpv.so.2`; in development also the system's, found by the system linker
 *   by its soname (`libmpv2` on Debian and Ubuntu, `mpv-libs` on Fedora, `mpv` on Arch).
 */
object MpvLibrary {
    /** Set to `true` by `./gradlew run` only. */
    const val ALLOW_SYSTEM_PROPERTY = "stremionow.libmpv.allowSystem"

    private const val WINDOWS = "libmpv-2.dll"
    private const val MACOS = "libmpv.2.dylib"
    private const val SONAME = "libmpv.so.2"

    private val MACOS_DEVELOPMENT_LOCATIONS = listOf("/opt/homebrew/lib", "/usr/local/lib", "/opt/local/lib")

    private val bundledName: String
        get() =
            when {
                Platform.isWindows() -> WINDOWS
                Platform.isMac() -> MACOS
                else -> SONAME
            }

    /**
     * The absolute path (or, in development on Linux, the soname) to load libmpv from, given the
     * folder of native libraries bundled with the app ([bundledDirectory], null when there is none).
     *
     * @throws MpvUnavailableException when there is no libmpv where it may be loaded from.
     */
    fun locate(
        bundledDirectory: File?,
        allowSystem: Boolean = System.getProperty(ALLOW_SYSTEM_PROPERTY) == "true",
    ): String {
        bundledDirectory
            ?.takeIf(File::isAbsolute)
            ?.resolve(bundledName)
            ?.takeIf(File::isFile)
            ?.let { return it.absolutePath }
        if (allowSystem && !Platform.isWindows()) {
            if (!Platform.isMac()) return SONAME
            MACOS_DEVELOPMENT_LOCATIONS
                .map { File(it, MACOS) }
                .firstOrNull(File::isFile)
                ?.let { return it.absolutePath }
        }
        throw MpvUnavailableException("$bundledName is not bundled with the app. ${installHint()}")
    }

    /** What to do about a missing libmpv, for the log. */
    internal fun installHint(): String =
        when {
            System.getProperty(ALLOW_SYSTEM_PROPERTY) != "true" -> {
                "Reinstall StremioNow, or start a development build with `./gradlew run`."
            }

            Platform.isMac() -> {
                "For development, install it with Homebrew: `brew install mpv`."
            }

            else -> {
                "For development, install libmpv 2: `sudo apt install libmpv2` (Debian, Ubuntu), " +
                    "`sudo dnf install mpv-libs` (Fedora) or `sudo pacman -S mpv` (Arch)."
            }
        }
}
