// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.platform.logWarning
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Updates of the Windows app that Velopack's installer installed (`StremioNow-win-Setup.exe`, in
 * `%LOCALAPPDATA%\StremioNow`). Every few hours it reads the latest release on GitHub; when it
 * has a newer version, it downloads that version's full package into Velopack's `packages`
 * folder, checks its SHA-256 against the release's list, and installs it with Velopack's
 * `Update.exe` when the app quits, or at once from Settings › About ([restartNow]).
 *
 * Releases are made with `./gradlew :app:packageVelopack :app:publishVelopack` (desktop/app).
 * Copies not installed by Velopack (`./gradlew run`, the MSI, macOS and Linux) never check.
 */
object AppUpdates {
    /** The release files, served from the latest (non-draft, non-prerelease) GitHub release. */
    private const val RELEASES = "https://github.com/AffonsoPaulo/stremionow/releases/latest/download/"
    private const val FEED = "releases.win.json"
    private const val PACKAGE_ID = "StremioNow"
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

    /** Waits for the first check a little, so it does not compete with the app's start. */
    private const val FIRST_CHECK_DELAY_MS = 30_000L

    private val ready = MutableStateFlow<String?>(null)

    /** The version downloaded and ready to install, or null while there is none. */
    val readyVersion: StateFlow<String?> = ready.asStateFlow()

    @Volatile private var staged: File? = null

    /** Set once Update.exe was started, so quitting does not start it again. */
    @Volatile private var applying = false

    private val http by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Starts checking, when this copy was installed by Velopack and knows its version; call it
     * once, after the app claimed its single instance.
     */
    fun start() {
        val install = VelopackInstall.find() ?: return
        val current = System.getProperty("stremionow.version")?.let(AppVersion::parse) ?: return
        Runtime.getRuntime().addShutdownHook(Thread({ apply(install, restart = false) }, "app-update"))
        AppGraph.scope.launch {
            delay(FIRST_CHECK_DELAY_MS)
            while (true) {
                try {
                    check(install, current)
                } catch (failure: IOException) {
                    // Offline, GitHub unreachable, or no release yet: try again later.
                    logWarning("Could not check for updates", failure)
                }
                delay(CHECK_EVERY_MS)
            }
        }
    }

    /** Installs the downloaded version now and opens the app again once it is installed. */
    fun restartNow() {
        val install = VelopackInstall.find() ?: return
        if (apply(install, restart = true)) exitProcess(0)
    }

    private fun check(
        install: VelopackInstall,
        current: AppVersion,
    ) {
        if (staged != null) return
        val feed = get(RELEASES + FEED).use { it.body.string() }
        val latest = newestFull(feed, current) ?: return
        val target = File(install.packages, latest.fileName)
        if (!(target.isFile && target.length() == latest.size && sha256(target) == latest.sha256)) {
            download(latest, target)
        }
        staged = target
        ready.value = latest.version.text
    }

    private fun download(
        asset: ReleaseAsset,
        target: File,
    ) {
        target.parentFile.mkdirs()
        val partial = File(target.parentFile, target.name + ".partial")
        try {
            get(RELEASES + asset.fileName).use { response ->
                response.body.byteStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
            }
            // A release replaced while it downloaded, or a damaged download: never installed.
            if (partial.length() != asset.size || sha256(partial) != asset.sha256) {
                throw IOException("The downloaded update does not match the release")
            }
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            partial.delete()
        }
    }

    private fun get(url: String) =
        http.newCall(Request.Builder().url(url).build()).execute().also { response ->
            if (!response.isSuccessful) {
                response.close()
                throw IOException("HTTP ${response.code}")
            }
        }

