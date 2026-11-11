// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp

/** The device family the interface runs on. */
enum class FormFactor { Phone, Large, Tv }

/** Set by each app at its root: the TV app provides [FormFactor.Tv]. */
val LocalIsTv = compositionLocalOf { false }

/** Layout measures that depend on the device. */
object SnLayout {
    val formFactor: FormFactor
        @Composable @ReadOnlyComposable
        get() =
            when {
                LocalIsTv.current -> FormFactor.Tv

                // The smallest width does not change with rotation, so a phone stays a phone
                // in landscape (about 900 dp wide) instead of switching layouts mid-video.
                LocalConfiguration.current.smallestScreenWidthDp >= 600 -> FormFactor.Large

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
