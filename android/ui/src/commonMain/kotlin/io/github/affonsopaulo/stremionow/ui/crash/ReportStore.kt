// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.crash

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** A report saved on the device, and the file it lives in. */
internal class StoredReport(
    val file: File,
    val report: CrashReport,
)

/**
 * The folder that holds crash reports and the user's choice about them. Reports are small
 * text files named `report-<time>-<signature>.txt`, where the signature tells crashes apart.
 */
internal class ReportStore(
    private val folder: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val writing = ReentrantLock()

    /**
     * Saves [report]. The same crash again (same kind and summary) replaces the earlier report
     * and counts the occurrences, so a crash loop leaves one report, not dozens. The text goes
     * to a temporary file first and is renamed into place, so a crash or a full disk mid-write
     * leaves no half report; [load] removes the temporary files left behind. Returns null when
     * nothing could be written. Never throws.
     */
    fun write(report: CrashReport): StoredReport? {
        // A second crashing thread waits a little for the first, then gives up.
        if (!writing.tryLock(WRITE_WAIT_MS, TimeUnit.MILLISECONDS)) return null
        var temporary: File? = null
        try {
            folder.mkdirs()
            val signature = Integer.toHexString((report.kind.id + report.summary).hashCode())
            val earlier = reports().filter { it.name.endsWith("-$signature$REPORT_SUFFIX") }
            val before = earlier.sumOf { file -> runCatching { read(file).occurrences }.getOrDefault(1) }
            val final = report.copy(occurrences = before + 1)
            val target = File(folder, "$REPORT_PREFIX${report.time}-$signature$REPORT_SUFFIX")
            temporary = File(folder, target.name + TEMPORARY_SUFFIX)
            FileOutputStream(temporary).use { stream ->
                stream.write(final.format().encodeToByteArray())
                stream.fd.sync()
            }
            if (target.exists()) target.delete()
            if (!temporary.renameTo(target)) return null
            temporary = null
            earlier.filter { it != target }.forEach(File::delete)
            prune()
            return StoredReport(target, final)
        } catch (_: Throwable) {
            return null
        } finally {
            temporary?.delete()
            writing.unlock()
        }
    }

    /**
     * The saved reports, newest first, redacted again with today's rules. Cleans up on the way:
     * temporary files, reports older than [MAX_AGE_MS], reports beyond [MAX_REPORTS] and files
     * that cannot be read.
     */
    fun load(homeFolder: String?): List<StoredReport> {
        folder.listFiles()?.filter { it.name.endsWith(TEMPORARY_SUFFIX) }?.forEach(File::delete)
        prune()
        val current = now()
        return reports()
            .mapNotNull { file ->
                if (current - file.reportTime() > MAX_AGE_MS) {
                    file.delete()
                    return@mapNotNull null
                }
                runCatching { StoredReport(file, read(file).redactedAgain(homeFolder)) }.getOrElse {
                    // Nothing to show, so nothing to keep.
                    file.delete()
                    null
                }
                // The file name has the time to the millisecond; the report only to the minute.
            }.sortedByDescending { it.file.reportTime() }
    }

    fun delete(report: StoredReport) {
        report.file.delete()
    }

    fun deleteAll() {
        folder.listFiles()?.filter { it.isReport() || it.name.endsWith(TEMPORARY_SUFFIX) }?.forEach(File::delete)
    }

    /** The user's choice; [CrashReports.Mode.Ask] until they make one. */
    fun readMode(): CrashReports.Mode =
        runCatching { File(folder, MODE_FILE).readText().trim() }
            .getOrNull()
            ?.let { saved -> CrashReports.Mode.entries.firstOrNull { it.name.equals(saved, ignoreCase = true) } }
            ?: CrashReports.Mode.Ask

    fun writeMode(mode: CrashReports.Mode) {
        runCatching {
            folder.mkdirs()
            File(folder, MODE_FILE).writeText(mode.name.lowercase())
        }
    }

    /** Time of the newest process exit already looked at, or null before the first look. */
    fun readExitsSeen(): Long? = runCatching { File(folder, EXITS_FILE).readText().trim().toLong() }.getOrNull()

    fun writeExitsSeen(time: Long) {
        runCatching {
            folder.mkdirs()
            File(folder, EXITS_FILE).writeText(time.toString())
        }
    }

    /**
     * How many launches in a row of app version [version] closed while the crash question was
     * on screen, before it had been there long enough to count as working.
     */
    fun readPromptFailures(version: String): Int =
        runCatching {
            val (count, savedVersion) = File(folder, PROMPT_FILE).readText().split(' ', limit = 2)
            if (savedVersion.trim() == version) count.toInt() else 0
        }.getOrDefault(0)

    fun writePromptFailures(
        count: Int,
        version: String,
    ) {
        runCatching {
            folder.mkdirs()
            File(folder, PROMPT_FILE).writeText("$count $version")
        }
    }

    private fun reports(): List<File> = folder.listFiles()?.filter { it.isReport() }.orEmpty()

    /** Keeps the newest [MAX_REPORTS] reports. */
    private fun prune() {
        reports().sortedByDescending { it.reportTime() }.drop(MAX_REPORTS).forEach(File::delete)
    }

    private fun read(file: File): CrashReport {
        // Reports are small; a huge file is not one of ours.
        require(file.length() <= MAX_FILE_BYTES)
        return CrashReport.parse(file.readBytes().decodeToString(), fallbackTime = file.reportTime())
    }

    private fun File.isReport() = name.startsWith(REPORT_PREFIX) && name.endsWith(REPORT_SUFFIX)

    private fun File.reportTime(): Long =
        name.removePrefix(REPORT_PREFIX).substringBefore('-').toLongOrNull() ?: lastModified()

    companion object {
        const val MAX_REPORTS = 5
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_FILE_BYTES = 512 * 1024L
        private const val REPORT_PREFIX = "report-"
        private const val REPORT_SUFFIX = ".txt"
        private const val TEMPORARY_SUFFIX = ".tmp"
        private const val MODE_FILE = "mode"
        private const val EXITS_FILE = "exits-seen"
        private const val PROMPT_FILE = "prompt-failures"
        private const val WRITE_WAIT_MS = 2_000L
    }
}
