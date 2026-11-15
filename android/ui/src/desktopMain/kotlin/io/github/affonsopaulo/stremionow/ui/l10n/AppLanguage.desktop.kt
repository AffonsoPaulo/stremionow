// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.l10n

import androidx.compose.runtime.Composable
import io.github.affonsopaulo.stremionow.ui.platform.PlatformContext
import java.util.Locale

actual object AppLanguage {
    actual fun current(context: PlatformContext): String = DesktopStrings.chosen

    actual fun set(
        context: PlatformContext,
        tag: String,
    ) = DesktopStrings.choose(tag)
}

/** The translation [DesktopStrings] reads from, English when the system's has none. */
@Composable
internal actual fun interfaceLocale(): Locale = DesktopStrings.locale
