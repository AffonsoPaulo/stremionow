// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.l10n

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val language = saved(base).ifEmpty { return base }
        val locale = Locale.forLanguageTag(language)
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration).apply { setLocales(LocaleList(locale)) }
        return base.createConfigurationContext(configuration)
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
