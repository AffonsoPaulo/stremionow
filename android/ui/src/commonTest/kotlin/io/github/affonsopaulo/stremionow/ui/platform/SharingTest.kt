// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharingTest {
    @Test
    fun `only web pages open`() {
        assertTrue(isWebPage("https://addon.example/configure", allowHttp = false))
        assertTrue(isWebPage("HTTPS://addon.example/configure", allowHttp = false))
        assertFalse(isWebPage("http://192.168.1.2:7000/configure", allowHttp = false))
        assertTrue(isWebPage("http://192.168.1.2:7000/configure", allowHttp = true))
        for (link in listOf(
            "javascript:alert('https://x')",
            "file:///etc/hosts",
            "intent://x#Intent;end",
            "stremio://a/manifest.json",
            "",
        )) {
            assertFalse(link, isWebPage(link, allowHttp = true))
        }
    }
}
