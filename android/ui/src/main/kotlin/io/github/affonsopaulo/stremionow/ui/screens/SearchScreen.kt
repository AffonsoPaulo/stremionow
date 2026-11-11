// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.affonsopaulo.stremionow.core.MetaItem
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.ui.R
import io.github.affonsopaulo.stremionow.ui.components.SnIconButton
import io.github.affonsopaulo.stremionow.ui.components.SnLoadError
import io.github.affonsopaulo.stremionow.ui.components.SnLoading
import io.github.affonsopaulo.stremionow.ui.components.SnMessage
import io.github.affonsopaulo.stremionow.ui.components.SnPosterCard
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.Loadable
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class SearchViewModel(
    private val core: StremioCore,
) : ViewModel() {
    private val mutableQuery = MutableStateFlow("")
    val query = mutableQuery.asStateFlow()
    private val mutableResults = MutableStateFlow<Loadable<List<MetaItem>>?>(null)

    /** Null until the user types something. */
    val results = mutableResults.asStateFlow()

    init {
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            query
                .map { it.trim() }
                .distinctUntilChanged()
                .debounce(400)
                .collectLatest { text ->
                    if (text.isEmpty()) {
                        mutableResults.value = null
                    } else {
                        mutableResults.value = Loadable.Loading
                        mutableResults.value =
                            runCatching { core.search(text) }.fold({ Loadable.Ready(it) }, { Loadable.Failed(it) })
                    }
                }
        }
    }

    fun setQuery(text: String) {
        // Cap the length so a pasted wall of text cannot flood every addon.
        mutableQuery.value = text.take(MAX_QUERY_LENGTH)
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
fun SearchScreen(onOpenTitle: (MetaItem) -> Unit) {
    val context = LocalContext.current
    val viewModel = viewModel { SearchViewModel(AppGraph.core(context)) }
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(Modifier.fillMaxSize()) {
        val label = stringResource(R.string.search_field_label)
        val shape = RoundedCornerShape(SnRadius.radiusLg)
        Row(
            modifier =
                Modifier
                    .padding(horizontal = SnLayout.gutter, vertical = SnSpacing.space2)
                    .widthIn(max = 880.dp)
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(SnColors.surfaceInput, shape)
                    .border(2.dp, SnColors.accent, shape)
                    .padding(start = SnSpacing.space6, end = SnSpacing.space2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
        ) {
            Image(SnIcons.Search, null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(SnColors.fgSecondary))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    BasicText(
                        stringResource(R.string.search_hint),
                        style = SnTypography.sectionTitle.copy(color = SnColors.fgMuted),
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = viewModel::setQuery,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .semantics { contentDescription = label },
                    textStyle = SnTypography.sectionTitle.copy(color = SnColors.fg),
                    singleLine = true,
                    cursorBrush = SolidColor(SnColors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
            }
            if (query.isNotEmpty()) {
                SnIconButton(SnIcons.Close, stringResource(R.string.search_clear), onClick = { viewModel.setQuery("") })
            }
        }

        when (val state = results) {
            null -> {
                SnMessage(title = "", body = stringResource(R.string.search_idle))
            }

            Loadable.Loading -> {
                SnLoading()
            }

            is Loadable.Failed -> {
                SnLoadError(onRetry = viewModel::retry)
            }

            is Loadable.Ready -> {
                if (state.value.isEmpty()) {
                    SnMessage(
                        title = stringResource(R.string.search_empty_title, query.trim()),
                        body = stringResource(R.string.search_empty_body),
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(150.dp),
                        contentPadding = PaddingValues(horizontal = SnLayout.gutter, vertical = SnSpacing.space6),
                        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space4),
                        verticalArrangement = Arrangement.spacedBy(SnSpacing.space6),
                    ) {
                        items(state.value, key = { "${it.kind}/${it.id}" }) { item ->
                            SnPosterCard(
                                item.name,
                                item.poster,
                                item.cardSubtitle(),
                                onClick = { onOpenTitle(item) },
                                width = null,
                            )
                        }
                    }
                }
            }
        }
    }
}
