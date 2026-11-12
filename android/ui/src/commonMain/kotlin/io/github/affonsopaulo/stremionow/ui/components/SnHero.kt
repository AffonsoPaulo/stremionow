// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.affonsopaulo.stremionow.ui.theme.FormFactor
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
import androidx.compose.ui.BiasAlignment as Bias
import coil3.compose.LocalPlatformContext as CoilContext

/*
 * The title art at the top of the home and details pages. It keeps the image's own shape as
 * the window grows (16:9, capped by the window's height), anchors the crop near the top,
 * where faces usually are, and fetches a sharper copy on wide screens.
 */

/**
 * Height of a hero, the header's area included: 16:9 of the page's [width], at most
 * [maxFraction] of the [viewport]'s height, so a row of titles always peeks below it, and
 * at least [min], so its title, synopsis and buttons fit on narrow windows.
 */
fun heroHeight(
    width: Dp,
    viewport: Dp,
    min: Dp,
    maxFraction: Float = 0.75f,
): Dp = (width * (9f / 16f)).coerceAtMost(viewport * maxFraction).coerceAtLeast(min)

/**
 * A sharper copy of a backdrop for wide screens, when its host is known to offer one; null
 * otherwise. Cinemeta's backdrops come from metahub at 480, 1280 and 3840 pixels wide (small,
 * medium and large), and catalogs link the medium one, which turns soft past 1280 pixels.
 */
fun largerBackdrop(url: String): String? {
    if (!url.startsWith(METAHUB_BACKGROUNDS)) return null
    val rest = url.removePrefix(METAHUB_BACKGROUNDS)
    val size = rest.substringBefore('/', missingDelimiterValue = "")
    if (size != "small" && size != "medium") return null
    return "${METAHUB_BACKGROUNDS}large/${rest.substringAfter('/')}"
}

private const val METAHUB_BACKGROUNDS = "https://images.metahub.space/background/"

/** Above this width in pixels, a hero asks for the large backdrop. */
private const val SHARP_BACKDROP_MIN_PX = 1600

/**
 * Where the crop of hero art sits: 30% of what does not fit is cut from the top and 70% from
 * the bottom, so heads stay in the frame when a wide window crops the image.
 */
private val HeroArtAlignment = Bias(horizontalBias = 0f, verticalBias = -0.4f)

/**
 * Hero art with its scrims: darker under the clear header, behind the title and synopsis at
 * the start side, and fading into the page at the bottom.
 *
 * [textWidth] is how wide the hero's text can grow from the page gutter: the side scrim keeps
 * text over the brightest image at 4.5:1 or more across that width and clears the rest of the
 * picture.
 */
@Composable
fun SnHeroBackdrop(
    url: String?,
    textWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val phone = SnLayout.formFactor == FormFactor.Phone
    val textEnd = SnLayout.gutter + textWidth
    BoxWithConstraints(modifier) {
        val widthPx = constraints.maxWidth
        Box(Modifier.fillMaxSize().background(SnColors.surfaceMuted).headerScrim()) {
            if (url != null) {
                HeroImage(url)
                val sharp = remember(url, widthPx) { largerBackdrop(url)?.takeIf { widthPx > SHARP_BACKDROP_MIN_PX } }
                // Loads alongside and fades in over the first copy; if it fails, that one stays.
                if (sharp != null) HeroImage(sharp)
            }
        }
        Box(Modifier.fillMaxSize().then(if (phone) Modifier.phoneStartScrim() else Modifier.heroStartScrim(textEnd)))
        // Fades into the page, so the art has no edge where the rows begin.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.55f to SnColors.bg.copy(alpha = 0f),
                    1f to SnColors.bg,
                ),
            ),
        )
    }
}

@Composable
private fun HeroImage(url: String) {
    val context = CoilContext.current
    AsyncImage(
        model =
            remember(url, context) {
                ImageRequest
                    .Builder(context)
                    .data(url)
                    .crossfade(true)
                    .build()
            },
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        alignment = HeroArtAlignment,
    )
}

/**
 * Darkens the art behind the hero's text, measured from the start edge rather than as a
 * share of the width, so a wide window keeps most of its picture clear. Over a pure white
 * image, primary text needs the page color at 0.6 opacity or more for 4.5:1, which the scrim
 * keeps up to [textEnd]; the secondary text (year, rating) is short and sits where it is
 * darker still. Then it fades out over a quarter of the width.
 */
private fun Modifier.heroStartScrim(textEnd: Dp): Modifier =
    drawWithCache {
        val width = size.width
        val reach = textEnd.toPx().coerceAtMost(width)
        val fadeEnd = (reach + width * 0.25f).coerceAtMost(width)
        val rtl = layoutDirection == LayoutDirection.Rtl

        fun at(x: Float) = (x / width).coerceIn(0f, 1f)
        val stops =
            arrayOf(
                0f to SnColors.bg.copy(alpha = 0.88f),
                at(reach * 0.45f) to SnColors.bg.copy(alpha = 0.78f),
                at(reach) to SnColors.bg.copy(alpha = 0.6f),
                (if (fadeEnd > reach) at(fadeEnd) else 1f) to
                    SnColors.bg.copy(alpha = if (fadeEnd > reach) 0f else 0.6f),
            )
        val brush =
            Brush.horizontalGradient(
                *stops,
                startX = if (rtl) width else 0f,
                endX = if (rtl) 0f else width,
            )
        onDrawBehind { drawRect(brush) }
    }

/** On phones the text spans the screen and sits low, over the bottom fade. */
private fun Modifier.phoneStartScrim(): Modifier =
    startScrim(
        0f to SnColors.bg.copy(alpha = 0.95f),
        0.4f to SnColors.bg.copy(alpha = 0.7f),
        0.75f to SnColors.bg.copy(alpha = 0f),
    )
