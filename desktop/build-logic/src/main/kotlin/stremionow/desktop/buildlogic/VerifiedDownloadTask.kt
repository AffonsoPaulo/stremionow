// SPDX-License-Identifier: GPL-3.0-or-later
package stremionow.desktop.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration

/**
 * Downloads [url] into [outputFile] and fails unless its SHA-256 equals [sha256].
 *
 * A file already in place with the right checksum is kept, so the download happens once per
 * clean build directory. The file only replaces the target after it has been verified.
 */
@DisableCachingByDefault(because = "Downloading the file is as fast as restoring it from the build cache")
abstract class VerifiedDownloadTask : DefaultTask() {
    /** Address of the file. */
    @get:Input
    abstract val url: Property<String>

    /** Expected SHA-256 of the file, in hexadecimal. */
    @get:Input
    abstract val sha256: Property<String>

    /** Where the verified file is written. */
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun download() {
        val target = outputFile.get().asFile
        val expected = sha256.get().lowercase()
        if (target.isFile && sha256Of(target) == expected) return

        target.parentFile.mkdirs()
        val partial = File(target.parentFile, "${target.name}.part")
        val client =
            HttpClient
                .newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .build()
        val request =
            HttpRequest
                .newBuilder(URI(url.get()))
                .timeout(Duration.ofMinutes(DOWNLOAD_TIMEOUT_MINUTES))
                .GET()
                .build()
        logger.lifecycle("Downloading ${url.get()}")
        val response = client.send(request, HttpResponse.BodyHandlers.ofFile(partial.toPath()))
        if (response.statusCode() != HTTP_OK) {
            partial.delete()
            throw GradleException("Could not download ${url.get()}: HTTP ${response.statusCode()}")
        }

        val actual = sha256Of(partial)
        if (actual != expected) {
            partial.delete()
            throw GradleException("SHA-256 mismatch for ${url.get()}: expected $expected, got $actual")
        }
        Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val HTTP_OK = 200
        const val CONNECT_TIMEOUT_SECONDS = 30L
        const val DOWNLOAD_TIMEOUT_MINUTES = 15L
        const val BUFFER_SIZE = 1 shl 16
    }
}
