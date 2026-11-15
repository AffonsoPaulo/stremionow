// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.l10n

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/**
 * From Android 13 the system keeps the choice (the per-app language, which also shows in the
 * system's settings for the app, see `locales_config.xml`) and restarts the activities with it.
 * Before, the app keeps it and applies it to each activity through [wrap].
 */
actual object AppLanguage {
    private const val PREFERENCES = "interface"
    private const val KEY = "language"

    actual fun current(context: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (locales.isEmpty) "" else interfaceLanguageFor(locales[0])
        } else {
            saved(context)
        }

    actual fun set(
        context: Context,
        tag: String,
    ) {
        val language = tag.takeIf { it in InterfaceLanguages }.orEmpty()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                if (language.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language)
        } else {
            preferences(context).edit().putString(KEY, language).apply()
            context.activity()?.recreate()
        }
    }

    /**
     * [base] in the chosen language, for `attachBaseContext` of the app's activities; [base]
     * itself from Android 13, where the system does this, or when the system's language is chosen.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            moveSavedChoice(base)
            return base
        }
        val language = saved(base)
        if (language.isEmpty()) {
            // Back to the system's language: undo the default an earlier choice set in this
            // process, or dates and numbers would keep that language.
            Locale.setDefault(base.resources.configuration.locales[0])
            return base
        }
        val locale = Locale.forLanguageTag(language)
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration).apply { setLocales(LocaleList(locale)) }
        return base.createConfigurationContext(configuration)
    }

    /**
     * After an update to Android 13, a choice the app kept before becomes the system's per-app
     * language, unless the system already has one; the app's copy then goes, so this runs once.
     */
    private fun moveSavedChoice(context: Context) {
        val preferences = preferences(context)
        if (!preferences.contains(KEY)) return
        val language = saved(context)
        if (language.isNotEmpty()) {
            runCatching {
                val manager = context.getSystemService(LocaleManager::class.java)
                if (manager.applicationLocales.isEmpty) {
                    manager.applicationLocales =
                        LocaleList.forLanguageTags(language)
                }
            }
        }
        preferences.edit().remove(KEY).apply()
    }

    private fun saved(context: Context): String =
        preferences(context).getString(KEY, null)?.takeIf { it in InterfaceLanguages }.orEmpty()

    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private tailrec fun Context.activity(): Activity? =
        when (this) {
            is Activity -> this
            is ContextWrapper -> baseContext.activity()
            else -> null
        }
}

/**
 * The first of the configuration's languages with a translation, as Android picks the strings
 * (from Android 13 the configuration holds the per-app language; before, [AppLanguage.wrap]
 * puts the choice there); English when none has one.
 */
@Composable
internal actual fun interfaceLocale(): Locale {
    val configuration = LocalConfiguration.current
    return remember(configuration) {
        val locales = configuration.locales
        (0 until locales.size())
            .firstNotNullOfOrNull { interfaceLanguageFor(locales[it]).ifEmpty { null } }
            ?.let(Locale::forLanguageTag) ?: Locale.ENGLISH
    }
}
