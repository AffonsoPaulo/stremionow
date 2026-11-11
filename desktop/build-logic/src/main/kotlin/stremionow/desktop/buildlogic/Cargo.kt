// SPDX-License-Identifier: GPL-3.0-or-later
package stremionow.desktop.buildlogic

import java.io.File

// Mirrors android/build-logic (Cargo.kt). Both builds will share one copy once the desktop app
// joins the Kotlin Multiplatform build.

private val osName = System.getProperty("os.name").lowercase()

/** Directory where rustup installs `cargo`. */
internal val cargoBinDirectory: File = File(System.getProperty("user.home"), ".cargo/bin")

/** Path to `cargo`, preferring the rustup install so builds also work from an IDE without a shell PATH. */
internal fun cargoExecutable(): String {
    val fromRustup = File(cargoBinDirectory, if (osName.contains("windows")) "cargo.exe" else "cargo")
    return if (fromRustup.isFile) fromRustup.absolutePath else "cargo"
}

/** PATH with the rustup directory first, so `cargo` finds its subcommands. */
internal fun pathWithCargo(): String =
    cargoBinDirectory.absolutePath + File.pathSeparator + System.getenv("PATH").orEmpty()

/** File name of a shared library built for the machine running the build. */
fun hostLibraryFileName(libraryName: String): String =
    when {
        osName.contains("windows") -> "$libraryName.dll"
        osName.contains("mac") -> "lib$libraryName.dylib"
        else -> "lib$libraryName.so"
    }
