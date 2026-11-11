// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.crash

/** Turning errors into report text: stack traces, summaries, and shorter versions of both. */
internal object Traces {
    /** Lines and characters a report's details may take on disk. */
    const val MAX_LINES = 1_500
    const val MAX_CHARS = 64 * 1024

    /** Packages of the app's own code, preferred when naming where an error happened. */
    private const val APP_PACKAGE = "io.github.affonsopaulo.stremionow"

    /** The stack trace of [error] with its causes, repeated frames folded and the size capped. */
    fun stackTrace(error: Throwable): String {
        // A deep recursion prints thousands of identical frames: cap the raw text first.
        val raw = error.stackTraceToString().take(MAX_CHARS * 4)
        return cap(collapseRepeats(raw.lines()))
    }

    /** Joins [lines], keeping at most [MAX_LINES] lines and [MAX_CHARS] characters. */
    fun cap(lines: List<String>): String {
        val kept =
            if (lines.size >
                MAX_LINES
            ) {
                lines.take(MAX_LINES) + "\t... (${lines.size - MAX_LINES} more lines)"
            } else {
                lines
            }
        return safeTake(kept.joinToString("\n").trimEnd(), MAX_CHARS)
    }

    /**
     * Folds runs of a block of up to [maxPeriod] lines repeated three times or more, such as the
     * frames of a recursion, into one copy and a note.
     */
    fun collapseRepeats(
        lines: List<String>,
        maxPeriod: Int = 8,
    ): List<String> {
        val result = ArrayList<String>(minOf(lines.size, MAX_LINES * 2))
        var index = 0
        while (index < lines.size) {
            var folded = false
            for (period in 1..maxPeriod) {
                if (index + period * 3 > lines.size) break
                var repeats = 1
                while (index + (repeats + 1) * period <= lines.size &&
                    (0 until period).all { lines[index + it] == lines[index + repeats * period + it] }
                ) {
                    repeats++
                }
                if (repeats >= 3) {
                    result.addAll(lines.subList(index, index + period))
                    val what = if (period == 1) "line" else "$period lines"
                    result.add("\t... (the $what above repeated ${repeats - 1} more times)")
                    index += repeats * period
                    folded = true
                    break
                }
            }
            if (!folded) result.add(lines[index++])
        }
        return result
    }

    /**
     * Shortens a stack trace to the first [frames] frames of each exception and cause, and cuts
     * long messages. The exception lines stay, so the chain of causes is still readable.
     */
    fun compact(
        trace: String,
        frames: Int,
    ): String {
        val result = mutableListOf<String>()
        var kept = 0
        var skipped = 0

        fun flush() {
            if (skipped > 0) result.add("\t... ($skipped frames left out)")
            skipped = 0
        }
        for (line in trace.lines()) {
            val trimmed = line.trimStart()
            val frame = trimmed.startsWith("at ") || trimmed.startsWith("... ") || trimmed.startsWith("native: ")
            if (frame) {
                if (kept < frames) {
                    result.add(line)
                    kept++
                } else {
                    skipped++
                }
            } else {
                flush()
                kept = 0
                result.add(safeTake(line, MAX_MESSAGE))
            }
        }
        flush()
        return result.joinToString("\n")
    }

    private const val MAX_MESSAGE = 300

    /**
     * One line naming the error and the place it happened: the root cause's class and the
     * first frame in the app's code (or the first frame at all). Messages are left out; they
     * can carry anything.
     */
    fun summary(error: Throwable): String {
        val root = rootCause(error)
        val frame =
            root.stackTrace.firstOrNull { it.className.startsWith(APP_PACKAGE) }
                ?: root.stackTrace.firstOrNull()
        val type = root.javaClass.name.substringAfterLast('.')
        val place = frame?.let { " at ${it.className.substringAfterLast('.')}.${it.methodName}" }.orEmpty()
        return sanitizeSummary("$type$place")
    }

    /** The innermost cause of [error], following at most a few links and never a cycle. */
    fun rootCause(error: Throwable): Throwable {
        var current = error
        val seen = mutableSetOf<Throwable>()
        while (seen.add(current) && seen.size < 32) current = current.cause ?: break
        return current
    }

    /** Whether [error] or one of its causes is an instance of [type]. */
    fun hasCause(
        error: Throwable,
        type: Class<*>,
    ): Boolean {
        var current: Throwable? = error
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current) && seen.size < 32) {
            if (type.isInstance(current)) return true
            current = current.cause
        }
        return false
    }

    /**
     * Keeps only characters that are safe and meaningful in an issue title: letters, digits and
     * the punctuation of class and method names. Anything else, such as text from a damaged
     * report, is dropped.
     */
    fun sanitizeSummary(summary: String): String =
        summary
            .filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in " _.$:()<>-" }
            .replace(Regex(" {2,}"), " ")
            .trim()
            .take(MAX_SUMMARY)
            .ifEmpty { "unknown error" }

    private const val MAX_SUMMARY = 120

    /**
     * A thread name fit for a report. Names can carry data (OkHttp names its threads after the
     * address they fetch), so they are redacted and cut like the rest.
     */
    fun threadName(
        name: String,
        homeFolder: String?,
    ): String =
        Redactor
            .redact(name, homeFolder = homeFolder)
            .filter { it.isLetterOrDigit() || it in " _-.:/#()[]@" }
            .take(MAX_THREAD_NAME)

    private const val MAX_THREAD_NAME = 80

    /** The file name and line of a Rust panic location such as `/a/b/src/core.rs:12:5`. */
    fun panicPlace(location: String): String {
        val file = location.substringAfterLast('/').substringAfterLast('\\')
        val parts = file.split(':')
        return if (parts.size >= 2) "${parts[0]}:${parts[1]}" else file
    }

    /**
     * A panic location with the build machine's folders removed: dependencies keep only their
     * crate and path (`tokio-1.54.1/src/…`), and the core's own files their workspace path.
     */
    fun panicLocation(location: String): String =
        location
            .replace(Regex("""^.*[\\/]registry[\\/]src[\\/][^\\/]+[\\/]"""), "")
            .replace(Regex("""^.*[\\/](crates[\\/])"""), "$1")

    /** The first [limit] characters of [text], never splitting a surrogate pair. */
    fun safeTake(
        text: String,
        limit: Int,
    ): String {
        if (text.length <= limit) return text
        val end = if (limit > 0 && text[limit - 1].isHighSurrogate()) limit - 1 else limit
        return text.substring(0, end.coerceAtLeast(0))
    }
}
