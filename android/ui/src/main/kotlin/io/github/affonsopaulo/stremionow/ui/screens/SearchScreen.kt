// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.affonsopaulo.stremionow.core.CatalogRow
import io.github.affonsopaulo.stremionow.core.MetaItem
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.ui.R
import io.github.affonsopaulo.stremionow.ui.components.SnChipRow
import io.github.affonsopaulo.stremionow.ui.components.SnLoadError
import io.github.affonsopaulo.stremionow.ui.components.SnLoading
import io.github.affonsopaulo.stremionow.ui.components.SnMessage
import io.github.affonsopaulo.stremionow.ui.components.SnPosterCard
import io.github.affonsopaulo.stremionow.ui.components.SnPosterGrid
import io.github.affonsopaulo.stremionow.ui.components.SnPosterSection
import io.github.affonsopaulo.stremionow.ui.components.SnSearchField
import io.github.affonsopaulo.stremionow.ui.components.SnSeeAll
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.io
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Search progress: rows found so far and how many catalogs answered or failed. */
data class SearchProgress(
    val rows: List<CatalogRow> = emptyList(),
    val asked: Int = 0,
    val answered: Int = 0,
    val failed: Int = 0,
) {
    val searching: Boolean get() = answered < asked

    /** Every catalog failed, which usually means there is no connection. */
    val allFailed: Boolean get() = asked > 0 && failed == asked
}

class SearchViewModel(
    private val core: StremioCore,
    initialQuery: String = "",
) : ViewModel() {
    private val mutableQuery = MutableStateFlow(initialQuery.take(MAX_QUERY_LENGTH))
    val query = mutableQuery.asStateFlow()
    private val mutableResults = MutableStateFlow<SearchProgress?>(null)

    /** Rows found so far, one per catalog; null until the user types something. */
    val results = mutableResults.asStateFlow()
    private val mutableKind = MutableStateFlow<String?>(null)

    /** Content type the results are narrowed to, or null for every type. */
    val kind = mutableKind.asStateFlow()

    init {
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            query
                .map { it.trim() }
                .distinctUntilChanged()
                .debounce(400)
                .collectLatest { text -> if (text.isEmpty()) mutableResults.value = null else search(text) }
        }
    }

    /**
     * Asks every search catalog at once and shows each answer as it arrives, keeping the
     * addons' order, so one slow addon does not hold back the others. Cancelled by the next query.
     */
    private suspend fun search(text: String) {
        val catalogs = runCatching { io { core.searchCatalogs() } }.getOrDefault(emptyList())
        mutableResults.value = SearchProgress(asked = catalogs.size)
        val found = arrayOfNulls<CatalogRow>(catalogs.size)
        coroutineScope {
            catalogs.forEachIndexed { index, catalog ->
                launch {
                    val result = runCatching { core.searchCatalog(catalog, text) }
                    result.getOrNull()?.takeIf { it.isNotEmpty() }?.let { found[index] = CatalogRow(catalog, it) }
                    mutableResults.update { current ->
                        current?.copy(
                            rows = found.filterNotNull(),
                            answered = current.answered + 1,
                            failed = current.failed + if (result.isFailure) 1 else 0,
                        )
                    }
                }
            }
        }
    }

    fun setQuery(text: String) {
        // Cap the length so a pasted wall of text cannot flood every addon.
        mutableQuery.value = text.take(MAX_QUERY_LENGTH)
    }

    fun setKind(kind: String?) {
        mutableKind.value = kind
    }

    fun retry() {
        val text = query.value
        mutableQuery.value = ""
        mutableQuery.value = text
    }

    private companion object {
        const val MAX_QUERY_LENGTH = 100
    }
}

