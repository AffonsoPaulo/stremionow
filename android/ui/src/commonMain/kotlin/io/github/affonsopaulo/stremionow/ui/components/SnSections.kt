// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.affonsopaulo.stremionow.core.MetaItem
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography

/** Title of a content section, announced as a heading, with an optional action at the end. */
@Composable
fun SnSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = SnLayout.gutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
    ) {
        BasicText(
            title,
            modifier = Modifier.weight(1f).semantics { heading() },
            style = SnTypography.sectionTitle.copy(color = SnColors.fg),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        action?.invoke()
    }
}

/** "See all ›" link for a section header. [label] is the spoken name, e.g. "See all Movies". */
@Composable
fun SnSeeAll(
    text: String,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .snClickable(RoundedCornerShape(SnRadius.radiusFull), onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = label }
                .padding(start = SnSpacing.space3, top = SnSpacing.space2, bottom = SnSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space1),
    ) {
        BasicText(text, style = SnTypography.label.copy(color = SnColors.fgSecondary))
        Image(SnIcons.ChevronRight, null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(SnColors.fgSecondary))
    }
}

/** Horizontal row of poster cards, aligned to the page gutter. */
@Composable
fun SnPosterRow(
    items: List<MetaItem>,
    onOpen: (MetaItem) -> Unit,
    subtitle: @Composable (MetaItem) -> String? = { it.releaseInfo },
    modifier: Modifier = Modifier,
) {
    SnRow(
        modifier = modifier,
        controlsCenter = SnSpacing.space2 + PosterCardWidth * 1.5f / 2,
    ) {
        items(items, key = { "${it.kind}/${it.id}" }) { item ->
            SnPosterCard(item.name, item.poster, subtitle(item), onClick = { onOpen(item) })
        }
    }
}

/** A titled section: header, optional "See all" action and a poster row. */
@Composable
fun SnPosterSection(
    title: String,
    items: List<MetaItem>,
    onOpen: (MetaItem) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: @Composable (MetaItem) -> String? = { it.releaseInfo },
    action: (@Composable () -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(SnSpacing.space3)) {
        SnSectionHeader(title, action = action)
        SnPosterRow(items, onOpen, subtitle)
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
    LazyVerticalGrid(
        state = state,
        modifier = modifier.fillMaxSize(),
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(horizontal = SnLayout.gutter, vertical = SnSpacing.space6),
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space6),
        content = content,
    )
}
