// SPDX-License-Identifier: GPL-3.0-or-later
package stremionow.buildlogic

import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Builds the Rust core for Android and generates its Kotlin bindings, for the Kotlin
 * Multiplatform module that applies this plugin.
 *
 * - The UniFFI bindings go to `commonMain`: they are plain JVM code over JNA, the same on
 *   Android and on the desktop.
 * - The native libraries, cross-compiled with cargo-ndk for each Android ABI, go to the Android
 *   target. The desktop build compiles the core for its own machine (see desktop/build-logic).
 */
class RustCorePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val workspace =
            project.rootProject.layout.projectDirectory
                .dir("../core")
        val workspaceSources = project.fileTree(workspace) { exclude("target/**") }
        val ndkVersion =
            project
                .versionCatalog()
                .findVersion("ndk")
                .get()
                .requiredVersion

        val generateBindings =
            project.tasks.register("generateRustBindings", UniffiBindgenTask::class.java) {
                group = "build"
                description = "Generates the Kotlin bindings of the Rust core."
                workspaceDirectory.set(workspace)
                sources.from(workspaceSources)
                crateName.set(CRATE)
                libraryName.set(LIBRARY)
                outputDirectory.set(project.layout.buildDirectory.dir("generated/rustCore/kotlin"))
            }

        project.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            project.extensions
                .getByType(KotlinMultiplatformExtension::class.java)
                .sourceSets
                .getByName("commonMain")
                .kotlin
                .srcDir(generateBindings.flatMap { it.outputDirectory })
        }

        project.plugins.withId("com.android.kotlin.multiplatform.library") {
            val components = project.extensions.getByType(KotlinMultiplatformAndroidComponentsExtension::class.java)
            val buildNative =
                project.tasks.register("buildRustCore", CargoNdkBuildTask::class.java) {
                    group = "build"
                    description = "Cross-compiles the Rust core for each Android ABI."
                    workspaceDirectory.set(workspace)
                    sources.from(workspaceSources)
                    crateName.set(CRATE)
                    abis.set(project.androidAbis())
                    // Android Kotlin Multiplatform libraries have no `ndkVersion`; use the pinned NDK.
                    ndkDirectory.set(components.sdkComponents.sdkDirectory.map { it.dir("ndk/$ndkVersion") })
                    outputDirectory.set(project.layout.buildDirectory.dir("generated/rustCore/jniLibs"))
                }
            components.onVariants { variant ->
                variant.sources.jniLibs?.addGeneratedSourceDirectory(buildNative, CargoNdkBuildTask::outputDirectory)
            }
        }
    }

    private companion object {
        const val CRATE = "stremionow-ffi"
        const val LIBRARY = "stremionow_ffi"
    }
}
