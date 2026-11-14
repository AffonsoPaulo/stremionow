// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing

/**
 * A vertical scrollbar for a page's list, where a pointer is the way to scroll (the desktop):
 * a slim thumb at the window's edge that stands out under the pointer and can be dragged.
 * Touch screens and remotes have no use for one, so elsewhere it draws nothing.
 */
@Composable
expect fun SnVerticalScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
)

/** See the [LazyListState] overload. */
@Composable
expect fun SnVerticalScrollbar(
    state: LazyGridState,
    modifier: Modifier = Modifier,
)

/** See the [LazyListState] overload. */
@Composable
expect fun SnVerticalScrollbar(
    state: ScrollState,
    modifier: Modifier = Modifier,
)

/**
 * A page's scrolling [content] with [SnVerticalScrollbar] along its end edge, starting below
 * [topInset] (the app header, for pages that run under it). With [minimalScroll], focus moves
 * scroll the list as little as [SnMinimalScroll] does, with the same inset.
 */
@Composable
fun SnScrollArea(
    state: LazyListState,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    minimalScroll: Boolean = false,
    content: @Composable () -> Unit,
) = ScrollArea(modifier, topInset, minimalScroll, content) { SnVerticalScrollbar(state, it) }

/** See the [LazyListState] overload. */
@Composable
fun SnScrollArea(
    state: LazyGridState,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    content: @Composable () -> Unit,
) = ScrollArea(modifier, topInset, minimalScroll = false, content) { SnVerticalScrollbar(state, it) }

/** See the [LazyListState] overload. */
@Composable
fun SnScrollArea(
    state: ScrollState,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    content: @Composable () -> Unit,
) = ScrollArea(modifier, topInset, minimalScroll = false, content) { SnVerticalScrollbar(state, it) }

@Composable
private fun ScrollArea(
    modifier: Modifier,
    topInset: Dp,
    minimalScroll: Boolean,
    content: @Composable () -> Unit,
    scrollbar: @Composable BoxScope.(Modifier) -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        if (minimalScroll) SnMinimalScroll(topInset = topInset, content = content) else content()
        scrollbar(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .padding(top = topInset + SnSpacing.space1, bottom = SnSpacing.space1, end = 2.dp),
        )
    }
}
