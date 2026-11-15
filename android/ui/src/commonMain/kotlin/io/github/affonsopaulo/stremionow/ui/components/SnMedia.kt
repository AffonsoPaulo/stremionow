// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.affonsopaulo.stremionow.ui.platform.rememberReducedMotion
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography

private val CardShape = RoundedCornerShape(SnRadius.radiusMd)

/** How much a poster's picture zooms, inside its frame, under the pointer. */
private const val POSTER_ZOOM = 1.06f

/** How much a focused card grows (see `snCardMotion`), for the poster's sharpness. */
private const val CARD_GROWTH = 1.08f

/**
 * A copy of a poster sharp enough for [widthPx] pixels, when its host offers several sizes;
 * the poster itself otherwise. Cinemeta's posters come from metahub at 300, 500 and 780
 * pixels wide (small, medium and large), and catalogs link the small one, which turns soft on
 * wide cards and scaled screens. Never a smaller copy than the one linked.
 */
fun sharperPoster(
    url: String,
    widthPx: Float,
): String {
    if (!url.startsWith(METAHUB_POSTERS)) return url
    val rest = url.removePrefix(METAHUB_POSTERS)
    val linked = rest.substringBefore('/', missingDelimiterValue = "")
    val sizes = POSTER_SIZES.keys.toList()
    if (linked !in sizes) return url
    val wanted = POSTER_SIZES.entries.firstOrNull { it.value >= widthPx }?.key ?: sizes.last()
    val size = if (sizes.indexOf(wanted) > sizes.indexOf(linked)) wanted else linked
    return "$METAHUB_POSTERS$size/${rest.substringAfter('/')}"
}

private const val METAHUB_POSTERS = "https://images.metahub.space/poster/"

/** Metahub's poster sizes, smallest first, with their width in pixels. */
private val POSTER_SIZES = linkedMapOf("small" to 300, "medium" to 500, "large" to 780)

/**
 * Artwork with a fallback: while the image loads, or when there is none, the box shows the
 * muted surface and the title in the display face, so layout never jumps.
 */
@Composable
fun SnArtwork(
    url: String?,
    fallbackTitle: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(SnColors.surfaceMuted), contentAlignment = Alignment.Center) {
        BasicText(
            text = fallbackTitle.uppercase(),
            modifier = Modifier.padding(SnSpacing.space3),
            style = SnTypography.logoMd.copy(color = SnColors.fgSecondary, textAlign = TextAlign.Center),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/** Width of a poster card in a row. */
val PosterCardWidth = 160.dp

/** Width of an episode card. */
val EpisodeCardWidth = 280.dp

/**
 * 2:3 poster card with the title and a short line underneath, and a progress bar along the
 * bottom of the picture when [progress] is given. Under the pointer it grows, lifts and zooms
 * its picture a little; with reduced motion it stays still.
 *
 * A card that opens a title gives its [origin]: its picture then flies into the title's page
 * and back, and on TV the focus returns to it (see [titleCard]).
 */
@Composable
fun SnPosterCard(
    title: String,
    poster: String?,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = PosterCardWidth,
    progress: Float? = null,
    origin: TitleOrigin? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val state = interaction.collectSnInteraction()
    val motion = snCardMotion(state, growOnFocus = true, liftOnHover = true, reducedMotion = rememberReducedMotion())
    val zoom by animateFloatAsState(if (motion.lift) POSTER_ZOOM else 1f, tween(FEEDBACK_MS), label = "poster-zoom")
    val card = titleCard(origin)
    Column(
        modifier = (if (width != null) modifier.width(width) else modifier),
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space2),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .semantics { contentDescription = title }
                .then(card.focus)
                .snClickable(
                    CardShape,
                    grow = true,
                    highlight = false,
                    interactionSource = interaction,
                    onClick = {
                        card.onOpen()
                        onClick()
                    },
                ).clip(CardShape),
        ) {
            // A copy as sharp as the card is wide on this screen, grown under the pointer.
            val density = LocalDensity.current
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val pixels = with(density) { maxWidth.toPx() } * POSTER_ZOOM * CARD_GROWTH
                SnArtwork(
                    url = remember(poster, pixels) { poster?.let { sharperPoster(it, pixels) } },
                    fallbackTitle = title,
                    modifier =
                        Modifier.fillMaxSize().then(card.art).graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                        },
                )
            }
            if (progress != null && progress > 0f) {
                ProgressBar(progress, Modifier.align(Alignment.BottomStart))
            }
        }
        BasicText(
            text = title,
            style = SnTypography.label.copy(color = SnColors.fg),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            BasicText(
                text = subtitle,
                style = SnTypography.caption.copy(color = SnColors.fgMuted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 16:9 card for episodes, with an optional progress bar, and a "Watched" badge when
 * [watchedLabel] is given. Under the pointer it grows, lifts and gets a light border. With
 * [keepMetaLine], the meta line keeps its room when empty, so cards in a row stay one size.
 */
@Composable
fun SnEpisodeCard(
    title: String,
    thumbnail: String?,
    meta: String?,
    overview: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    highlighted: Boolean = false,
    watchedLabel: String? = null,
    keepMetaLine: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val state = interaction.collectSnInteraction()
    Column(modifier.width(EpisodeCardWidth), verticalArrangement = Arrangement.spacedBy(SnSpacing.space2)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .semantics { contentDescription = title }
                .snClickable(
                    CardShape,
                    grow = true,
                    highlight = false,
                    interactionSource = interaction,
                    onClick = onClick,
                ).clip(CardShape)
                // Drawn over the picture.
                .snStateBorder(Color.Transparent, CardShape, state),
        ) {
            SnArtwork(url = thumbnail, fallbackTitle = "", modifier = Modifier.fillMaxSize())
            if (watchedLabel != null) {
                Row(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(SnSpacing.space2)
                        .background(SnColors.scrim, RoundedCornerShape(SnRadius.radiusSm))
                        .padding(horizontal = SnSpacing.space2, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SnSpacing.space1),
                ) {
                    Image(
                        SnIcons.Check,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        colorFilter = ColorFilter.tint(SnColors.fg),
                    )
                    BasicText(watchedLabel, style = SnTypography.badge.copy(color = SnColors.fg), maxLines = 1)
                }
            }
            if (progress != null && progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(SnColors.progressTrack),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(4.dp)
                            .background(SnColors.accent),
                    )
                }
            }
        }
        BasicText(
            text = title,
            style = SnTypography.body.copy(color = SnColors.fg),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!meta.isNullOrBlank() || keepMetaLine) {
            BasicText(
                text = meta.orEmpty(),
                style = SnTypography.caption.copy(color = if (highlighted) SnColors.accentText else SnColors.fgMuted),
                maxLines = 1,
                minLines = 1,
            )
        }
        if (!overview.isNullOrBlank()) {
            BasicText(
                text = overview,
                style = SnTypography.caption.copy(color = SnColors.fgMuted),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** How far into a video the user is, drawn along the bottom edge of its picture. */
@Composable
private fun ProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(SnColors.progressTrack),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(4.dp)
                .background(SnColors.accent),
        )
    }
}