@Composable
fun SearchScreen(
    onOpenTitle: (MetaItem) -> Unit,
    initialQuery: String = "",
    focusRequests: Int = 0,
) {
    val context = LocalContext.current
    val viewModel = viewModel(key = "search-$initialQuery") { SearchViewModel(AppGraph.core(context), initialQuery) }
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val kind by viewModel.kind.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Raise the keyboard when the user came to type, and again each time Search is chosen
    // while already open. A search opened for a person's name only shows the results.
    LaunchedEffect(focusRequests) {
        // Coming back to earlier results (from a title, for example) leaves the keyboard down.
        if (focusRequests > 0 || viewModel.query.value.isEmpty()) {
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }

    Column(Modifier.fillMaxSize()) {
        SnSearchField(
            query = query,
            onQueryChange = viewModel::setQuery,
            label = stringResource(R.string.search_field_label),
            hint = stringResource(R.string.search_hint),
            clearLabel = stringResource(R.string.search_clear),
            focusRequester = focus,
        )

        when (val state = results) {
            null -> {
                SnMessage(title = "", body = stringResource(R.string.search_idle))
            }

            else -> {
                when {
                    state.rows.isNotEmpty() -> {
                        SearchResults(state.rows, kind, viewModel::setKind, onOpenTitle)
                    }

                    state.searching -> {
                        SnLoading()
                    }

                    state.allFailed -> {
                        SnLoadError(onRetry = viewModel::retry)
                    }

                    else -> {
                        SnMessage(
                            title = stringResource(R.string.search_empty_title, query.trim()),
                            body = stringResource(R.string.search_empty_body),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Results grouped the way the addons group them: one row per catalog under a type heading,
 * like the official app. The chips narrow everything to one type, shown as a full grid;
 * "See all" on a row does the same for that row's type.
 */
@Composable
private fun SearchResults(
    rows: List<CatalogRow>,
    kind: String?,
    onKindChange: (String?) -> Unit,
    onOpenTitle: (MetaItem) -> Unit,
) {
    val kinds = remember(rows) { rows.map { it.catalog.kind }.distinct() }
    // A filter left over from an earlier search may match nothing now.
    val activeKind = kind?.takeIf { it in kinds }
    Column(Modifier.fillMaxSize()) {
        if (kinds.size > 1) {
            val all = stringResource(R.string.explore_genre_all)
            val labels = kinds.associateWith { kindLabel(it) }
            SnChipRow(
                options = listOf<String?>(null) + kinds,
                selected = activeKind,
                label = { option -> option?.let { labels.getValue(it) } ?: all },
                onSelect = onKindChange,
                modifier = Modifier.padding(horizontal = SnLayout.gutter, vertical = SnSpacing.space2),
            )
        }
        if (activeKind == null) {
            ResultRows(rows, canNarrow = kinds.size > 1, onSeeAll = onKindChange, onOpenTitle = onOpenTitle)
        } else {
            val items =
                remember(rows, activeKind) {
                    rows
                        .filter { it.catalog.kind == activeKind }
                        .flatMap { it.items }
                        .distinctBy { it.id }
                }
            SnPosterGrid {
                items(items, key = { "${it.kind}/${it.id}" }) { item ->
                    SnPosterCard(
                        item.name,
                        item.poster,
                        item.releaseInfo,
                        onClick = { onOpenTitle(item) },
                        width = null,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultRows(
    rows: List<CatalogRow>,
    canNarrow: Boolean,
    onSeeAll: (String) -> Unit,
    onOpenTitle: (MetaItem) -> Unit,
) {
    // Name a row by its type alone unless several catalogs share that type.
    val sharedKinds =
        remember(rows) {
            rows
                .groupingBy { it.catalog.kind }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = SnSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space8),
    ) {
        items(rows, key = { "${it.catalog.addonManifestUrl}/${it.catalog.kind}/${it.catalog.catalogId}" }) { row ->
            val type = kindLabel(row.catalog.kind)
            SnPosterSection(
                title = if (row.catalog.kind in sharedKinds) "$type · ${row.catalog.name}" else type,
                items = row.items,
                onOpen = onOpenTitle,
                action =
                    if (canNarrow) {
                        {
                            SnSeeAll(
                                text = stringResource(R.string.search_see_all),
                                label = stringResource(R.string.search_see_all_label, type),
                                onClick = { onSeeAll(row.catalog.kind) },
                            )
                        }
                    } else {
                        null
                    },
            )
        }
    }
}
