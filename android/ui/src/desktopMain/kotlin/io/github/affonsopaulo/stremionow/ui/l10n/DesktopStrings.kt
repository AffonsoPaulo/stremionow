// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.l10n

import java.io.InputStreamReader
import java.util.Locale
import java.util.Properties
import kotlin.math.abs

/**
 * The interface strings on the desktop, read from the tables that `generateStrings` builds out
 * of `l10n/res` (docs/adr/0003-strings-format.md).
 *
 * The language is the system's, chosen as Android does: the exact locale (`pt_BR`), then the
 * language alone (`pt`), then another region of the same language, then English. A string
 * missing from a translation falls back along the same chain; a plurals resource comes whole
 * from the first table that has it.
 */
internal object DesktopStrings {
    private const val FOLDER = "io/github/affonsopaulo/stremionow/ui/l10n"
    private val log = System.getLogger("StremioNow")

    /** The tables to read, most specific first, ending with English; and the locale of the first. */
    private val chain: Pair<List<Properties>, Locale> by lazy { resolve(Locale.getDefault()) }

    /** The locale the strings are shown in, for plural rules and number formatting. */
    val locale: Locale get() = chain.second

    fun string(key: String): String = chain.first.firstNotNullOfOrNull { it.getProperty(key) } ?: missing(key)

    fun plural(
        key: String,
        count: Int,
    ): String {
        val table = chain.first.firstOrNull { it.getProperty("$key.other") != null } ?: return missing(key)
        return table.getProperty("$key.${PluralRules.quantity(locale, count)}") ?: table.getProperty("$key.other")
    }

    /** Fills positional placeholders (`%1$s`, `%2$d`) as Android's `getString` does. */
    fun format(
        pattern: String,
        args: Array<out Any>,
    ): String = String.format(locale, pattern, *args)

    /** The tables for [wanted], given the translations that exist, and the locale they are in. */
    internal fun resolve(wanted: Locale): Pair<List<Properties>, Locale> {
        val available =
            read("locales.txt")
                ?.lineSequence()
                ?.filter(String::isNotBlank)
                ?.toList()
                .orEmpty()
        val language = wanted.language
        val exact = "${language}_${wanted.country}".takeIf { wanted.country.isNotEmpty() }
        val translation =
            listOfNotNull(exact, language)
                .firstOrNull { it in available }
                ?: available.firstOrNull { it.substringBefore('_') == language }
        val tables =
            listOfNotNull(
                translation?.let { load("strings_$it.properties") },
                // A regional translation falls back to the plain language one, if there is one.
                translation?.takeIf { '_' in it }?.substringBefore('_')?.takeIf { it in available }?.let {
                    load("strings_$it.properties")
                },
                load("strings.properties"),
            )
        val locale = translation?.let { Locale.forLanguageTag(it.replace('_', '-')) } ?: Locale.ENGLISH
        return tables to locale
    }

    private fun load(name: String): Properties? =
        DesktopStrings::class.java.classLoader.getResourceAsStream("$FOLDER/$name")?.use { stream ->
            Properties().apply { load(InputStreamReader(stream, Charsets.UTF_8)) }
        }

    private fun read(name: String): String? =
        DesktopStrings::class.java.classLoader
            .getResourceAsStream("$FOLDER/$name")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun missing(key: String): String {
        log.log(System.Logger.Level.WARNING, "Missing string: $key")
        return key
    }
}

/**
 * CLDR plural categories for whole numbers, for the languages `l10n/res` has. Android uses ICU
 * for this; the desktop JVM has no public API for it. Add a language here when its translation
 * arrives: a test fails until every translated language has a rule.
 */
internal object PluralRules {
    /** Languages with a rule of their own; any other uses English's. */
    val languages = setOf("en", "pt")

    fun quantity(
        locale: Locale,
        count: Int,
    ): String {
        val n = abs(count)
        val millions = n != 0 && n % 1_000_000 == 0
        val portuguese = locale.language == "pt"
        return when {
            // Portugal: one for 1 only. Brazil (CLDR's "pt"): one for 0 and 1. Both: many for millions.
            portuguese && locale.country == "PT" && n == 1 -> "one"

            portuguese && locale.country != "PT" && n <= 1 -> "one"

            portuguese && millions -> "many"

            portuguese -> "other"

            n == 1 -> "one"

            else -> "other"
        }
    }
}
