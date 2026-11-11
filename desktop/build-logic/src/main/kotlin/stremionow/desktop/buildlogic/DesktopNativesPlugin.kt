// SPDX-License-Identifier: GPL-3.0-or-later
package stremionow.desktop.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Sync

/**
 * Prepares the native parts of the desktop app:
 *
 * - `buildRustCore`: the Rust core as a release shared library for this machine;
 * - `generateRustBindings`: its UniFFI Kotlin bindings, read from a debug build;
 * - `downloadLibmpv` and `extractLibmpv`: the pinned libmpv build for Windows x86_64;
 * - `downloadCaBundle`: the pinned Mozilla CA store for mpv's TLS verification;
 * - `prepareNativeResources`: all of the above laid out as a Compose resources root
 *   (`common/` plus one folder per OS and architecture) in [RESOURCES_ROOT].
 *
 * Pins live in `gradle.properties` (`stremionow.libmpv.*`, `stremionow.cacert.*`).
 */
class DesktopNativesPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val workspace =
            project.rootProject.layout.projectDirectory
                .dir("../core")
        val workspaceSources = project.fileTree(workspace) { exclude("target/**") }
        val tasks = project.tasks
        val buildDirectory = project.layout.buildDirectory
        val properties = project.providers

        val buildRustCore =
            tasks.register("buildRustCore", CargoHostBuildTask::class.java) {
                group = "build"
                description = "Builds the Rust core as a release shared library for this machine."
                workspaceDirectory.set(workspace)
                sources.from(workspaceSources)
                crateName.set(CRATE)
                libraryName.set(LIBRARY)
                outputDirectory.set(buildDirectory.dir("generated/rustCore/native"))
            }

        tasks.register("generateRustBindings", UniffiBindgenTask::class.java) {
            group = "build"
            description = "Generates the Kotlin bindings of the Rust core."
            workspaceDirectory.set(workspace)
            sources.from(workspaceSources)
            crateName.set(CRATE)
            libraryName.set(LIBRARY)
            outputDirectory.set(buildDirectory.dir("generated/rustCore/kotlin"))
        }

        val downloadLibmpv =
            tasks.register("downloadLibmpv", VerifiedDownloadTask::class.java) {
                description = "Downloads the pinned libmpv build for Windows x86_64."
                url.set(properties.gradleProperty("stremionow.libmpv.url"))
                sha256.set(properties.gradleProperty("stremionow.libmpv.sha256"))
                outputFile.set(buildDirectory.file("downloads/mpv-dev-x86_64.7z"))
            }

        val extractLibmpv =
            tasks.register("extractLibmpv", Extract7zTask::class.java) {
                description = "Extracts libmpv-2.dll from the downloaded archive."
                archive.set(downloadLibmpv.flatMap { it.outputFile })
                entries.add(LIBMPV_WINDOWS)
                outputDirectory.set(buildDirectory.dir("generated/libmpv"))
            }

        val downloadCaBundle =
            tasks.register("downloadCaBundle", VerifiedDownloadTask::class.java) {
                description = "Downloads the pinned Mozilla CA store that mpv uses to verify HTTPS servers."
                url.set(properties.gradleProperty("stremionow.cacert.url"))
                sha256.set(properties.gradleProperty("stremionow.cacert.sha256"))
                outputFile.set(buildDirectory.file("downloads/cacert.pem"))
            }

        tasks.register("prepareNativeResources", Sync::class.java) {
            group = "build"
            description = "Collects the native libraries and the CA store bundled with the app."
            into(buildDirectory.dir(RESOURCES_ROOT))
            into("common") { from(downloadCaBundle) }
            into(hostResourceFolder()) {
                from(buildRustCore)
                // Other systems load libmpv from their package manager (see docs/poc/phase-5.md).
                if (isWindows) from(extractLibmpv)
            }
        }
    }

    companion object {
        /** Folder of the build directory laid out for Compose's `appResourcesRootDir`. */
        const val RESOURCES_ROOT = "appResources"

        private const val CRATE = "stremionow-ffi"
        private const val LIBRARY = "stremionow_ffi"
        private const val LIBMPV_WINDOWS = "libmpv-2.dll"

        private val osName = System.getProperty("os.name").lowercase()
        private val isWindows = osName.contains("windows")

        /** Compose's resource folder for this machine, for example `windows-x64` or `macos-arm64`. */
        private fun hostResourceFolder(): String {
            val os =
                when {
                    isWindows -> "windows"
                    osName.contains("mac") -> "macos"
                    else -> "linux"
                }
            val arch =
                when (System.getProperty("os.arch").lowercase()) {
                    "aarch64", "arm64" -> "arm64"
                    else -> "x64"
                }
            return "$os-$arch"
        }
    }
}
