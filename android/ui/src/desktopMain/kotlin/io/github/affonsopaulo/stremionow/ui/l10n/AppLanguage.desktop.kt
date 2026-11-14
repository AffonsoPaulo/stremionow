// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.l10n

import io.github.affonsopaulo.stremionow.ui.platform.PlatformContext

actual object AppLanguage {
    actual fun current(context: PlatformContext): String = DesktopStrings.chosen

    actual fun set(
        context: PlatformContext,
        tag: String,
    ) = DesktopStrings.choose(tag)
}
