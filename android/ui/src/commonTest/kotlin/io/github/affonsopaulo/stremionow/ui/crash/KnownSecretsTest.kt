// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.crash

import org.junit.Assert.assertEquals
import org.junit.Test

class KnownSecretsTest {
    @Test
    fun keysInAddonAddressesAreFound() {
        assertEquals(
            listOf("ABCD1234EFGH5678"),
            keyLikeParts(
                "https://torrentio.strem.fun/sort=qualitysize|qualityfilter=480p|realdebrid=ABCD1234EFGH5678/manifest.json",
            ),
        )
        assertEquals(
            listOf("0f8fad5b-d9cb-469f-a165-70867728950e"),
            keyLikeParts(
                "https://torrentio.strem.fun/torbox=0f8fad5b-d9cb-469f-a165-70867728950e%7Csort=size/manifest.json",
            ),
        )
        assertEquals(
            listOf("abc123def456"),
            keyLikeParts("https://addon.example/manifest.json?apikey=abc123def456&lang=en"),
        )
    }

    @Test
    fun ordinaryAddressesHoldNoKeys() {
        assertEquals(emptyList<String>(), keyLikeParts("https://v3-cinemeta.strem.io/manifest.json"))
        assertEquals(emptyList<String>(), keyLikeParts("https://opensubtitles-v3.strem.io/manifest.json"))
        assertEquals(emptyList<String>(), keyLikeParts("not an address"))
    }
}
