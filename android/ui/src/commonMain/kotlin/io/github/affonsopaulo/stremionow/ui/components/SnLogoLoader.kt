// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography

/**
 * Loading screen built from the title's logo: a dimmed, grey copy that fills with color from
 * left to right as [progress] grows, the way Stremio shows a source getting ready. Without a
 * known progress the fill sweeps back and forth. Titles without a logo use their name.
 *
 * [status] is a short line under the logo, such as peers and speed while a torrent starts.
 */
@Composable
fun SnLogoLoader(
    logo: String?,
    title: String,
    progress: Float?,
    status: String?,
    label: String,
    modifier: Modifier = Modifier,
) {
    val sweep by rememberInfiniteTransition(label = "sweep").animateFloat(
        initialValue = 0.15f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
        label = "sweep",
    )
    val known by animateFloatAsState(
        targetValue = (progress ?: 0f).coerceIn(0f, 1f),
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "fill",
    )
    val fill = if (progress == null) sweep else known

    Column(
        modifier =
            modifier.semantics {
                contentDescription = label
                progressBarRangeInfo =
                    if (progress == null) ProgressBarRangeInfo.Indeterminate else ProgressBarRangeInfo(known, 0f..1f)
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space6),
    ) {
        Box(Modifier.widthIn(max = 420.dp).heightIn(max = 160.dp), contentAlignment = Alignment.Center) {
            LogoLayer(logo, title, dimmed = true)
            LogoLayer(
                logo,
                title,
                dimmed = false,
                modifier =
                    Modifier.drawWithContent {
                        clipRect(right = size.width * fill) { this@drawWithContent.drawContent() }
                    },
            )
        }
        if (!status.isNullOrBlank()) {
            BasicText(
                status,
                style = SnTypography.body.copy(color = SnColors.fgSecondary, textAlign = TextAlign.Center),
            )
        }
    }
}

@Composable
private fun LogoLayer(
    logo: String?,
    title: String,
    dimmed: Boolean,
    modifier: Modifier = Modifier,
) {
    if (logo != null) {
        AsyncImage(
            model = logo,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Fit,
            alpha = if (dimmed) 0.35f else 1f,
            colorFilter = if (dimmed) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null,
        )
    } else {
        BasicText(
            title.uppercase(),
            modifier = modifier,
            style =
                SnTypography.logoXl.copy(
                    color = if (dimmed) SnColors.fgSubtle else SnColors.fg,
                    textAlign = TextAlign.Center,
                ),
            maxLines = 3,
        )
    }
}
