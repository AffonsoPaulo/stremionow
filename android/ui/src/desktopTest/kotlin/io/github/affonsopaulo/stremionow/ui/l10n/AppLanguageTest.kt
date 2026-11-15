// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.l10n

import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.Locale

class AppLanguageTest {
    @Test
    fun theChoicesAreTheTranslations() {
        val translations =
            javaClass.classLoader
                .getResourceAsStream("io/github/affonsopaulo/stremionow/ui/l10n/locales.txt")!!
                .use { it.readBytes().toString(Charsets.UTF_8) }
                .lines()
                .filter(String::isNotBlank)
                .map { it.replace('_', '-') }
        assertEquals(listOf("en") + translations.sorted(), InterfaceLanguages)
    }

    @Test
    fun androidListsTheSameLanguages() {
        val config = File("src/androidMain/res/xml/locales_config.xml").readText()
        val listed = Regex("""<locale android:name="([^"]+)"""").findAll(config).map { it.groupValues[1] }.toList()
        assertEquals(InterfaceLanguages, listed)
    }

    @Test
    fun localesMatchTheirTranslation() {
        assertEquals("pt-BR", interfaceLanguageFor(Locale.forLanguageTag("pt-BR")))
        assertEquals("pt-BR", interfaceLanguageFor(Locale.forLanguageTag("pt-PT")))
        assertEquals("en", interfaceLanguageFor(Locale.forLanguageTag("en-GB")))
        assertEquals("", interfaceLanguageFor(Locale.JAPANESE))
        assertEquals("", interfaceLanguageFor(null))
    }

    @Test
    fun layoutRunsTheWayTheLanguageIsWritten() {
        assertEquals(LayoutDirection.Rtl, layoutDirectionFor(Locale.forLanguageTag("ar-EG")))
        assertEquals(LayoutDirection.Rtl, layoutDirectionFor(Locale.forLanguageTag("he")))
        assertEquals(LayoutDirection.Ltr, layoutDirectionFor(Locale.ENGLISH))
        assertEquals(LayoutDirection.Ltr, layoutDirectionFor(Locale.forLanguageTag("pt-BR")))
        // Every translation today is left to right, so no system language turns the layout.
        for (tag in InterfaceLanguages) {
            assertEquals(
                tag,
                LayoutDirection.Ltr,
                layoutDirectionFor(Locale.forLanguageTag(tag)),
            )
        }
    }

    @Test
    fun languagesAreNamedInThemselves() {
        assertEquals("English", endonym("en"))
        assertEquals("Português (Brasil)", endonym("pt-BR"))
    }
}
