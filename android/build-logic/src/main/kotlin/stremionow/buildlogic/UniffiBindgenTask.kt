// SPDX-License-Identifier: GPL-3.0-or-later
package stremionow.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Generates the Kotlin bindings of a UniFFI crate.
 *
 * The bindings are read from a debug build for the machine running Gradle: release builds
 * strip the metadata UniFFI needs, and the exported API is the same on every target.
 */
@CacheableTask
abstract class UniffiBindgenTask : DefaultTask() {
    /** Root of the Cargo workspace. Its contents are tracked through [sources]. */
    @get:Internal
    abstract val workspaceDirectory: DirectoryProperty

    /** Workspace sources whose changes trigger a regeneration. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** Cargo package that exports the API, for example `stremionow-ffi`. */
    @get:Input
    abstract val crateName: Property<String>

    /** Library name of that package, for example `stremionow_ffi`. */
    @get:Input
    abstract val libraryName: Property<String>

    /** Receives the generated Kotlin sources. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    protected abstract val execOperations: ExecOperations

    @TaskAction
    fun generate() {
        val workspace = workspaceDirectory.get().asFile
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()

        cargo(workspace, listOf("build", "--package", crateName.get()))

        val library = File(workspace, "target/debug/${hostLibraryFileName(libraryName.get())}")
        cargo(
            workspace,
            listOf(
                "run",
                "--quiet",
                "--package",
                "uniffi-bindgen",
                "--",
                "generate",
                "--library",
                library.absolutePath,
                "--language",
                "kotlin",
                "--out-dir",
                output.absolutePath,
                "--no-format",
            ),
        )
    }

    private fun cargo(
        workspace: File,
        arguments: List<String>,
    ) {
        execOperations.exec {
            workingDir = workspace
            executable = cargoExecutable()
            args = arguments
            environment("PATH", pathWithCargo())
        }
    }
}
