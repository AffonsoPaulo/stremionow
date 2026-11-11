// SPDX-License-Identifier: GPL-3.0-or-later
package stremionow.buildlogic

import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Wires the repository's `l10n/res` strings (docs/adr/0003-strings-format.md) into the Kotlin
 * Multiplatform module that applies this plugin:
 *
 * - Android reads them as ordinary resources: `l10n/res` is added to the Android target's
 *   resource directories, with no copy step.
 * - `generateStrings` ([GenerateStringsTask]) turns them into shared keys, the Android mapping
 *   to `R`, and per-locale tables for the desktop target.
 */
class StringsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val res =
            project.rootProject.layout.projectDirectory
                .dir("../l10n/res")
        val output = project.layout.buildDirectory.dir("generated/strings")
        val generate =
            project.tasks.register("generateStrings", GenerateStringsTask::class.java) {
                group = "build"
                description = "Generates the shared string keys and the desktop string tables from l10n/res."
                resDirectory.set(res)
                packageName.set(PACKAGE)
                androidNamespace.set(ANDROID_NAMESPACE)
                commonOutputDirectory.set(output.map { it.dir("common") })
                androidOutputDirectory.set(output.map { it.dir("android") })
                desktopOutputDirectory.set(output.map { it.dir("desktop") })
            }

        project.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            val sourceSets = project.extensions.getByType(KotlinMultiplatformExtension::class.java).sourceSets
            // The target source sets appear once the targets are declared, which may come later.
            sourceSets.matching { it.name == "commonMain" }.configureEach {
                kotlin.srcDir(generate.flatMap { it.commonOutputDirectory })
            }
            sourceSets.matching { it.name == "androidMain" }.configureEach {
                kotlin.srcDir(generate.flatMap { it.androidOutputDirectory })
            }
            sourceSets.matching { it.name == "${DESKTOP_TARGET}Main" }.configureEach {
                resources.srcDir(generate.flatMap { it.desktopOutputDirectory })
            }
        }
        project.plugins.withId("com.android.kotlin.multiplatform.library") {
            val components = project.extensions.getByType(KotlinMultiplatformAndroidComponentsExtension::class.java)
            components.onVariants { variant -> variant.sources.res?.addStaticSourceDirectory(res.asFile.absolutePath) }
        }
    }

    private companion object {
        const val PACKAGE = "io.github.affonsopaulo.stremionow.ui.l10n"
        const val ANDROID_NAMESPACE = "io.github.affonsopaulo.stremionow.ui"
    }
}
