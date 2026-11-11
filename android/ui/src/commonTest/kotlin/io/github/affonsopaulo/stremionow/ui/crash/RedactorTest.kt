// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {
    private fun redact(
        text: String,
        home: String? = null,
    ) = Redactor.redact(text, homeFolder = home)

    /** Asserts that none of [secrets] survive in [text] once redacted, and returns the result. */
    private fun assertGone(
        text: String,
        vararg secrets: String,
        home: String? = null,
    ): String {
        val result = redact(text, home)
        for (secret in secrets) {
            assertFalse(
                "'$secret' survived in: $result",
                result.contains(secret, ignoreCase = true),
            )
        }
        // Redacting is idempotent: the rules never match their own placeholders.
        assertEquals(result, redact(result, home))
        return result
    }

    @Test
    fun `addresses keep only their scheme and host`() {
        assertEquals(
            "Failed: https://torrentio.strem.fun/[redacted]",
            redact(
                "Failed: https://torrentio.strem.fun/realdebrid=ABCDEFGHIJKLMNOPQRSTUVWXYZ012345/stream/movie/tt0111161.json",
            ),
        )
        assertEquals(
            "GET https://api.real-debrid.com/[redacted] 401",
            redact("GET https://api.real-debrid.com/rest/1.0/unrestrict/link?auth_token=KEY 401"),
        )
        assertEquals("see https://example.com.", redact("see https://example.com."))
        assertEquals("(https://example.com/[redacted])", redact("(https://example.com/a/b)"))
        assertEquals("https://example.com", redact("https://example.com/"))
    }

    @Test
    fun `credentials, ports and fragments in addresses`() {
        assertGone("https://user:hunter2@host.example/x", "user", "hunter2")
        assertEquals("https://host.example/[redacted]", redact("https://user:hunter2@host.example/x"))
        assertEquals(
            "http://127.0.0.1:11470/[redacted]",
            redact("http://127.0.0.1:11470/0123456789abcdef0123456789abcdef/proxy/x"),
        )
        assertGone("https://host.example#access_token=SECRETVALUE", "SECRETVALUE")
        assertGone("https://host.example?apikey=SECRETVALUE", "SECRETVALUE")
        assertEquals("http://[::1]:8080/[redacted]", redact("http://[::1]:8080/abc"))
        // Something after a colon that is not a port is not kept either.
        assertGone("https://name:pass/word@host/", "pass", "word")
        // Text that imitates an already redacted address gets no free pass.
        assertGone("https://h.example/[redacted]SECRET", "SECRET")
        assertGone("https://name:pass/[redacted]", "pass")
        assertGone("https://name:pass@h.example/[redacted].", "name", "pass")
    }

    @Test
    fun `addresses in every disguise`() {
        assertGone("HTTPS://HOST.EXAMPLE/PATH/SECRETKEY", "SECRETKEY")
        assertGone("""{"url":"https:\/\/host.example\/key\/SECRETKEY\/manifest.json"}""", "SECRETKEY", "manifest")
        assertGone("u=https%3A%2F%2Fhost.example%2FSECRETKEY%2Fmanifest.json", "SECRETKEY", "host.example")
        assertGone("stremio://host.example/SECRETKEY/manifest.json", "SECRETKEY")
        assertGone("content://media/external/video/media/SECRET", "SECRET")
        assertGone("file:///storage/emulated/0/Movies/Private%20Film.mkv", "Private", "Movies")
        assertGone("<a href='https://h.example/SECRETKEY'>", "SECRETKEY")
        assertGone("https://h.example/\u00e1\u00e9\u4e2d\u6587SECRET\ud83d\ude00", "SECRET", "\u4e2d")
        assertGone("https://h.example/a?next=https://other.example/SECRETKEY", "SECRETKEY", "other.example")
        assertGone("ws://h.example:9000/socket?token=SECRETKEY", "SECRETKEY")
        // A scheme the URL pattern cannot read still leaves `//host/…` behind.
        assertGone("http​s://h.example/SECRETKEY", "SECRETKEY")
        assertGone("һttps://h.example/SECRETKEY", "SECRETKEY")
        assertEquals("fetch //cdn.example/[redacted] failed", redact("fetch //cdn.example/a/SECRETKEY failed"))
        assertGone("apikey%3DSECRETVALUE", "SECRETVALUE")
    }

    @Test
    fun `magnets, bearer tokens and secret pairs`() {
        assertGone(
            "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Some.Movie",
            "Some.Movie",
            "0123456789abcdef",
        )
        assertGone("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig", "eyJhbGciOiJIUzI1NiJ9", "payload")
        assertGone("authKey=abcdEFGH; other", "abcdEFGH")
        assertGone("""{"authKey":"abcdEFGH","email":"x"}""", "abcdEFGH")
        assertGone("""{\"authKey\":\"abcdEFGH\"}""", "abcdEFGH")
        assertGone("password: correct horse", "correct")
        assertGone("X-Api-Key: abcdEFGH", "abcdEFGH")
        assertGone("token='abcdEFGH'", "abcdEFGH")
        assertEquals("apikey=[redacted]", redact("apikey=SECRET"))
    }

    @Test
    fun `emails, ids, hex strings and tokens`() {
        assertEquals("signed in as [email]", redact("signed in as someone.else+tag@mail.example.co.uk"))
        assertGone("usu\u00e1rio@exemplo.com.br", "exemplo")
        assertGone("torbox key 0f8fad5b-d9cb-469f-a165-70867728950e", "0f8fad5b")
        assertGone("relay /0123456789abcdef0123456789abcdef/torrent", "0123456789abcdef")
        assertGone("alldebrid=Ab12Cd34Ef56Gh78Ij90", "Ab12Cd34Ef56Gh78Ij90")
        assertGone("rd ABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJ", "ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        assertGone("config eyJwcm92aWRlcnMiOlsiWVRTIl0sInNvcnQiOjF9 end", "eyJwcm92aWRlcnMiOlsiWVRTIl0")
        assertEquals("meta tt[id]:1:5 failed", redact("meta tt0903747:1:5 failed"))
        assertEquals("from [ip] and 127.0.0.1", redact("from 192.168.1.20 and 127.0.0.1"))
        assertEquals(
            "to [ip] port 443, [ip] and ::1",
            redact("to 2001:db8:85a3::8a2e:370:7334 port 443, fe80::1 and ::1"),
        )
        // Times, Rust paths and source positions are not addresses.
        assertEquals("12:00:01 W Option::unwrap at a.rs:1:5", redact("12:00:01 W Option::unwrap at a.rs:1:5"))
    }

    @Test
    fun `stack traces stay readable`() {
        val trace =
            """
            java.lang.IllegalStateException: Player not ready
            	at io.github.affonsopaulo.stremionow.ui.screens.PlayerViewModel.open(PlayerViewModel.kt:123)
            	at io.github.affonsopaulo.stremionow.ui.screens.PlayerViewModel${'$'}openSource${'$'}1.invokeSuspend(PlayerViewModel.kt:98)
            	at androidx.compose.runtime.ComposerImpl.rememberLauncherForActivityResult(ComposerImpl.kt:42)
            	at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:108)
            	at android.os.Handler.dispatchMessage(Handler.java:106)
            	at java.lang.Object.wait(Native Method)
            	Suppressed: kotlinx.coroutines.internal.DiagnosticCoroutineContextException: [StandaloneCoroutine{Cancelling}@5f2e1a, Dispatchers.Main]
            Caused by: java.io.IOException: unexpected end of stream on http://127.0.0.1:41234/abc/def
            	... 12 more
            """.trimIndent()
        val expected = trace.replace("http://127.0.0.1:41234/abc/def", "http://127.0.0.1:41234/[redacted]")
        assertEquals(expected, redact(trace))
        // App versions and Android releases are not addresses.
        assertEquals(
            "App: 0.0.1 (debug), Android 16 (API 36), 1.10.2",
            redact("App: 0.0.1 (debug), Android 16 (API 36), 1.10.2"),
        )
    }

    @Test
    fun `home folders and shared storage`() {
        assertEquals(
            "~\\AppData\\Roaming\\StremioNow\\core",
            redact("C:\\Users\\Jane\\AppData\\Roaming\\StremioNow\\core"),
        )
        assertEquals("~/.cargo/registry/src/x.rs", redact("/home/jane/.cargo/registry/src/x.rs"))
        assertEquals(
            "~/Library/Application Support/StremioNow",
            redact("/Users/jane/Library/Application Support/StremioNow"),
        )
        assertGone("open failed: /home/jane/Videos/My Holiday.mkv (No such file)", "jane", "Holiday", "Videos")
        assertEquals(
            "open failed: ~/[path] (No such file)",
            redact("open failed: /home/jane/Videos/My Holiday.mkv (No such file)"),
        )
        assertGone("\\\\?\\C:\\Users\\Jane Doe\\Desktop\\notes.txt", "Doe", "notes", home = "C:\\Users\\Jane Doe")
        assertGone("C:/Users/jane/Documents/x.mkv", "jane", "Documents")
        assertGone("/storage/emulated/0/Download/Some.Movie.2024.mkv", "Some.Movie", "Download")
        assertGone("/sdcard/Movies/x.mkv", "Movies")
        // The literal home folder goes even where no pattern would find it, in any case.
        assertGone("D:\\Profiles\\jdoe\\cache", "jdoe", home = "d:\\profiles\\jdoe")
    }

    @Test
    fun `known secrets are removed exactly`() {
        val result = Redactor.redact("relay abc123xyz9 and short ab", secrets = listOf("abc123xyz9", "ab"))
        assertEquals("relay [secret] and short ab", result)
    }

    @Test
    fun `hostile input stays fast`() {
        val inputs =
            listOf(
                "a".repeat(1_000_000),
                "a@".repeat(500_000),
                "https://".repeat(200_000),
                "~/".repeat(500_000),
                "1.".repeat(500_000),
                "key=".repeat(250_000),
                ("x".repeat(19) + " ").repeat(50_000),
                "%3A%2F%2F".repeat(100_000),
            )
        for (input in inputs) {
            val start = System.nanoTime()
            redact(input)
            val millis = (System.nanoTime() - start) / 1_000_000
            assertTrue("took $millis ms on ${input.take(12)}…", millis < 3_000)
        }
    }

    @Test
    fun `odd text passes through`() {
        assertEquals("", redact(""))
        val text = "\u05e9\u05dc\u05d5\u05dd \u0645\u0631\u062d\u0628\u0627 \u4f60\u597d \ud83d\ude00 \u0000\t\r\n"
        assertEquals(text, redact(text))
    }
}
