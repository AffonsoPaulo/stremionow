// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.desktop

import com.sun.jna.NativeLibrary
import java.io.File

/**
 * Finds the native libraries and data files bundled with the app.
 *
 * Compose puts them in the directory named by the `compose.application.resources.dir` system
 * property, both for `./gradlew run` and in installed apps (`<app>/app/resources` on Windows).
 * When the property is missing, for example when running the main class from an IDE, JNA's
 * usual search applies: `-Djna.library.path=...` or the system library path.
 */
object NativeLibraries {
    private const val RESOURCES_DIR_PROPERTY = "compose.application.resources.dir"

    /** JNA names of the bundled libraries: libmpv on Windows and the Rust core everywhere. */
    private val BUNDLED_LIBRARIES = listOf("libmpv-2", "stremionow_ffi")

    /** The bundled resources directory, or null when the app runs without one. */
    val resourcesDirectory: File? by lazy {
        System.getProperty(RESOURCES_DIR_PROPERTY)?.let(::File)?.takeIf(File::isDirectory)
    }

    /** Mozilla CA store for mpv's TLS verification, or null when it is not bundled. */
    val caBundle: File?
        get() = resourcesDirectory?.resolve("cacert.pem")?.takeIf(File::isFile)

    /** Makes JNA look in the resources directory first. Call before any native library loads. */
    fun install() {
        val directory = resourcesDirectory ?: return
        for (library in BUNDLED_LIBRARIES) NativeLibrary.addSearchPath(library, directory.absolutePath)
    }
}
