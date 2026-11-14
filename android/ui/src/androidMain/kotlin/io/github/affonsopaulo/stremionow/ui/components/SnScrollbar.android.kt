// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Phones, tablets and TVs scroll by touch or remote: no scrollbar.

@Composable
actual fun SnVerticalScrollbar(
    state: LazyListState,
    modifier: Modifier,
) = Unit

@Composable
actual fun SnVerticalScrollbar(
    state: LazyGridState,
    modifier: Modifier,
) = Unit

@Composable
actual fun SnVerticalScrollbar(
    state: ScrollState,
    modifier: Modifier,
) = Unit
