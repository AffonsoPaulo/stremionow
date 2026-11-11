// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.l10n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class DesktopStringsTest {
    private val brazil = Locale.forLanguageTag("pt-BR")

    @Test
    fun everyTranslatedLanguageHasPluralRules() {
        val locales =
            javaClass.classLoader
                .getResourceAsStream("io/github/affonsopaulo/stremionow/ui/l10n/locales.txt")!!
                .use { it.readBytes().toString(Charsets.UTF_8) }
                .lines()
                .filter(String::isNotBlank)
        assertTrue("No translations found", locales.isNotEmpty())
        for (locale in locales) {
            val language = locale.substringBefore('_')
            assertTrue("Add plural rules for \"$language\" to PluralRules", language in PluralRules.languages)
        }
    }

    @Test
    fun portugueseFromBrazilUsesOneForZeroAndOneAndManyForMillions() {
        assertEquals("one", PluralRules.quantity(brazil, 0))
        assertEquals("one", PluralRules.quantity(brazil, 1))
        assertEquals("other", PluralRules.quantity(brazil, 2))
        assertEquals("many", PluralRules.quantity(brazil, 1_000_000))
        assertEquals("one", PluralRules.quantity(Locale.ENGLISH, 1))
        assertEquals("other", PluralRules.quantity(Locale.ENGLISH, 0))
    }

    @Test
    fun brazilianPortugueseTableWinsAndEnglishFillsTheRest() {
        val (tables, locale) = DesktopStrings.resolve(brazil)
        assertEquals(brazil, locale)
        assertEquals("Início", tables.first().getProperty("nav_home"))
        assertEquals("StremioNow", tables.firstNotNullOf { it.getProperty("app_name") })
    }

    @Test
    fun anotherRegionOfTheSameLanguageIsUsedBeforeEnglish() {
        val (tables, locale) = DesktopStrings.resolve(Locale.forLanguageTag("pt-PT"))
        assertEquals(brazil, locale)
        assertEquals("Início", tables.first().getProperty("nav_home"))
    }

    @Test
    fun unknownLanguagesFallBackToEnglish() {
        val (tables, locale) = DesktopStrings.resolve(Locale.JAPANESE)
        assertEquals(Locale.ENGLISH, locale)
        assertEquals("Home", tables.first().getProperty("nav_home"))
    }

    @Test
    fun androidEscapesAreResolved() {
        val english = DesktopStrings.resolve(Locale.ENGLISH).first.first()
        assertEquals("Couldn't load this", english.getProperty("error_load_title"))
        assertTrue(english.getProperty("sources_wifi_body").contains("\"Only on Wi-Fi\""))
        assertEquals("%1\$d sources", english.getProperty("sources_count.other"))
    }
}
