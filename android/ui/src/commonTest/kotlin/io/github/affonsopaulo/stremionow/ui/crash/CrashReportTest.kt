// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun sampleReport(
    details: String = "java.lang.IllegalStateException: boom\n\tat a.B.c(B.kt:1)",
    log: List<String> = listOf("12:00:00 W first", "12:00:01 W second"),
) = CrashReport(
    kind = ReportKind.Crash,
    time = 1_762_862_400_000,
    app = "0.0.1 (debug)",
    platform = "Android 16 (API 36)",
    device = "phone",
    abi = "arm64-v8a",
    locale = "pt-BR",
    thread = "main",
    uptimeSeconds = 3,
    occurrences = 1,
    summary = "IllegalStateException at PlayerViewModel.open",
    details = details,
    log = log,
)

class CrashReportTest {
    @Test
    fun `a report reads back as written`() {
        val report = sampleReport().copy(occurrences = 3)
        val text = report.format()
        assertTrue(text.startsWith("StremioNow crash report\nFormat: 1\nKind: crash\nTime: 2025-11-11 12:00 UTC\n"))
        assertEquals(report, CrashReport.parse(text, fallbackTime = 0))
    }

    @Test
    fun `non-ASCII survives the round trip`() {
        val report = sampleReport(details = "Erro: não foi possível 中文 😀 שלום")
        val bytes = report.format().encodeToByteArray()
        assertEquals(report, CrashReport.parse(bytes.decodeToString(), fallbackTime = 0))
    }

    @Test
    fun `reports from other versions and damaged files still read`() {
        val newer =
            "StremioNow crash report\nFormat: 7\nKind: something-new\nFuture: yes\nSummary: X at Y.z\n\n" +
                "--- Details ---\ntrace\n"
        val parsed = CrashReport.parse(newer, fallbackTime = 42)
        assertEquals(ReportKind.Crash, parsed.kind)
        assertEquals(42, parsed.time)
        assertEquals("trace", parsed.details)
        assertEquals("unknown", parsed.app)

        val garbage = CrashReport.parse("not a report at all\r\nsecond line", fallbackTime = 1)
        assertEquals("not a report at all\nsecond line", garbage.details)
        assertEquals(1, garbage.occurrences)

        val truncated = CrashReport.parse(sampleReport().format().take(60), fallbackTime = 5)
        assertEquals(ReportKind.Crash, truncated.kind)
        assertTrue(CrashReport.parse("", fallbackTime = 0).details.isEmpty())
    }

    @Test
    fun `reading back redacts again and keeps the summary clean`() {
        val leaky =
            sampleReport(details = "at https://h.example/SECRETKEY", log = listOf("someone@example.com"))
                .copy(summary = "Bad\nTitle https://h.example/x?k=v <script>")
        val again = CrashReport.parse(leaky.format(), fallbackTime = 0).redactedAgain(homeFolder = null)
        assertFalse(again.details.contains("SECRETKEY"))
        assertEquals(listOf("[email]"), again.log)
        assertFalse(again.summary.contains("/"))
        assertFalse(again.summary.contains("?"))
    }

    @Test
    fun `header values cannot break the format`() {
        val report = sampleReport().copy(thread = "evil\nKind: anr", summary = "a\n--- Details ---")
        val parsed = CrashReport.parse(report.format(), fallbackTime = 0)
        assertEquals(ReportKind.Crash, parsed.kind)
        assertEquals(report.details, parsed.details)
    }
}
