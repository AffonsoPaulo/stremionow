// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.crash

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** What a report is about. [closedApp] tells whether the app was closed by it. */
internal enum class ReportKind(
    val id: String,
    val closedApp: Boolean,
) {
    /** An uncaught exception, which ends the process on Android. */
    Crash("crash", closedApp = true),

    /** An uncaught exception where the process may go on, as on the desktop. */
    Error("error", closedApp = false),

    /** A panic in the Rust core that did not end the process. */
    CorePanic("core-panic", closedApp = false),

    /** The process died in native code, found on the next launch (Android 11 and later). */
    NativeCrash("native-crash", closedApp = true),

    /** The system closed the app because it stopped responding (Android 11 and later). */
    NotResponding("anr", closedApp = true),
    ;

    companion object {
        fun of(id: String): ReportKind = entries.firstOrNull { it.id == id } ?: Crash
    }
}

/**
 * A crash report as stored on the device and sent. Every field is already redacted: the
 * header holds only values the app builds itself, and [details] and [log] went through
 * [Redactor]. The text form ([format]) is plain English, for developers, whatever the
 * interface language.
 */
internal data class CrashReport(
    val kind: ReportKind,
    /** When it happened, in milliseconds since the epoch. */
    val time: Long,
    /** App version, with "(debug)" for debug builds. */
    val app: String,
    /** Operating system and version, such as "Android 16 (API 36)". */
    val platform: String,
    /** Device class: phone, tablet, tv, fire-tv or desktop. */
    val device: String,
    val abi: String,
    /** Interface locale as a BCP 47 tag, such as "pt-BR". */
    val locale: String,
    val thread: String,
    /** Seconds between the app's start and the crash, when known. */
    val uptimeSeconds: Long?,
    /** How many times the same crash happened before it was sent or dismissed. */
    val occurrences: Int,
    /** One line naming the error and where it happened, built from class and method names only. */
    val summary: String,
    /** Stack trace, panic message or system description. */
    val details: String,
    /** Recent warnings, oldest first. */
    val log: List<String>,
) {
    fun format(): String =
        buildString {
            append(TITLE).append('\n')
            header("Format", FORMAT.toString())
            header("Kind", kind.id)
            header("Time", TIME_FORMAT.format(Instant.ofEpochMilli(time).truncatedTo(ChronoUnit.MINUTES)))
            header("App", app)
            header("Platform", platform)
            header("Device", device)
            header("ABI", abi)
            header("Locale", locale)
            if (thread.isNotEmpty()) header("Thread", thread)
            if (uptimeSeconds != null) header("Uptime", "$uptimeSeconds s")
            if (occurrences > 1) header("Occurrences", occurrences.toString())
            header("Summary", summary)
            append('\n').append(DETAILS_MARKER).append('\n')
            append(details.trimEnd()).append('\n')
            if (log.isNotEmpty()) {
                append('\n').append(LOG_MARKER).append('\n')
                log.forEach { append(it).append('\n') }
            }
        }

    private fun StringBuilder.header(
        name: String,
        value: String,
    ) {
        append(name).append(": ").append(value.replace('\n', ' ')).append('\n')
    }

    /** The same report with [details] and [log] redacted again, for reports saved by older versions. */
    fun redactedAgain(homeFolder: String?): CrashReport =
        copy(
            summary = Traces.sanitizeSummary(summary),
            details = Redactor.redact(details, homeFolder = homeFolder),
            log = log.map { Redactor.redact(it, homeFolder = homeFolder) },
        )

    companion object {
        const val FORMAT = 1
        const val TITLE = "StremioNow crash report"
        const val DETAILS_MARKER = "--- Details ---"
        const val LOG_MARKER = "--- Recent log ---"

        private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

        /**
         * Reads a report back. Tolerates reports from other versions and damaged files: unknown
         * header lines are ignored, missing ones take neutral values, and text that is not a
         * report at all becomes the details of one. [fallbackTime] is used when the time is
         * missing (the file's modification time).
         */
        fun parse(
            text: String,
            fallbackTime: Long,
        ): CrashReport {
            val lines = text.replace("\r\n", "\n").split('\n')
            val headers = mutableMapOf<String, String>()
            var index = 0
            if (lines.firstOrNull() == TITLE) index = 1
            while (index < lines.size && lines[index].isNotEmpty() && lines[index] != DETAILS_MARKER) {
                val line = lines[index]
                val colon = line.indexOf(": ")
                if (colon <= 0) break
                headers[line.substring(0, colon)] = line.substring(colon + 2)
                index++
            }
            val rest = lines.drop(index).dropWhile { it.isEmpty() }
            val body = if (rest.firstOrNull() == DETAILS_MARKER) rest.drop(1) else rest
            val logStart = body.indexOf(LOG_MARKER)
            val details = (if (logStart >= 0) body.take(logStart) else body).joinToString("\n").trim()
            val log = if (logStart >= 0) body.drop(logStart + 1).filter { it.isNotBlank() } else emptyList()
            return CrashReport(
                kind = ReportKind.of(headers["Kind"].orEmpty()),
                time = headers["Time"]?.let(::parseTime) ?: fallbackTime,
                app = headers["App"] ?: UNKNOWN,
                platform = headers["Platform"] ?: UNKNOWN,
                device = headers["Device"] ?: UNKNOWN,
                abi = headers["ABI"] ?: UNKNOWN,
                locale = headers["Locale"] ?: UNKNOWN,
                thread = headers["Thread"].orEmpty(),
                uptimeSeconds = headers["Uptime"]?.removeSuffix(" s")?.toLongOrNull(),
                occurrences = headers["Occurrences"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                summary = headers["Summary"] ?: UNKNOWN,
                details = details,
                log = log,
            )
        }

        private fun parseTime(value: String): Long? =
            runCatching {
                java.time.ZonedDateTime
                    .parse(value, TIME_FORMAT)
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()

        private const val UNKNOWN = "unknown"
    }
}
