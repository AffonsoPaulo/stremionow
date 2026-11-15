// SPDX-License-Identifier: GPL-3.0-or-later
package stremionow.desktop.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.zip.ZipFile

/**
 * Copies one entry of a jar (for example JNA's `com/sun/jna/win32-x86-64/jnidispatch.dll`) into
 * [outputDirectory], under its file name alone.
 */
@CacheableTask
abstract class ExtractJarEntryTask : DefaultTask() {
    /** The jar; exactly one file. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jar: ConfigurableFileCollection

    /** The entry's path inside the jar. */
    @get:Input
    abstract val entry: Property<String>

    /** Receives the file. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun extract() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        val source =
            jar.files.singleOrNull()
                ?: throw GradleException("Expected one jar to extract from, found ${jar.files.map(File::getName)}")
        val path = entry.get()
        ZipFile(source).use { zip ->
            val found = zip.getEntry(path) ?: throw GradleException("${source.name} has no entry $path")
            zip.getInputStream(found).use { input ->
                File(output, path.substringAfterLast('/')).outputStream().use(input::copyTo)
            }
        }
    }
}
