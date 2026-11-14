// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Other companies' marks, for their sign-in buttons only, in the colors their guidelines ask
 * for. These are the only colors outside the design tokens: a brand's button must look like
 * that brand's. The logos are drawn after Simple Icons (CC0), 24 × 24; tint them like
 * [SnIcons].
 */
object SnBrands {
    /** Facebook's blue, the background of "Continue with Facebook". */
    val FacebookBlue = Color(0xFF1877F2)
    val OnFacebook = Color(0xFFFFFFFF)

    /** "Continue with Apple" on a dark background: white, with black text and logo. */
    val AppleButton = Color(0xFFFFFFFF)
    val OnApple = Color(0xFF000000)

    /** Facebook's "f" cut out of a disc. */
    val FacebookLogo =
        logo(
            "facebook",
            "M9.101 23.691v-7.98H6.627v-3.667h2.474v-1.58c0 -4.085 1.848 -5.978 5.858 -5.978" +
                "c0.401 0 0.955 0.042 1.468 0.103a8.68 8.68 0 0 1 1.141 0.195v3.325" +
                "a8.623 8.623 0 0 0 -0.653 -0.036a26.805 26.805 0 0 0 -0.733 -0.009" +
                "c-0.707 0 -1.259 0.096 -1.675 0.309a1.686 1.686 0 0 0 -0.679 0.622" +
                "c-0.258 0.42 -0.374 0.995 -0.374 1.752v1.297h3.919l-0.386 2.103l-0.287 1.564h-3.246v8.245" +
                "C19.396 23.238 24 18.179 24 12.044c0 -6.627 -5.373 -12 -12 -12s-12 5.373 -12 12" +
                "c0 5.628 3.874 10.35 9.101 11.647z",
        )

    /** Apple's apple. */
    val AppleLogo =
        logo(
            "apple",
            "M12.152 6.896c-0.948 0 -2.415 -1.078 -3.96 -1.04c-2.04 0.027 -3.91 1.183 -4.961 3.014" +
                "c-2.117 3.675 -0.546 9.103 1.519 12.09c1.013 1.454 2.208 3.09 3.792 3.039" +
                "c1.52 -0.065 2.09 -0.987 3.935 -0.987c1.831 0 2.35 0.987 3.96 0.948" +
                "c1.637 -0.026 2.676 -1.48 3.676 -2.948c1.156 -1.688 1.636 -3.325 1.662 -3.415" +
                "c-0.039 -0.013 -3.182 -1.221 -3.22 -4.857c-0.026 -3.04 2.48 -4.494 2.597 -4.559" +
                "c-1.429 -2.09 -3.623 -2.324 -4.39 -2.376c-2 -0.156 -3.675 1.09 -4.61 1.09z" +
                "M15.53 3.83c0.843 -1.012 1.4 -2.427 1.245 -3.83c-1.207 0.052 -2.662 0.805 -3.532 1.818" +
                "c-0.78 0.896 -1.454 2.338 -1.273 3.714c1.338 0.104 2.715 -0.688 3.559 -1.701z",
        )

    private fun logo(
        name: String,
        path: String,
    ): ImageVector =
        ImageVector
            .Builder(
                name = name,
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply { addPath(pathData = addPathNodes(path), fill = SolidColor(Color.White)) }
            .build()
}
