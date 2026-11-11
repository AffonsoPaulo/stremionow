// SPDX-License-Identifier: GPL-3.0-or-later
package stremionow.desktop.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Extracts the named entries of a 7z archive into [outputDirectory] with libarchive's `bsdtar`.
 *
 * The libmpv archives use the BCJ2 filter, which Apache Commons Compress cannot read. Windows 10
 * (1803) and later ship `bsdtar` as `%SystemRoot%\System32\tar.exe`, and it reads these archives,
 * so the build needs no 7-Zip install. The path is explicit because Git Bash puts GNU tar, which
 * has no 7z support, first on the PATH. Only the listed entries are written.
 */
@CacheableTask
abstract class Extract7zTask : DefaultTask() {
    /** The 7z archive. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archive: RegularFileProperty

    /** Paths of the entries to extract, as stored in the archive, for example `libmpv-2.dll`. */
    @get:Input
    abstract val entries: SetProperty<String>

    /** The `bsdtar` executable. Defaults to the one that ships with Windows. */
    @get:Internal
    abstract val tarExecutable: Property<String>

    /** Receives the extracted files. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    protected abstract val execOperations: ExecOperations

    init {
        tarExecutable.convention(defaultTar())
    }

    @TaskAction
    fun extract() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()

        val wanted = entries.get().sorted()
        execOperations.exec {
            executable = tarExecutable.get()
            args = listOf("-x", "-f", archive.get().asFile.absolutePath, "-C", output.absolutePath) + wanted
        }
        val missing = wanted.filterNot { File(output, it).isFile }
        if (missing.isNotEmpty()) {
            throw GradleException("${archive.get().asFile.name} has no entries named $missing")
        }
    }

    private companion object {
        fun defaultTar(): String {
            val systemRoot = System.getenv("SystemRoot")
            val windowsTar = systemRoot?.let { File(it, "System32/tar.exe") }
            return if (windowsTar?.isFile == true) windowsTar.absolutePath else "bsdtar"
        }
    }
}
