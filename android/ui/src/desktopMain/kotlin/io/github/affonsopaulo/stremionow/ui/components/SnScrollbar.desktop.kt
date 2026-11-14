// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.affonsopaulo.stremionow.ui.theme.SnColors

/** Slim and quiet, brighter under the pointer; no track, so it never covers content. */
private val style: ScrollbarStyle
    get() =
        ScrollbarStyle(
            minimalHeight = 48.dp,
            thickness = 8.dp,
            shape = RoundedCornerShape(4.dp),
            hoverDurationMillis = 160,
            unhoverColor = SnColors.fg.copy(alpha = 0.22f),
            hoverColor = SnColors.fg.copy(alpha = 0.5f),
        )

@Composable
actual fun SnVerticalScrollbar(
    state: LazyListState,
    modifier: Modifier,
) = VerticalScrollbar(rememberScrollbarAdapter(state), modifier, style = style)

@Composable
actual fun SnVerticalScrollbar(
    state: LazyGridState,
    modifier: Modifier,
) = VerticalScrollbar(rememberScrollbarAdapter(state), modifier, style = style)

@Composable
actual fun SnVerticalScrollbar(
    state: ScrollState,
    modifier: Modifier,
) = VerticalScrollbar(rememberScrollbarAdapter(state), modifier, style = style)
