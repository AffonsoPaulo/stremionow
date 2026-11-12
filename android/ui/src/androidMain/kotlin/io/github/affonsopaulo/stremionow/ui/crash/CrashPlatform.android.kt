// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import io.github.affonsopaulo.stremionow.ui.platform.PlatformContext
import io.github.affonsopaulo.stremionow.ui.platform.appVersion
import java.io.File

internal actual fun crashEnvironment(
    context: PlatformContext,
    isTv: Boolean,
): CrashEnvironment {
    val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    val version = appVersion(context) ?: "unknown"
    val device =
        when {
            isTv && context.packageManager.hasSystemFeature("amazon.hardware.fire_tv") -> "fire-tv"
            isTv -> "tv"
            context.resources.configuration.smallestScreenWidthDp >= 600 -> "tablet"
            else -> "phone"
        }
    // The ABI this process runs, which picks the core library that was loaded.
    val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
    return CrashEnvironment(
        app = if (debug) "$version (debug)" else version,
        platform = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        device = device,
        abi = abis.firstOrNull() ?: Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
        // App paths on Android hold the package name, not the user's.
        homeFolder = null,
        debug = debug,
    )
}

internal actual fun crashReportsDirectory(context: PlatformContext): File =
    File(context.applicationContext.noBackupFilesDir, "crash-reports")

/** Android's default handler kills the process after an uncaught exception. */
internal actual val uncaughtErrorsEndProcess: Boolean = true

internal actual fun processExitsSince(
    context: PlatformContext,
    since: Long,
): List<ProcessExit> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
    return ExitReasons.since(context, since)
}

/**
 * Reads ApplicationExitInfo. Only native crashes and "not responding" exits become reports:
 * uncaught exceptions already wrote theirs, and the system ending the app for memory, an
 * update or the user's swipe is not a crash.
 */
@RequiresApi(Build.VERSION_CODES.R)
private object ExitReasons {
    /** Exits the system is asked for; it keeps only a few per app anyway. */
    private const val MAX_EXITS = 16

    /** Lines of the main thread's stack kept from an ANR trace. */
    private const val MAX_TRACE_LINES = 60

    /** Lines of an ANR trace read at most while looking for the main thread. */
    private const val MAX_SCAN_LINES = 20_000

    fun since(
        context: PlatformContext,
        since: Long,
    ): List<ProcessExit> {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return manager
            .getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS)
            .filter { it.timestamp > since && it.processName == context.packageName }
            .mapNotNull { exit ->
                when (exit.reason) {
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> {
                        ProcessExit(
                            kind = ReportKind.NativeCrash,
                            time = exit.timestamp,
                            summary = "Native crash (signal ${exit.status})",
                            details = describe(exit, "The process ended in native code with signal ${exit.status}."),
                            thread = "",
                        )
                    }

                    ApplicationExitInfo.REASON_ANR -> {
                        val trace = mainThreadTrace(exit)
                        ProcessExit(
                            kind = ReportKind.NotResponding,
                            time = exit.timestamp,
                            summary = "Not responding" + (trace?.let(::topFrame)?.let { " at $it" }.orEmpty()),
                            details =
                                describe(exit, "The system closed the app because it stopped responding.") +
                                    (trace?.let { "\n\nMain thread:\n$it" }.orEmpty()),
                            thread = "main",
                        )
                    }

                    else -> {
                        null
                    }
                }
            }
    }

    private fun describe(
        exit: ApplicationExitInfo,
        what: String,
    ): String =
        buildString {
            append(what)
            exit.description?.takeIf { it.isNotBlank() }?.let { append("\nSystem description: ").append(it.take(500)) }
            append("\nIn the foreground: ")
            append(if (exit.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) "yes" else "no")
        }

    /** The main thread's block of an ANR trace (`"main" prio=5 tid=1 …` up to the blank line). */
    private fun mainThreadTrace(exit: ApplicationExitInfo): String? =
        runCatching {
            exit.traceInputStream?.bufferedReader()?.use { reader ->
                val lines = mutableListOf<String>()
                var inMain = false
                var scanned = 0
                while (scanned++ < MAX_SCAN_LINES) {
                    val line = reader.readLine() ?: break
                    if (!inMain) {
                        inMain = line.startsWith("\"main\"")
                        if (inMain) lines.add(line)
                    } else if (line.isBlank() || lines.size >= MAX_TRACE_LINES) {
                        break
                    } else {
                        lines.add(line)
                    }
                }
                lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
            }
        }.getOrNull()

    /** `Class.method` of the first app frame of a trace, or of its first frame. */
    private fun topFrame(trace: String): String? {
        val frames =
            trace
                .lines()
                .map(String::trim)
                .filter { it.startsWith("at ") }
                .map { it.removePrefix("at ") }
        val frame =
            frames.firstOrNull { it.startsWith("io.github.affonsopaulo.stremionow") } ?: frames.firstOrNull()
                ?: return null
        val qualified = frame.substringBefore('(')
        return "${qualified.substringBeforeLast('.').substringAfterLast('.')}.${qualified.substringAfterLast('.')}"
    }
}

/** Android reports an app as not responding when input waits five seconds; this waits longer. */
internal actual fun freezeForTest() {
    Thread.sleep(20_000)
}

internal actual val testCrashKinds: List<TestCrashKind> = TestCrashKind.entries
