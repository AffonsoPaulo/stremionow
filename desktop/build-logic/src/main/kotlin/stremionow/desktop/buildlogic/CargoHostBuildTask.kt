// SPDX-License-Identifier: GPL-3.0-or-later
package stremionow.desktop.buildlogic

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
 * Builds a crate of the Rust workspace as a release shared library for the machine running
 * Gradle and copies it into [outputDirectory].
 */
@CacheableTask
abstract class CargoHostBuildTask : DefaultTask() {
    /** Root of the Cargo workspace. Its contents are tracked through [sources]. */
    @get:Internal
    abstract val workspaceDirectory: DirectoryProperty

    /** Workspace sources whose changes trigger a rebuild. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** Cargo package to build, for example `stremionow-ffi`. */
    @get:Input
    abstract val crateName: Property<String>

    /** Library name of that package, for example `stremionow_ffi`. */
    @get:Input
    abstract val libraryName: Property<String>

    /** Receives the shared library. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    protected abstract val execOperations: ExecOperations

    @TaskAction
    fun build() {
        val workspace = workspaceDirectory.get().asFile
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()

        execOperations.exec {
            workingDir = workspace
            executable = cargoExecutable()
            args = listOf("build", "--release", "--package", crateName.get())
            environment("PATH", pathWithCargo())
        }

        val fileName = hostLibraryFileName(libraryName.get())
        File(workspace, "target/release/$fileName").copyTo(File(output, fileName))
    }
}
