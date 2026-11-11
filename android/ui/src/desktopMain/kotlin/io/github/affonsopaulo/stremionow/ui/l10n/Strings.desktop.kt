// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.l10n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable

// The language follows the system and does not change while the app runs, so nothing here
// needs to observe composition state.

@Composable
@ReadOnlyComposable
actual fun stringResource(resource: StringResource): String = DesktopStrings.string(resource.key)

@Composable
@ReadOnlyComposable
actual fun stringResource(
    resource: StringResource,
    vararg formatArgs: Any,
): String = DesktopStrings.format(DesktopStrings.string(resource.key), formatArgs)

@Composable
@ReadOnlyComposable
actual fun pluralStringResource(
    resource: PluralsResource,
    count: Int,
): String = DesktopStrings.plural(resource.key, count)

@Composable
@ReadOnlyComposable
actual fun pluralStringResource(
    resource: PluralsResource,
    count: Int,
    vararg formatArgs: Any,
): String = DesktopStrings.format(DesktopStrings.plural(resource.key, count), formatArgs)
