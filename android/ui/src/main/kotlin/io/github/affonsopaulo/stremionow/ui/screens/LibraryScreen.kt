// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.affonsopaulo.stremionow.core.LibraryItem
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.ui.R
import io.github.affonsopaulo.stremionow.ui.components.SnLoadError
import io.github.affonsopaulo.stremionow.ui.components.SnLoading
import io.github.affonsopaulo.stremionow.ui.components.SnMessage
import io.github.affonsopaulo.stremionow.ui.components.SnPageTitle
import io.github.affonsopaulo.stremionow.ui.components.SnPosterCard
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.Loadable
import io.github.affonsopaulo.stremionow.ui.data.io
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val core: StremioCore,
) : ViewModel() {
    private val mutableItems = MutableStateFlow<Loadable<List<LibraryItem>>>(Loadable.Loading)
    val items = mutableItems.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            mutableItems.value =
                runCatching { io { core.library() } }.fold({ Loadable.Ready(it) }, { Loadable.Failed(it) })
        }
    }
}

@Composable
fun LibraryScreen(
    onOpenTitle: (kind: String, id: String) -> Unit,
    onExplore: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel = viewModel { LibraryViewModel(AppGraph.core(context)) }
    val state by viewModel.items.collectAsStateWithLifecycle()
    // Titles can be added from any screen, so reload whenever this one becomes visible.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Column(Modifier.fillMaxSize()) {
        SnPageTitle(stringResource(R.string.nav_my_list), Modifier.padding(horizontal = SnLayout.gutter))
        when (val items = state) {
            Loadable.Loading -> {
                SnLoading()
            }

            is Loadable.Failed -> {
                SnLoadError(onRetry = viewModel::refresh)
            }

            is Loadable.Ready -> {
                if (items.value.isEmpty()) {
                    SnMessage(
                        title = stringResource(R.string.library_empty_title),
                        body = stringResource(R.string.library_empty_body),
                        actionText = stringResource(R.string.action_explore_series),
                        onAction = onExplore,
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(150.dp),
                        contentPadding = PaddingValues(horizontal = SnLayout.gutter, vertical = SnSpacing.space6),
                        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space4),
                        verticalArrangement = Arrangement.spacedBy(SnSpacing.space6),
                    ) {
                        items(items.value, key = { it.id }) { item ->
                            SnPosterCard(
                                item.name,
                                item.poster,
                                null,
                                onClick = { onOpenTitle(item.kind, item.id) },
                                width = null,
                            )
                        }
                    }
                }
            }
        }
    }
}
