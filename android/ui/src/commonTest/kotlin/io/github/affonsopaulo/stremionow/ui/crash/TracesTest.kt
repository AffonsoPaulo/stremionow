// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TracesTest {
    @Test
    fun `recursion folds into one copy`() {
        val lines = listOf("Error") + List(3000) { "\tat a.B.recurse(B.kt:10)" } + "\tat a.B.main(B.kt:1)"
        assertEquals(
            listOf(
                "Error",
                "\tat a.B.recurse(B.kt:10)",
                "\t... (the line above repeated 2999 more times)",
                "\tat a.B.main(B.kt:1)",
            ),
            Traces.collapseRepeats(lines),
        )
        val cycle = List(600) { if (it % 2 == 0) "\tat a.B.ping" else "\tat a.B.pong" }
        assertEquals(
            listOf("\tat a.B.ping", "\tat a.B.pong", "\t... (the 2 lines above repeated 299 more times)"),
            Traces.collapseRepeats(cycle),
        )
        // Two copies are not a pattern.
        val twice = listOf("x", "x", "y")
        assertEquals(twice, Traces.collapseRepeats(twice))
    }

    @Test
    fun `a stack overflow makes a short report`() {
        fun recurse(depth: Int): Int = if (depth < 0) depth else recurse(depth + 1) + 1
        val error = runCatching { recurse(0) }.exceptionOrNull()!!
        val trace = Traces.stackTrace(error)
        assertTrue(trace.startsWith("java.lang.StackOverflowError"))
        assertTrue(trace.lines().size < 50)
        assertTrue(trace.length <= Traces.MAX_CHARS)
    }

    @Test
    fun `huge traces are capped`() {
        val error = IllegalStateException("x".repeat(500_000))
        val trace = Traces.stackTrace(error)
        assertTrue(trace.length <= Traces.MAX_CHARS)
        val many = List(5000) { "line $it" }
        val capped = Traces.cap(many)
        assertEquals(Traces.MAX_LINES + 1, capped.lines().size)
    }

    @Test
    fun `compacting keeps every exception line`() {
        val trace =
            "java.lang.RuntimeException: outer\n" +
                (1..20).joinToString("\n") { "\tat a.B.f$it(B.kt:$it)" } +
                "\nCaused by: java.io.IOException: inner\n" +
                (1..20).joinToString("\n") { "\tat c.D.g$it(D.kt:$it)" } +
                "\n\t... 5 more"
        val compact = Traces.compact(trace, frames = 2)
        assertEquals(
            listOf(
                "java.lang.RuntimeException: outer",
                "\tat a.B.f1(B.kt:1)",
                "\tat a.B.f2(B.kt:2)",
                "\t... (18 frames left out)",
                "Caused by: java.io.IOException: inner",
                "\tat c.D.g1(D.kt:1)",
                "\tat c.D.g2(D.kt:2)",
                "\t... (19 frames left out)",
            ),
            compact.lines(),
        )
    }

    @Test
    fun `summaries name the root cause and the app's frame, never the message`() {
        val cause = IllegalArgumentException("secret https://h.example/KEY")
        cause.stackTrace =
            arrayOf(
                StackTraceElement("java.util.Objects", "requireNonNull", "Objects.java", 1),
                StackTraceElement(
                    "io.github.affonsopaulo.stremionow.ui.screens.PlayerViewModel",
                    "open",
                    "PlayerViewModel.kt",
                    9,
                ),
            )
        val error = RuntimeException("wrapper", cause)
        assertEquals("IllegalArgumentException at PlayerViewModel.open", Traces.summary(error))

        val cyclic = RuntimeException("a")
        val other = RuntimeException("b", cyclic)
        cyclic.initCause(other)
        // Terminates despite the cycle.
        Traces.summary(cyclic)
    }

    @Test
    fun `summaries keep only safe characters`() {
        assertEquals("Crash at Foo.bar", Traces.sanitizeSummary("Crash at Foo.bar"))
        val nasty = Traces.sanitizeSummary("x&title=pwned#\n\u202eevil\u0000 https://h/?q=1 \u4e2d")
        assertFalse(nasty.any { it in "&#=?/\n\u202e\u0000" || it.code > 127 })
        assertEquals("unknown error", Traces.sanitizeSummary("\u4e2d\u6587"))
        assertTrue(Traces.sanitizeSummary("a".repeat(500)).length <= 120)
    }

    @Test
    fun `panic locations lose the build machine's folders`() {
        assertEquals(
            "tokio-1.54.1/src/runtime/task/mod.rs:12:5",
            Traces.panicLocation(
                "/home/runner/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/tokio-1.54.1/src/runtime/task/mod.rs:12:5",
            ),
        )
        assertEquals(
            "crates/ffi/src/core.rs:120:9",
            Traces.panicLocation("C:\\Users\\dev\\StremioNow\\core\\crates/ffi/src/core.rs:120:9"),
        )
        assertEquals("core.rs:120", Traces.panicPlace("crates/ffi/src/core.rs:120:9"))
    }

    @Test
    fun `cutting text never splits a character`() {
        val text = "ab\ud83d\ude00cd"
        assertEquals("ab", Traces.safeTake(text, 3))
        assertEquals("ab\ud83d\ude00", Traces.safeTake(text, 4))
    }

    @Test
    fun `thread names are redacted`() {
        val name =
            Traces.threadName(
                "OkHttp https://torrentio.strem.fun/realdebrid=SECRETKEY123456789/x",
                homeFolder = null,
            )
        assertFalse(name.contains("SECRETKEY"))
        assertTrue(name.startsWith("OkHttp https://torrentio.strem.fun"))
    }
}
