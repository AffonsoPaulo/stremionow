// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.l10n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import kotlin.jvm.JvmInline

/**
 * A `<string>` of `l10n/res`, by its resource name. Use the generated `Res.string` keys; see
 * docs/adr/0003-strings-format.md.
 */
@JvmInline
value class StringResource(
    val key: String,
)

/** A `<plurals>` of `l10n/res`, by its resource name. Use the generated `Res.plurals` keys. */
@JvmInline
value class PluralsResource(
    val key: String,
)

/** The string in the current language: Android's own resources, or the desktop's tables. */
@Composable
@ReadOnlyComposable
expect fun stringResource(resource: StringResource): String

/** The string in the current language, with its positional placeholders (`%1$s`) filled in. */
@Composable
@ReadOnlyComposable
expect fun stringResource(
    resource: StringResource,
    vararg formatArgs: Any,
): String

/** The form of a plurals resource for [count], following the current language's plural rules. */
@Composable
@ReadOnlyComposable
expect fun pluralStringResource(
    resource: PluralsResource,
    count: Int,
): String

/** The form of a plurals resource for [count], with its placeholders filled in. */
@Composable
@ReadOnlyComposable
expect fun pluralStringResource(
    resource: PluralsResource,
    count: Int,
    vararg formatArgs: Any,
): String