    /** Hands the staged package to Update.exe, which waits for this process to end. */
    @Synchronized
    private fun apply(
        install: VelopackInstall,
        restart: Boolean,
    ): Boolean {
        val pkg = staged ?: return false
        if (applying || !pkg.isFile) return false
        val command =
            buildList {
                add(install.updater.absolutePath)
                add("apply")
                add("--silent")
                if (!restart) add("--norestart")
                add("--waitPid")
                add(ProcessHandle.current().pid().toString())
                add("--package")
                add(pkg.absolutePath)
            }
        return try {
            ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            applying = true
            true
        } catch (failure: IOException) {
            logWarning("Could not start the update", failure)
            false
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02X".format(it) }
    }

    /**
     * The newest full package in [feed], Velopack's `releases.win.json`, when it is newer than
     * [current]; null otherwise, or when the list makes no sense.
     */
    internal fun newestFull(
        feed: String,
        current: AppVersion,
    ): ReleaseAsset? {
        val assets = runCatching { JSONObject(feed).getJSONArray("Assets") }.getOrNull() ?: return null
        return (0 until assets.length())
            .mapNotNull { index ->
                val asset = assets.optJSONObject(index) ?: return@mapNotNull null
                if (asset.optString("PackageId") != PACKAGE_ID || asset.optString("Type") != "Full") {
                    return@mapNotNull null
                }
                val fileName = asset.optString("FileName")
                // A plain file name: it is joined to the packages folder and to the release's address.
                if (!PACKAGE_FILE.matches(fileName)) return@mapNotNull null
                val sha256 = asset.optString("SHA256").uppercase()
                if (!SHA256_HEX.matches(sha256)) return@mapNotNull null
                val size = asset.optLong("Size", -1)
                if (size <= 0) return@mapNotNull null
                val version = AppVersion.parse(asset.optString("Version")) ?: return@mapNotNull null
                ReleaseAsset(version, fileName, sha256, size)
            }.filter { it.version > current }
            .maxByOrNull { it.version }
    }

    private val PACKAGE_FILE = Regex("[A-Za-z0-9._-]+\\.nupkg")
    private val SHA256_HEX = Regex("[0-9A-F]{64}")
}

/** One package of a release, from `releases.win.json`. */
internal data class ReleaseAsset(
    val version: AppVersion,
    val fileName: String,
    val sha256: String,
    val size: Long,
)

/** A version like "0.1.0"; a pre-release ("0.2.0-beta.1") comes before its release. */
internal data class AppVersion(
    val numbers: List<Int>,
    val preRelease: String?,
    val text: String,
) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        for (i in 0 until maxOf(numbers.size, other.numbers.size)) {
            val difference = numbers.getOrElse(i) { 0 } - other.numbers.getOrElse(i) { 0 }
            if (difference != 0) return difference
        }
        return when {
            preRelease == other.preRelease -> 0
            preRelease == null -> 1
            other.preRelease == null -> -1
            else -> preRelease.compareTo(other.preRelease)
        }
    }

    companion object {
        fun parse(text: String): AppVersion? {
            val core = text.substringBefore('+')
            val numbers = core.substringBefore('-').split('.').map { it.toIntOrNull() ?: return null }
            if (numbers.isEmpty() || numbers.any { it < 0 }) return null
            return AppVersion(numbers, core.substringAfter('-', "").ifEmpty { null }, text)
        }
    }
}

/**
 * Where Velopack installed this copy: the app runs from `<root>\current`, next to
 * `<root>\Update.exe` and the `<root>\packages` folder updates are kept in.
 */
private class VelopackInstall(
    val updater: File,
    val packages: File,
) {
    companion object {
        fun find(): VelopackInstall? {
            // Set by the launcher jpackage makes (StremioNow.exe); missing under `./gradlew run`.
            val exe = System.getProperty("jpackage.app-path")?.let(::File) ?: return null
            val current = exe.absoluteFile.parentFile ?: return null
            if (!current.name.equals("current", ignoreCase = true)) return null
            if (!File(current, "sq.version").isFile) return null
            val root = current.parentFile ?: return null
            val updater = File(root, "Update.exe").takeIf { it.isFile } ?: return null
            return VelopackInstall(updater, File(root, "packages"))
        }
    }
}
