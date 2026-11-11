// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import io.github.affonsopaulo.stremionow.ui.platform.isLargeScreen

/** The device family the interface runs on. */
enum class FormFactor { Phone, Large, Tv }

/** Set by each app at its root: the TV app provides [FormFactor.Tv]. */
val LocalIsTv = compositionLocalOf { false }

/** Layout measures that depend on the device. */
object SnLayout {
    /** TV when the app says so; otherwise large on tablets and desktop windows, phone below that. */
    val formFactor: FormFactor
        @Composable @ReadOnlyComposable
        get() =
            when {
                LocalIsTv.current -> FormFactor.Tv
                isLargeScreen() -> FormFactor.Large
                else -> FormFactor.Phone
            }

    /** Side margin of every page: TV safe area, desktop-like margin or phone margin. */
    val gutter: Dp
        @Composable @ReadOnlyComposable
        get() =
            when (formFactor) {
                FormFactor.Tv -> SnSpacing.space16
                FormFactor.Large -> SnSpacing.space12
                FormFactor.Phone -> SnSpacing.space4
            }
}
