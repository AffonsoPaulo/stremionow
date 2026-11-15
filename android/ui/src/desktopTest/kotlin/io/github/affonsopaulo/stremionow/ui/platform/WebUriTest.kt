// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebUriTest {
    @Test
    fun configureLinksWithPipesOpen() {
        val uri = webUri("https://torrentio.strem.fun/sort=size|qualityfilter=480p/configure")
        assertEquals("https://torrentio.strem.fun/sort=size%7Cqualityfilter=480p/configure", uri.toString())
        assertEquals("/sort=size|qualityfilter=480p/configure", uri?.path)
    }

    @Test
    fun validAddressesStayTheSame() {
        for (url in listOf(
            "https://addon.example/configure",
            "http://192.168.1.2:7000/a%20b/configure?x=1&y=%7B%7D#top",
            "https://[2001:db8::1]:8080/configure",
            "https://user@addon.example/p;q=1/(x)*~!$',+",
        )) {
            assertEquals(url, webUri(url).toString())
        }
    }

    @Test
    fun charactersAUriCannotHoldAreEncoded() {
        assertEquals(
            "https://a.example/b%20c/%7B%22k%22:%5B1%5D%7D?q=%C3%A7%F0%9F%8E%AC#f%23g",
            encodeIllegal("https://a.example/b c/{\"k\":[1]}?q=ç🎬#f#g"),
        )
        // A '%' that starts no escape is a plain percent sign.
        assertEquals("https://a.example/100%25?x=%252", encodeIllegal("https://a.example/100%?x=%2"))
    }

    @Test
    fun onlyWebAddressesWithAHost() {
        assertNull(webUri("file:///etc/hosts"))
        assertNull(webUri("javascript:alert(1)"))
        assertNull(webUri("stremio://addon.example/manifest.json"))
        assertNull(webUri("https:///no-host"))
    }
}
