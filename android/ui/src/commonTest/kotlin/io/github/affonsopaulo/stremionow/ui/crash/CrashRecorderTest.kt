// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.crash

import io.github.affonsopaulo.stremionow.core.InternalException
import io.github.affonsopaulo.stremionow.core.PanicDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.PrintStream
import java.io.PrintWriter

class CrashRecorderTest {
    @get:Rule val temporary = TemporaryFolder()

    private var clock = 1_762_862_400_000L
    private val home = "C:\\Users\\Jane Doe"
    private val environment =
        CrashEnvironment(
            app = "0.0.1",
            platform = "Windows 11 10.0 (Java 25)",
            device = "desktop",
            abi = "amd64",
            homeFolder = home,
            debug = false,
        )
    private val store by lazy { ReportStore(temporary.root) { clock } }

    private fun recorder(log: List<String> = emptyList()) =
        CrashRecorder(
            store = store,
            environment = environment,
            startedAt = clock - 5_000,
            errorsEndProcess = true,
            recentLog = { log },
            now = { clock },
        )

    private fun saved() = store.load(homeFolder = home)

    @Test
    fun `an uncaught exception becomes a redacted report`() {
        val error =
            IllegalStateException(
                "load https://torrentio.strem.fun/realdebrid=SECRETKEY123456/manifest.json for someone@example.com " +
                    "from $home\\Videos\\private.mkv",
            )
        recorder(
            log = listOf("12:00:00 W token=abc123def"),
        ).recordCrash(Thread("OkHttp https://h.example/SECRET"), error)
        val report = saved().single().report
        assertEquals(ReportKind.Crash, report.kind)
        assertEquals(5L, report.uptimeSeconds)
        assertEquals("desktop", report.device)
        assertTrue(report.summary, report.summary.startsWith("IllegalStateException at "))
        val text = report.format()
        for (secret in listOf("SECRETKEY", "someone", "Jane", "private.mkv", "abc123def", "SECRET")) {
            assertFalse("'$secret' leaked:\n$text", text.contains(secret))
        }
        assertTrue(text.contains("https://torrentio.strem.fun/[redacted]"))
    }

    @Test
    fun `a core panic and the exception it raises become one report`() {
        val recorder = recorder()
        recorder.recordPanic(
            PanicDetails(
                message = "called `Option::unwrap()` on a `None` value at https://h.example/KEY",
                location = "/home/runner/work/stremionow/core/crates/ffi/src/core.rs:120:9",
                thread = "main",
            ),
        )
        assertEquals(ReportKind.CorePanic, saved().single().report.kind)
        clock += 1_000
        recorder.recordCrash(
            Thread.currentThread(),
            RuntimeException("wrapped", InternalException("called `Option::unwrap()`")),
        )
        val report = saved().single().report
        assertEquals(ReportKind.Crash, report.kind)
        assertEquals("Core panic at core.rs:120", report.summary)
        assertTrue(report.details.startsWith("Core panicked at crates/ffi/src/core.rs:120:9:"))
        assertTrue(report.details.contains("InternalException"))
        assertFalse(report.details.contains("KEY"))
    }

    @Test
    fun `an unrelated crash later does not take the panic`() {
        val recorder = recorder()
        recorder.recordPanic(PanicDetails("boom", "src/a.rs:1:1", "tokio-runtime-worker"))
        clock += CrashRecorder.PANIC_LINK_MS + 1
        recorder.recordCrash(Thread.currentThread(), RuntimeException("wrapped", InternalException("boom")))
        assertEquals(setOf(ReportKind.CorePanic, ReportKind.Crash), saved().map { it.report.kind }.toSet())
    }

    @Test
    fun `a native crash right after a panic is the panic aborting`() {
        recorder().recordPanic(PanicDetails("boom", "src/a.rs:1:1", "worker"))
        val pending = saved()
        clock += 60_000
        val wrote =
            recorder().recordExits(
                listOf(ProcessExit(ReportKind.NativeCrash, clock - 59_000, "Native crash (signal 6)", "signal 6", "")),
                pending,
            )
        assertTrue(wrote)
        val report = saved().single().report
        assertEquals(ReportKind.NativeCrash, report.kind)
        assertEquals("Core panic at a.rs:1", report.summary)
        assertTrue(report.details.contains("signal 6"))
        assertEquals(null, report.uptimeSeconds)
    }

    @Test
    fun `exits become reports without this launch's log`() {
        val wrote =
            recorder(log = listOf("12:00:00 W this launch")).recordExits(
                listOf(
                    ProcessExit(
                        ReportKind.NotResponding,
                        clock - 1000,
                        "Not responding at Foo.bar",
                        "Main thread:\nat https://h/KEY",
                        "main",
                    ),
                    ProcessExit(ReportKind.NativeCrash, clock - 2000, "Native crash (signal 11)", "signal 11", ""),
                ),
                pending = emptyList(),
            )
        assertTrue(wrote)
        val reports = saved().map { it.report }
        assertEquals(2, reports.size)
        assertTrue(reports.all { it.log.isEmpty() })
        assertFalse(reports.any { it.details.contains("KEY") })
    }

    @Test
    fun `a crash while describing the crash still leaves a report`() {
        val hostile =
            object : RuntimeException("x") {
                override fun printStackTrace(s: PrintWriter) = throw OutOfMemoryError("simulated")

                override fun printStackTrace(s: PrintStream) = throw OutOfMemoryError("simulated")
            }
        recorder().recordCrash(Thread.currentThread(), hostile)
        val report = saved().single().report
        assertTrue(report.details.contains("CrashRecorderTest"))
        assertTrue(report.log.isEmpty())
    }

    @Test
    fun `nothing escapes when the disk refuses`() {
        val blocked = temporary.newFile("blocked")
        val recorder = CrashRecorder(ReportStore(blocked), environment, clock, errorsEndProcess = true)
        recorder.recordCrash(Thread.currentThread(), IllegalStateException("x"))
        recorder.recordPanic(PanicDetails("boom", "a.rs:1:1", ""))
        assertFalse(recorder.recordExits(listOf(ProcessExit(ReportKind.NativeCrash, clock, "s", "d", "")), emptyList()))
    }
}
