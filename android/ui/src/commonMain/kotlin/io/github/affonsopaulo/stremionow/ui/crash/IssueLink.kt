// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.crash

/**
 * A link that opens GitHub's new-issue form with the crash template filled in from a report.
 * The project has no server: the user reads the issue before submitting it, from their own
 * GitHub account. [shortened] tells whether the report had to be cut to fit in the link.
 */
internal data class IssueLink(
    val url: String,
    val shortened: Boolean,
) {
    companion object {
        /** The repository's new-issue page. Only links to it are ever built or opened. */
        const val NEW_ISSUE = "https://github.com/AffonsoPaulo/stremionow/issues/new"

        /** GitHub refuses longer links; browsers and intents carry this much without trouble. */
        const val BROWSER_BUDGET = 8_000

        /**
         * For a QR code on a TV: about 1,200 bytes still makes a code a phone reads from the
         * couch (version 26 or so, at the lowest error correction).
         */
        const val QR_BUDGET = 1_200

        /** Note at the end of a report cut to fit. */
        const val SHORTENED_NOTE =
            "[This report was shortened to fit in a link. The full report is in the app: " +
                "View report, then Copy report.]"

        /** Whether [url] is a link this class builds: the new-issue page, over HTTPS. */
        fun isIssueLink(url: String): Boolean = url.startsWith("$NEW_ISSUE?")

        /** The issue title: the summary and version only, never a message or a path. */
        fun title(report: CrashReport): String {
            val version =
                report.app.filter { (it.isLetterOrDigit() && it.code < 128) || it in ".-+() " }.trim().take(
                    40,
                )
            return "Crash: ${Traces.sanitizeSummary(report.summary)} ($version)"
        }

        /**
         * Builds the link for [report] within [budget] characters, dropping detail step by
         * step until it fits: the log first, then stack frames, then, as a last resort, the end
         * of the details.
         */
        fun build(
            report: CrashReport,
            budget: Int,
        ): IssueLink {
            val full = report.format()
            link(report, full).let { if (it.length <= budget) return IssueLink(it, shortened = false) }

            val candidates =
                sequenceOf(
                    report.copy(log = report.log.takeLast(SHORT_LOG)),
                    report.copy(log = emptyList()),
                ) +
                    FRAME_STEPS.asSequence().map { frames ->
                        report.copy(log = emptyList(), details = Traces.compact(report.details, frames))
                    }
            for (candidate in candidates) {
                val url = link(report, shortenedText(candidate))
                if (url.length <= budget) return IssueLink(url, shortened = true)
            }

            // Still too long: keep as much of the most compact details as fits.
            val compact = Traces.compact(report.details, FRAME_STEPS.last())
            var low = 0
            var high = compact.length
            while (low < high) {
                val middle = (low + high + 1) / 2
                val url =
                    link(
                        report,
                        shortenedText(report.copy(log = emptyList(), details = Traces.safeTake(compact, middle))),
                    )
                if (url.length <= budget) low = middle else high = middle - 1
            }
            val text = shortenedText(report.copy(log = emptyList(), details = Traces.safeTake(compact, low)))
            return IssueLink(link(report, text), shortened = true)
        }

        private const val SHORT_LOG = 15
        private val FRAME_STEPS = listOf(12, 6, 3, 1)

        private fun shortenedText(report: CrashReport) = report.format() + "\n" + SHORTENED_NOTE + "\n"

        private fun link(
            report: CrashReport,
            text: String,
        ): String =
            buildString {
                append(NEW_ISSUE).append('?')
                // Issue forms ignore `body`; each field is filled by its id (.github/ISSUE_TEMPLATE/crash.yml).
                parameter("template", "crash.yml")
                append('&')
                parameter("title", title(report))
                append('&')
                parameter("version", report.app)
                append('&')
                parameter("platform", "${report.platform}, ${report.device}, ${report.abi}")
                append('&')
                parameter("report", text)
            }

        private fun StringBuilder.parameter(
            name: String,
            value: String,
        ) {
            append(name).append('=')
            appendFormEncoded(value)
        }

        /**
         * Form encoding of UTF-8 text: unreserved characters stay, spaces become `+`, and every
         * other byte (`&`, `#`, `=`, `+`, line breaks, anything non-ASCII) becomes `%XX`, so no
         * value can add a parameter or end the query.
         */
        internal fun StringBuilder.appendFormEncoded(value: String) {
            for (byte in value.encodeToByteArray()) {
                val code = byte.toInt() and 0xFF
                val char = code.toChar()
                when {
                    char == ' ' -> append('+')
                    code < 0x80 && (char.isLetterOrDigit() || char in "-_.~") -> append(char)
                    else -> append('%').append(HEX[code shr 4]).append(HEX[code and 0xF])
                }
            }
        }

        private const val HEX = "0123456789ABCDEF"
    }
}
