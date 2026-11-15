// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.l10n

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.LayoutDirection
import io.github.affonsopaulo.stremionow.ui.platform.PlatformContext
import java.util.Locale

/**
 * The languages the interface is translated into (the folders of `l10n/res`), as BCP 47 tags,
 * English first. A test keeps it in step with the translations.
 */
val InterfaceLanguages: List<String> = listOf("en", "pt-BR")

/**
 * The interface language picked in Settings › General: a tag of [InterfaceLanguages], or ""
 * to follow the system, the default. It changes the interface's strings, plurals and number
 * formats at once; titles and overviews come from addons and keep their language.
 *
 * Android keeps the choice itself from Android 13 (the per-app language, also in the system's
 * settings for the app) and the app keeps it before; the desktop keeps it in its data folder.
 */
expect object AppLanguage {
    fun current(context: PlatformContext): String

    fun set(
        context: PlatformContext,
        tag: String,
    )
}

/** The [InterfaceLanguages] tag for [locale], or "" when there is no translation for it. */
fun interfaceLanguageFor(locale: Locale?): String {
    locale ?: return ""
    val exact = InterfaceLanguages.firstOrNull { it.equals(locale.toLanguageTag(), ignoreCase = true) }
    return exact ?: InterfaceLanguages.firstOrNull { Locale.forLanguageTag(it).language == locale.language }.orEmpty()
}

/** A language's name in that language, capitalised: "English", "Português (Brasil)". */
fun endonym(tag: String): String {
    val locale = Locale.forLanguageTag(tag)
    return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
}

/**
 * The language the interface's strings are in now: the chosen one, or the system's when there
 * is a translation for it, or English, which the strings fall back to.
 */
@Composable
internal expect fun interfaceLocale(): Locale

/**
 * The layout direction for text in [locale]. The interface follows its strings' language rather
 * than the system's: on an Arabic or Hebrew system without that translation, the English
 * strings stay left to right.
 */
fun layoutDirectionFor(locale: Locale): LayoutDirection =
    if (locale.language in RightToLeftLanguages) LayoutDirection.Rtl else LayoutDirection.Ltr

/** Languages written right to left, with the old codes Java still uses for some ("iw", "ji"). */
private val RightToLeftLanguages = setOf("ar", "ckb", "dv", "fa", "he", "iw", "ji", "ps", "sd", "ug", "ur", "yi")
