// SPDX-License-Identifier: GPL-3.0-or-later
package stremionow.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
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
import javax.inject.Inject

/** Cross-compiles a crate of the Rust workspace into a shared library for each Android ABI. */
@CacheableTask
abstract class CargoNdkBuildTask : DefaultTask() {
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

    /** Android ABIs to build for, for example `arm64-v8a`. */
    @get:Input
    abstract val abis: ListProperty<String>

    /** NDK used by cargo-ndk. */
    @get:Internal
    abstract val ndkDirectory: DirectoryProperty

    /** Receives one folder per ABI, laid out as Android expects for `jniLibs`. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    protected abstract val execOperations: ExecOperations

    @TaskAction
    fun build() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()

        val arguments = mutableListOf("ndk")
        for (abi in abis.get()) {
            arguments += listOf("--target", abi)
        }
        arguments += listOf("--output-dir", output.absolutePath, "build", "--release", "--package", crateName.get())

        execOperations.exec {
            workingDir = workspaceDirectory.get().asFile
            executable = cargoExecutable()
            args = arguments
            environment("PATH", pathWithCargo())
            environment("ANDROID_NDK_HOME", ndkDirectory.get().asFile.absolutePath)
        }
    }
}
