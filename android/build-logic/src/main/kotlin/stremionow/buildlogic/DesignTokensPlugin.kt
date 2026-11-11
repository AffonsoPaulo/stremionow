// SPDX-License-Identifier: GPL-3.0-or-later
package stremionow.buildlogic

import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Generates the Compose theme and color resources from `design/tokens.json`. The theme goes to
 * `commonMain` of the Kotlin Multiplatform module that applies this plugin, so every platform
 * shares it; the color resources go to its Android target, for window themes and drawables.
 */
class DesignTokensPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val generate =
            project.tasks.register("generateDesignTokens", GenerateDesignTokensTask::class.java) {
                group = "build"
                description = "Generates the Compose theme and color resources from the design tokens."
                tokensFile.set(
                    project.rootProject.layout.projectDirectory
                        .file("../design/tokens.json"),
                )
                packageName.set(PACKAGE)
                kotlinOutputDirectory.set(project.layout.buildDirectory.dir("generated/designTokens/kotlin"))
                resOutputDirectory.set(project.layout.buildDirectory.dir("generated/designTokens/res"))
            }

        project.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            project.extensions
                .getByType(KotlinMultiplatformExtension::class.java)
                .sourceSets
                .getByName("commonMain")
                .kotlin
                .srcDir(generate.flatMap { it.kotlinOutputDirectory })
        }
        project.plugins.withId("com.android.kotlin.multiplatform.library") {
            val components = project.extensions.getByType(KotlinMultiplatformAndroidComponentsExtension::class.java)
            components.onVariants { variant ->
                variant.sources.res?.addGeneratedSourceDirectory(generate, GenerateDesignTokensTask::resOutputDirectory)
            }
        }
    }

    private companion object {
        const val PACKAGE = "io.github.affonsopaulo.stremionow.ui.theme"
    }
}
