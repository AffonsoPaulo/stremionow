// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.crash

import io.github.affonsopaulo.stremionow.core.InternalException
import io.github.affonsopaulo.stremionow.core.PanicDetails
import java.util.Locale

/**
 * Turns crashes into redacted reports in [store]: uncaught exceptions, core panics and the
 * exits the system recorded. Every method returns normally whatever happens, because it runs
 * while the app is crashing.
 */
internal class CrashRecorder(
    private val store: ReportStore,
    private val environment: CrashEnvironment,
    /** When the process started, for the uptime in reports. */
    private val startedAt: Long,
    /** Whether an uncaught exception ends the process here (see [uncaughtErrorsEndProcess]). */
    private val errorsEndProcess: Boolean,
    /** The app's recent warnings, oldest first. */
    private val recentLog: () -> List<String> = RecentLog::snapshot,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** The last core panic of this process, to attach to the crash it may cause. */
    @Volatile private var lastPanic: Pair<Long, StoredReport>? = null

    private val home get() = environment.homeFolder

    /** Saves a report for [error], uncaught on [thread]. */
    fun recordCrash(
        thread: Thread,
        error: Throwable,
    ) {
        try {
            val time = now()
            val kind = if (errorsEndProcess) ReportKind.Crash else ReportKind.Error
            // A panic in a core call reaches Kotlin as InternalException with only its
            // message; the panic report written a moment ago has the location, so the two
            // become one.
            val panic =
                lastPanic?.let { (panicTime, stored) ->
                    stored.takeIf {
                        time - panicTime in 0..PANIC_LINK_MS &&
                            Traces.hasCause(error, InternalException::class.java)
                    }
                }
            val report =
                try {
                    val trace = Redactor.redact(Traces.stackTrace(error), homeFolder = home)
                    newReport(
                        kind = kind,
                        time = time,
                        thread = thread.name,
                        summary = panic?.report?.summary ?: Traces.summary(error),
                        details = panic?.let { it.report.details + "\n\n" + trace } ?: trace,
                    )
                } catch (_: Throwable) {
                    // Out of memory, most likely: save what costs nothing.
                    val type = error.javaClass.name
                    newReport(kind, time, thread.name, Traces.sanitizeSummary(type), type, withLog = false)
                }
            if (store.write(report) != null && panic != null) store.delete(panic)
        } catch (_: Throwable) {
            // Never let reporting get in the way of the crash itself.
        }
    }

    /** Saves a report for a panic in the core; called on the panicking thread. */
    fun recordPanic(details: PanicDetails) {
        try {
            val time = now()
            val location = Redactor.redact(Traces.panicLocation(details.location), homeFolder = home)
            val message = Redactor.redact(Traces.safeTake(details.message, Traces.MAX_CHARS), homeFolder = home)
            val report =
                newReport(
                    kind = ReportKind.CorePanic,
                    time = time,
                    thread = details.thread,
                    summary = Traces.sanitizeSummary("Core panic at ${Traces.panicPlace(location)}"),
                    details = "Core panicked at $location:\n$message",
                )
            store.write(report)?.let { lastPanic = time to it }
        } catch (_: Throwable) {
        }
    }

    /**
     * Saves reports for [exits] the system recorded. A native crash right after a core panic in
     * [pending] is that panic aborting the process: the two become one report, and the panic's
     * own is deleted. Returns whether anything was written.
     */
    fun recordExits(
        exits: List<ProcessExit>,
        pending: List<StoredReport>,
    ): Boolean {
        var wrote = false
        for (exit in exits.sortedBy { it.time }) {
            try {
                val details = Redactor.redact(exit.details, homeFolder = home)
                val panic =
                    pending.firstOrNull {
                        exit.kind == ReportKind.NativeCrash &&
                            it.report.kind == ReportKind.CorePanic &&
                            exit.time - it.report.time in 0..PANIC_LINK_MS + MINUTE_MS
                    }
                val report =
                    newReport(
                        kind = exit.kind,
                        time = exit.time,
                        thread = exit.thread,
                        summary = panic?.report?.summary ?: Traces.sanitizeSummary(exit.summary),
                        details =
                            panic?.let { it.report.details + "\n\nThe process then ended.\n" + details } ?: details,
                        // The log in memory is this launch's, not the one that ended.
                        withLog = false,
                        withUptime = false,
                    )
                if (store.write(report) != null) {
                    wrote = true
                    if (panic != null) store.delete(panic)
                }
            } catch (_: Throwable) {
            }
        }
        return wrote
    }

    private fun newReport(
        kind: ReportKind,
        time: Long,
        thread: String,
        summary: String,
        details: String,
        withLog: Boolean = true,
        withUptime: Boolean = true,
    ) = CrashReport(
        kind = kind,
        time = time,
        app = environment.app,
        platform = environment.platform,
        device = environment.device,
        abi = environment.abi,
        locale = Locale.getDefault().toLanguageTag(),
        thread = Traces.threadName(thread, home),
        uptimeSeconds = ((time - startedAt) / 1000).takeIf { withUptime && it >= 0 },
        occurrences = 1,
        summary = summary,
        details = details,
        log = if (withLog) recentLog().map { Redactor.redact(it, homeFolder = home) } else emptyList(),
    )

    companion object {
        /** A core panic followed this soon by a crash is reported as its cause. */
        const val PANIC_LINK_MS = 30_000L

        /** Saved reports keep their time to the minute, so a panic may seem up to this much earlier. */
        private const val MINUTE_MS = 60_000L
    }
}
