// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class OnboardingTest {
    @Test
    fun debridKeysOfEveryServicePass() {
        // Made-up keys in each service's shape: letters and digits, or a UUID (TorBox).
        assertTrue(isPlausibleDebridKey("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567ABCDEFGHIJKLMNOPQRST"))
        assertTrue(isPlausibleDebridKey("aBcD1234eFgH5678iJkL"))
        assertTrue(isPlausibleDebridKey("0f8fad5b-d9cb-469f-a165-70867728950e"))
        assertTrue(isPlausibleDebridKey("token_with_underscores"))
    }

    @Test
    fun debridKeysThatWouldChangeTheAddressAreRefused() {
        assertFalse(isPlausibleDebridKey("short"))
        assertFalse(isPlausibleDebridKey("a".repeat(201)))
        assertFalse(isPlausibleDebridKey("key1234|sort=size"))
        assertFalse(isPlausibleDebridKey("key1234/../manifest"))
        assertFalse(isPlausibleDebridKey("key 12345"))
        assertFalse(isPlausibleDebridKey("key1234=5678"))
    }

    @Test
    fun languageNamesAreCapitalisedInTheirOwnLanguage() {
        // Under a Turkish default, the default locale's rules would give "İtaliano".
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertEquals("Italiano", languageName("it"))
            assertEquals("English", languageName("en"))
        } finally {
            Locale.setDefault(before)
        }
    }
}
