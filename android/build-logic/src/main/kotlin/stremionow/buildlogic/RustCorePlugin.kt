// SPDX-License-Identifier: GPL-3.0-or-later
package stremionow.buildlogic

import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Builds the Rust core for Android and adds its native libraries and Kotlin bindings to
 * every variant of the Android library module that applies this plugin.
 */
class RustCorePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val workspace = project.rootProject.layout.projectDirectory.dir("../core")
        val workspaceSources = project.fileTree(workspace) { exclude("target/**") }

        project.plugins.withId("com.android.library") {
            val components = project.extensions.getByType(LibraryAndroidComponentsExtension::class.java)

            val buildNative = project.tasks.register("buildRustCore", CargoNdkBuildTask::class.java) {
                group = "build"
                description = "Cross-compiles the Rust core for each Android ABI."
                workspaceDirectory.set(workspace)
                sources.from(workspaceSources)
                crateName.set(CRATE)
                abis.set(ANDROID_ABIS)
                ndkDirectory.set(components.sdkComponents.ndkDirectory)
                outputDirectory.set(project.layout.buildDirectory.dir("generated/rustCore/jniLibs"))
            }

            val generateBindings = project.tasks.register("generateRustBindings", UniffiBindgenTask::class.java) {
                group = "build"
                description = "Generates the Kotlin bindings of the Rust core."
                workspaceDirectory.set(workspace)
                sources.from(workspaceSources)
                crateName.set(CRATE)
                libraryName.set(LIBRARY)
                outputDirectory.set(project.layout.buildDirectory.dir("generated/rustCore/kotlin"))
            }

            components.onVariants { variant ->
                variant.sources.jniLibs?.addGeneratedSourceDirectory(buildNative, CargoNdkBuildTask::outputDirectory)
                (variant.sources.kotlin ?: variant.sources.java)
                    ?.addGeneratedSourceDirectory(generateBindings, UniffiBindgenTask::outputDirectory)
            }
        }
    }

    private companion object {
        const val CRATE = "stremionow-ffi"
        const val LIBRARY = "stremionow_ffi"
    }
}
