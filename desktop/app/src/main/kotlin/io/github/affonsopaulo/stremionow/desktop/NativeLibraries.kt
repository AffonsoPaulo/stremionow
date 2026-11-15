// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.desktop

import com.sun.jna.NativeLibrary
import java.io.File

/**
 * Finds the native libraries and data files bundled with the app.
 *
 * Compose puts them in the directory named by the `compose.application.resources.dir` system
 * property, both for `./gradlew run` and in installed apps (`<app>/app/resources` on Windows).
 * Native libraries are only loaded from there (or, for libmpv, from the fixed system locations of
 * `MpvLibrary`), never searched for in the working directory or the `PATH`.
 */
object NativeLibraries {
    private const val RESOURCES_DIR_PROPERTY = "compose.application.resources.dir"
    private const val LIBRARY_PATH_PROPERTY = "jna.library.path"

    /** JNA name of the Rust core, which the UniFFI bindings load. */
    private const val CORE = "stremionow_ffi"

    /** JNA's own native library, and the property that names the folder it is loaded from first. */
    private const val JNA_DISPATCH = "jnidispatch"
    private const val JNA_BOOT_PATH_PROPERTY = "jna.boot.library.path"

    /** The bundled resources directory, or null when the app runs without one. */
    val resourcesDirectory: File? by lazy {
        System
            .getProperty(RESOURCES_DIR_PROPERTY)
            ?.let(::File)
            ?.takeIf { it.isAbsolute && it.isDirectory }
    }

    /** Mozilla CA store for mpv's TLS verification, or null when it is not bundled. */
    val caBundle: File?
        get() = resourcesDirectory?.resolve("cacert.pem")?.takeIf(File::isFile)

    /**
     * Has JNA load its own native part (`jnidispatch`) from the bundled resources, where the
     * build puts the copy from the JNA jar in use. Otherwise JNA unpacks it from its jar into the
     * temporary folder on every launch. Call before anything touches JNA; without a bundled copy
     * (a start from an IDE) JNA does as before.
     *
     * `jna.nounpack` stays off on purpose: JNA loads from this path before it would unpack
     * anything, and with the property on it would also stop deleting the copies that earlier
     * launches left in the temporary folder.
     */
    fun useBundledJnaDispatch() {
        val directory = resourcesDirectory ?: return
        // JNA looks for .jnilib on macOS, as its jar names the file.
        val name = System.mapLibraryName(JNA_DISPATCH).replace(".dylib", ".jnilib")
        if (File(directory, name).isFile) System.setProperty(JNA_BOOT_PATH_PROPERTY, directory.absolutePath)
    }

    /**
     * Points JNA at the bundled Rust core. Call before any native library loads.
     *
     * Without a resources directory (the main class started from an IDE), the core is only looked
     * for where `-Djna.library.path` says; with neither, the app stops here rather than let the
     * system search the working directory for it.
     */
    fun install() {
        val directory = resourcesDirectory
        if (directory == null) {
            check(!System.getProperty(LIBRARY_PATH_PROPERTY).isNullOrBlank()) {
                "No bundled native libraries: start the app with `./gradlew run`, or set -D$LIBRARY_PATH_PROPERTY " +
                    "to the folder that holds ${System.mapLibraryName(CORE)}"
            }
            return
        }
        val core = File(directory, System.mapLibraryName(CORE))
        check(core.isFile) { "${core.name} is missing from $directory" }
        NativeLibrary.addSearchPath(CORE, directory.absolutePath)
    }
}
