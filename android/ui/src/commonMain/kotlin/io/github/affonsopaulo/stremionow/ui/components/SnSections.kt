// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.affonsopaulo.stremionow.core.MetaItem
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography

/**
 * Title of a content section, announced as a heading, with an optional action at the end.
 * A [caption], such as the addon a row comes from, follows the title in a quieter style.
 */
@Composable
fun SnSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = SnLayout.gutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
    ) {
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                title,
                modifier = Modifier.weight(1f, fill = false).semantics { heading() },
                style = SnTypography.sectionTitle.copy(color = SnColors.fg),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!caption.isNullOrBlank()) {
                BasicText(
                    caption,
                    modifier = Modifier.widthIn(max = MaxCaptionWidth),
                    style = SnTypography.caption.copy(color = SnColors.fgMuted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        action?.invoke()
    }
}

/** A long addon name gives way to the row's title. */
private val MaxCaptionWidth = 240.dp

/**
 * "See all ›" link for a section header. [label] is the spoken name, e.g. "See all Movies".
 * Like other links, it brightens and underlines under the pointer.
 */
@Composable
fun SnSeeAll(
    text: String,
    label: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val state = interaction.collectSnInteraction()
    val color by animateColorAsState(snLinkColor(SnColors.fgSecondary, state), tween(FEEDBACK_MS), label = "see-all")
    Row(
        modifier =
            Modifier
                .snClickable(
                    RoundedCornerShape(SnRadius.radiusFull),
                    highlight = false,
                    interactionSource = interaction,
                    onClick = onClick,
                ).semantics(mergeDescendants = true) { contentDescription = label }
                .padding(start = SnSpacing.space3, top = SnSpacing.space2, bottom = SnSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space1),
    ) {
        BasicText(
            text,
            style = SnTypography.label.copy(color = color, textDecoration = snLinkDecoration(null, state)),
        )
        Image(SnIcons.ChevronRight, null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(color))
    }
}

/**
 * Horizontal row of poster cards, aligned to the page gutter. [slot] tells the row apart from
 * the page's other rows (a row's key), so a title in two of them flies from the card chosen.
 */
@Composable
fun SnPosterRow(
    items: List<MetaItem>,
    onOpen: (MetaItem) -> Unit,
    subtitle: @Composable (MetaItem) -> String? = { it.releaseInfo },
    modifier: Modifier = Modifier,
    slot: String = "row",
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val sizing = rememberPosterRowSizing(maxWidth)
        SnRow(
            spacing = sizing.spacing,
            controlsCenter = SnSpacing.space2 + sizing.cardWidth * 1.5f / 2,
        ) {
            items(items, key = { "${it.kind}/${it.id}" }) { item ->
                SnPosterCard(
                    item.name,
                    item.poster,
                    subtitle(item),
                    onClick = { onOpen(item) },
                    width = sizing.cardWidth,
                    origin = titleOrigin(slot, item),
                )
            }
        }
    }
}

/** How wide a row's poster cards are, and how far apart. */
data class PosterRowSizing(
    val cardWidth: Dp,
    val spacing: Dp,
)

/**
 * The poster cards of a row [rowWidth] wide. Where a pointer drives the screen, the cards fill
 * the window: eight whole ones and half of a ninth past the page gutter, the cut card telling
 * there is more to the side, never narrower than phones' and TV's [PosterCardWidth] nor wider
 * than [MAX_POSTER_CARD_WIDTH]. Elsewhere they keep [PosterCardWidth].
 */
@Composable
fun rememberPosterRowSizing(rowWidth: Dp): PosterRowSizing {
    val mouseFirst = LocalSnFeedback.current.mouseFirst
    val gutter = SnLayout.gutter
    return remember(rowWidth, mouseFirst, gutter) {
        if (mouseFirst) {
            PosterRowSizing(posterCardWidth(rowWidth, gutter, POINTER_POSTER_SPACING), POINTER_POSTER_SPACING)
        } else {
            PosterRowSizing(PosterCardWidth, SnSpacing.space4)
        }
    }
}

/** Whole cards a row shows where a pointer drives the screen; half of the next one shows too. */
internal const val POSTER_ROW_WHOLE_CARDS = 8

/** Widest a poster card grows in a wide window. */
internal val MAX_POSTER_CARD_WIDTH = 240.dp

/** Room between poster cards where a pointer drives the screen. */
internal val POINTER_POSTER_SPACING = 24.dp

/**
 * The width of each card so that [POSTER_ROW_WHOLE_CARDS] whole cards and half of the next fit in
 * [rowWidth] after the page [gutter], with [spacing] between them.
 */
internal fun posterCardWidth(
    rowWidth: Dp,
    gutter: Dp,
    spacing: Dp,
): Dp {
    val shown = POSTER_ROW_WHOLE_CARDS + 0.5f
    val width = (rowWidth - gutter - spacing * POSTER_ROW_WHOLE_CARDS) / shown
    return width.coerceIn(PosterCardWidth, MAX_POSTER_CARD_WIDTH)
}

/** A titled section: header, optional "See all" action and a poster row; see [SnPosterRow] for [slot]. */
@Composable
fun SnPosterSection(
    title: String,
    items: List<MetaItem>,
    onOpen: (MetaItem) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: @Composable (MetaItem) -> String? = { it.releaseInfo },
    caption: String? = null,
    action: (@Composable () -> Unit)? = null,
    slot: String = "row",
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(SnSpacing.space3)) {
        SnSectionHeader(title, caption = caption, action = action)
        SnPosterRow(items, onOpen, subtitle, slot = slot)
    }
}

/**
 * Grid of poster cards that adapts its column count to the width, with the page gutter.
 * Fill it with `items { SnPosterCard(..., width = null) }` and any footer items.
 */
@Composable
fun SnPosterGrid(
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    content: LazyGridScope.() -> Unit,
) {
    SnScrollArea(state, modifier) {
        LazyVerticalGrid(
            state = state,
            modifier = Modifier.fillMaxSize(),
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(horizontal = SnLayout.gutter, vertical = SnSpacing.space6),
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space4),
            verticalArrangement = Arrangement.spacedBy(SnSpacing.space6),
            content = content,
        )
    }
}
