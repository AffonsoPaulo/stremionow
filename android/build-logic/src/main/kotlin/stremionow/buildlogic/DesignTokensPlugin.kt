// SPDX-License-Identifier: GPL-3.0-or-later
package stremionow.buildlogic

import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Generates the Compose theme and color resources from `design/tokens.json` and adds them
 * to every variant of the Android library module that applies this plugin.
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

        project.plugins.withId("com.android.library") {
            val components = project.extensions.getByType(LibraryAndroidComponentsExtension::class.java)
            components.onVariants { variant ->
                (variant.sources.kotlin ?: variant.sources.java)
                    ?.addGeneratedSourceDirectory(generate, GenerateDesignTokensTask::kotlinOutputDirectory)
                variant.sources.res?.addGeneratedSourceDirectory(generate, GenerateDesignTokensTask::resOutputDirectory)
            }
        }
    }

    private companion object {
        const val PACKAGE = "io.github.affonsopaulo.stremionow.ui.theme"
    }
}
