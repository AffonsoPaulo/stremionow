// SPDX-License-Identifier: GPL-3.0-or-later
package stremionow.desktop.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.tasks.Sync

/**
 * Prepares the native parts of the desktop app:
 *
 * - `buildRustCore`: the Rust core as a release shared library for this machine (its Kotlin
 *   bindings come with the shared `core-bridge` module of the Android build);
 * - `downloadLibmpv` and `extractLibmpv`: the pinned libmpv build for Windows x86_64;
 * - `extractJnaDispatch`: JNA's own native library for this machine, out of the JNA jar;
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

        // JNA's native part, from the JNA jar the app runs with, so that the two always match.
        // Bundled, it is loaded from the app's folder (NativeLibraries.useBundledJnaDispatch)
        // instead of being unpacked into the temporary folder on every launch.
        val extractJnaDispatch =
            tasks.register("extractJnaDispatch", ExtractJarEntryTask::class.java) {
                description = "Copies JNA's native library for this machine out of the JNA jar."
                entry.set(jnaDispatchEntry())
                outputDirectory.set(buildDirectory.dir("generated/jnaDispatch"))
            }
        project.pluginManager.withPlugin("java") {
            val runtimeClasspath = project.configurations.getByName("runtimeClasspath")
            val jna =
                runtimeClasspath.incoming
                    .artifactView {
                        componentFilter { id ->
                            id is ModuleComponentIdentifier && id.group == JNA_GROUP && id.module == JNA_MODULE
                        }
                    }.files
            extractJnaDispatch.configure { jar.from(jna) }
        }

        // Only Windows x64 bundles libmpv so far; the pinned build is x86_64 and would not load
        // in an ARM64 process. Development runs on macOS and Linux fall back to the system's
        // (MpvLibrary); packaging it is still to do (docs/adr/0005-desktop-ui.md).
        val bundlesLibmpv = isWindows && hostArch == "x64"
        if (isWindows && !bundlesLibmpv) {
            project.logger.warn(
                "No libmpv build is pinned for Windows on $hostArch: the app builds, but its player reports " +
                    "that video playback is unavailable.",
            )
        }

        tasks.register("prepareNativeResources", Sync::class.java) {
            group = "build"
            description = "Collects the native libraries and the CA store bundled with the app."
            into(buildDirectory.dir(RESOURCES_ROOT))
            into("common") { from(downloadCaBundle) }
            into(hostResourceFolder()) {
                from(buildRustCore)
                from(extractJnaDispatch)
                if (bundlesLibmpv) from(extractLibmpv)
            }
        }
    }

    companion object {
        /** Folder of the build directory laid out for Compose's `appResourcesRootDir`. */
        const val RESOURCES_ROOT = "appResources"

        private const val CRATE = "stremionow-ffi"
        private const val LIBRARY = "stremionow_ffi"
        private const val LIBMPV_WINDOWS = "libmpv-2.dll"

        private const val JNA_GROUP = "net.java.dev.jna"
        private const val JNA_MODULE = "jna"

        private val osName = System.getProperty("os.name").lowercase()
        private val isWindows = osName.contains("windows")
        private val isMac = osName.contains("mac")

        /** This machine's architecture as Compose names it: `x64` or `arm64`. */
        private val hostArch =
            when (System.getProperty("os.arch").lowercase()) {
                "aarch64", "arm64" -> "arm64"
                else -> "x64"
            }

        /** Compose's resource folder for this machine, for example `windows-x64` or `macos-arm64`. */
        private fun hostResourceFolder(): String {
            val os =
                when {
                    isWindows -> "windows"
                    isMac -> "macos"
                    else -> "linux"
                }
            return "$os-$hostArch"
        }

        /** Where the JNA jar keeps its native library for this machine. */
        private fun jnaDispatchEntry(): String {
            val arch = if (hostArch == "arm64") "aarch64" else "x86-64"
            return when {
                isWindows -> "com/sun/jna/win32-$arch/jnidispatch.dll"
                isMac -> "com/sun/jna/darwin-$arch/libjnidispatch.jnilib"
                else -> "com/sun/jna/linux-$arch/libjnidispatch.so"
            }
        }
    }
}
