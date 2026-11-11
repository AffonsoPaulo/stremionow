// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class IssueLinkTest {
    /** The query parameters of [url], decoded as a server would. */
    private fun parameters(url: String): Map<String, String> =
        URI(url).rawQuery.split('&').associate { pair ->
            val (name, value) = pair.split('=', limit = 2)
            name to URLDecoder.decode(value, Charsets.UTF_8)
        }

    @Test
    fun `a short report travels whole`() {
        val report = sampleReport()
        val link = IssueLink.build(report, IssueLink.BROWSER_BUDGET)
        assertFalse(link.shortened)
        assertTrue(IssueLink.isIssueLink(link.url))
        val query = parameters(link.url)
        assertEquals(setOf("template", "title", "version", "platform", "report"), query.keys)
        assertEquals("crash.yml", query["template"])
        assertEquals(report.format(), query["report"])
        assertEquals("Crash: IllegalStateException at PlayerViewModel.open (0.0.1 (debug))", query["title"])
        assertEquals("Android 16 (API 36), phone, arm64-v8a", query["platform"])
    }

    @Test
    fun `long reports are shortened to fit and say so`() {
        val frames =
            (1..2000).joinToString("\n") {
                "\tat io.github.affonsopaulo.stremionow.Some${it}Class.method$it(File.kt:$it)"
            }
        val report =
            sampleReport(
                details = "java.lang.IllegalStateException: boom\n$frames",
                log = List(50) { "12:00:00 W line $it" },
            )
        for (budget in listOf(IssueLink.BROWSER_BUDGET, IssueLink.QR_BUDGET)) {
            val link = IssueLink.build(report, budget)
            assertTrue(link.shortened)
            assertTrue("${link.url.length} > $budget", link.url.length <= budget)
            val text = parameters(link.url).getValue("report")
            assertTrue(text.contains(IssueLink.SHORTENED_NOTE))
            assertTrue(text.contains("java.lang.IllegalStateException: boom"))
            assertTrue(text.contains("Kind: crash"))
        }
    }

    @Test
    fun `a single enormous line is cut, not lost`() {
        val report = sampleReport(details = "x".repeat(60_000), log = emptyList())
        val link = IssueLink.build(report, IssueLink.QR_BUDGET)
        assertTrue(link.url.length <= IssueLink.QR_BUDGET)
        assertTrue(parameters(link.url).getValue("report").contains("xxxxxxxx"))
    }

    @Test
    fun `nothing in a report can add parameters or leave the page`() {
        val evil = "a&title=pwned&labels=x#frag\r\nb=c%26d+e 中😀 ‮"
        val report = sampleReport(details = evil, log = listOf(evil)).copy(app = "0.0.1&x=y", summary = "S&t=u")
        val link = IssueLink.build(report, IssueLink.BROWSER_BUDGET)
        val uri = URI(link.url)
        assertEquals("https", uri.scheme)
        assertEquals("github.com", uri.host)
        assertEquals("/AffonsoPaulo/stremionow/issues/new", uri.path)
        assertEquals(null, uri.rawFragment)
        val query = parameters(link.url)
        assertEquals(setOf("template", "title", "version", "platform", "report"), query.keys)
        assertTrue(query.getValue("report").contains(evil))
        assertFalse(query.getValue("title").contains('&'))
        // Only unreserved characters and escapes in the query.
        assertTrue(uri.rawQuery.all { (it.isLetterOrDigit() && it.code < 128) || it in "-_.~%+=&" })
    }

    @Test
    fun `the title never carries the message or addresses`() {
        val report =
            sampleReport(details = "java.lang.IllegalStateException: someone@example.com https://h.example/KEY")
                .copy(summary = "IllegalStateException at X.y someone@example.com https://h/KEY")
        val title = IssueLink.title(report)
        assertFalse(title.contains('@'))
        assertFalse(title.contains('/'))
        assertFalse(title.contains("example.com/"))
    }

    @Test
    fun `only links to the repository's new-issue page count`() {
        assertTrue(IssueLink.isIssueLink("https://github.com/AffonsoPaulo/stremionow/issues/new?template=crash.yml"))
        assertFalse(IssueLink.isIssueLink("https://github.com/AffonsoPaulo/stremionow/issues/new.evil.example/?x"))
        assertFalse(IssueLink.isIssueLink("http://github.com/AffonsoPaulo/stremionow/issues/new?x"))
        assertFalse(IssueLink.isIssueLink("https://github.com.evil.example/AffonsoPaulo/stremionow/issues/new?x"))
    }
}
