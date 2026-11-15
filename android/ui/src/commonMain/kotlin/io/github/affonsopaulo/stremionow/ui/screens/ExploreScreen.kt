// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.affonsopaulo.stremionow.core.CatalogInfo
import io.github.affonsopaulo.stremionow.core.MetaItem
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.ui.components.SnChipRow
import io.github.affonsopaulo.stremionow.ui.components.SnLoadError
import io.github.affonsopaulo.stremionow.ui.components.SnLoading
import io.github.affonsopaulo.stremionow.ui.components.SnMessage
import io.github.affonsopaulo.stremionow.ui.components.SnPageTitle
import io.github.affonsopaulo.stremionow.ui.components.SnPosterCard
import io.github.affonsopaulo.stremionow.ui.components.SnPosterGrid
import io.github.affonsopaulo.stremionow.ui.components.titleOrigin
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.io
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.platform.LocalPlatformContext
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** State of a browsable catalog. */
data class ExploreState(
    val catalogs: List<CatalogInfo> = emptyList(),
    val catalog: CatalogInfo? = null,
    val genre: String? = null,
    val items: List<MetaItem> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    val endReached: Boolean = false,
)

class ExploreViewModel(
    private val core: StremioCore,
    private val kind: String,
    private val initialGenre: String? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ExploreState())
    val state = mutableState.asStateFlow()
    private var pageJob: Job? = null

    init {
        viewModelScope.launch {
            val catalogs = runCatching { io { core.catalogs(kind) } }.getOrDefault(emptyList())
            // Opened from a genre link: start on a catalog that can filter by it.
            val catalog =
                catalogs.firstOrNull { initialGenre != null && initialGenre in it.genres } ?: catalogs.firstOrNull()
            val genre = initialGenre?.takeIf { catalog?.genres?.contains(it) == true }
            mutableState.update { it.copy(catalogs = catalogs, catalog = catalog, genre = genre) }
            if (catalogs.isEmpty()) mutableState.update { it.copy(loading = false) } else reload()
        }
    }

    fun selectCatalog(catalog: CatalogInfo) {
        if (catalog == state.value.catalog) return
        mutableState.update { it.copy(catalog = catalog, genre = null) }
        reload()
    }

    fun selectGenre(genre: String?) {
        if (genre == state.value.genre) return
        mutableState.update { it.copy(genre = genre) }
        reload()
    }

    fun reload() {
        pageJob?.cancel()
        mutableState.update { it.copy(items = emptyList(), endReached = false, failed = false) }
        loadMore()
    }

    /** Loads the next page; ignored while a page is loading or after the last page. */
    fun loadMore() {
        val current = state.value
        val catalog = current.catalog ?: return
        if (current.endReached || (pageJob?.isActive == true)) return
        mutableState.update { it.copy(loading = true, failed = false) }
        pageJob =
            viewModelScope.launch {
                runCatching { core.catalogPage(catalog, current.genre, current.items.size.toUInt()) }
                    .onSuccess { page ->
                        mutableState.update { state ->
                            val known = state.items.mapTo(HashSet()) { it.id }
                            val fresh = page.filter { it.id !in known }
                            // An empty or fully repeated page means the catalog has no more items.
                            state.copy(items = state.items + fresh, loading = false, endReached = fresh.isEmpty())
                        }
                    }.onFailure { mutableState.update { it.copy(loading = false, failed = true) } }
            }
    }
}

@Composable
fun ExploreScreen(
    kind: String,
    onOpenTitle: (MetaItem) -> Unit,
    initialGenre: String? = null,
) {
    val context = LocalPlatformContext.current
    val viewModel =
        viewModel(key = "explore-$kind-${initialGenre.orEmpty()}") {
            ExploreViewModel(AppGraph.core(context), kind, initialGenre)
        }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val grid = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf {
            val last =
                grid.layoutInfo.visibleItemsInfo
                    .lastOrNull()
                    ?.index ?: 0
            last >= grid.layoutInfo.totalItemsCount - 12
        }
    }
    LaunchedEffect(nearEnd, state.items.size) { if (nearEnd && state.items.isNotEmpty()) viewModel.loadMore() }

    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(horizontal = SnLayout.gutter),
            verticalArrangement = Arrangement.spacedBy(SnSpacing.space3),
        ) {
            SnPageTitle(
                initialGenre ?: stringResource(if (kind == "series") Res.string.nav_series else Res.string.nav_movies),
            )
            if (state.catalogs.size > 1) {
                SnChipRow(
                    state.catalogs,
                    state.catalog,
                    { catalog -> catalog?.let { catalogShortName(it) }.orEmpty() },
                    { it?.let(viewModel::selectCatalog) },
                )
            }
            val genres = state.catalog?.genres.orEmpty()
            if (genres.isNotEmpty()) {
                val all = stringResource(Res.string.explore_genre_all)
                SnChipRow(listOf<String?>(null) + genres, state.genre, { it ?: all }, viewModel::selectGenre)
            }
        }

        when {
            state.items.isEmpty() && state.loading -> {
                SnLoading()
            }

            state.items.isEmpty() && state.failed -> {
                SnLoadError(onRetry = viewModel::reload)
            }

            state.items.isEmpty() -> {
                SnMessage(
                    title = stringResource(Res.string.home_empty_title),
                    body = stringResource(Res.string.explore_empty_body),
                )
            }

            else -> {
                SnPosterGrid(state = grid) {
                    items(state.items, key = { it.id }) { item ->
                        SnPosterCard(
                            item.name,
                            item.poster,
                            item.releaseInfo,
                            onClick = { onOpenTitle(item) },
                            width = null,
                            origin = titleOrigin("grid", item),
                        )
                    }
                    if (state.loading || state.failed) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            if (state.failed) {
                                SnLoadError(onRetry = viewModel::loadMore)
                            } else {
                                BasicText(
                                    stringResource(Res.string.explore_loading_more),
                                    style = SnTypography.caption.copy(color = SnColors.fgMuted),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
