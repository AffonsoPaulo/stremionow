// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.affonsopaulo.stremionow.core.LibraryItem
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.ui.components.SnChipRow
import io.github.affonsopaulo.stremionow.ui.components.SnLoadError
import io.github.affonsopaulo.stremionow.ui.components.SnLoading
import io.github.affonsopaulo.stremionow.ui.components.SnMenuChip
import io.github.affonsopaulo.stremionow.ui.components.SnMessage
import io.github.affonsopaulo.stremionow.ui.components.SnPageTitle
import io.github.affonsopaulo.stremionow.ui.components.SnPosterCard
import io.github.affonsopaulo.stremionow.ui.components.SnPosterGrid
import io.github.affonsopaulo.stremionow.ui.components.TitleOrigin
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.Loadable
import io.github.affonsopaulo.stremionow.ui.data.io
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.pluralStringResource
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.platform.LocalPlatformContext
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The type and pill chosen on "My list", kept while the app runs. */
private object LibraryChoices {
    val type = MutableStateFlow(LibraryType.All)
    val sort = MutableStateFlow(LibrarySort.LastWatched)
}

class LibraryViewModel(
    private val core: StremioCore,
) : ViewModel() {
    private val mutableItems = MutableStateFlow<Loadable<List<LibraryItem>>>(Loadable.Loading)
    val items = mutableItems.asStateFlow()
    val type: StateFlow<LibraryType> = LibraryChoices.type.asStateFlow()
    val sort: StateFlow<LibrarySort> = LibraryChoices.sort.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            mutableItems.value =
                runCatching { io { core.library() } }.fold({ Loadable.Ready(it) }, { Loadable.Failed(it) })
        }
    }

    fun selectType(type: LibraryType) {
        LibraryChoices.type.value = type
    }

    fun selectSort(sort: LibrarySort) {
        LibraryChoices.sort.value = sort
    }
}

@Composable
fun LibraryScreen(
    onOpenTitle: (kind: String, id: String) -> Unit,
    onExplore: () -> Unit,
) {
    val context = LocalPlatformContext.current
    val viewModel = viewModel { LibraryViewModel(AppGraph.core(context)) }
    val state by viewModel.items.collectAsStateWithLifecycle()
    val type by viewModel.type.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    // Titles can be added from any screen, so reload whenever this one becomes visible.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Column(Modifier.fillMaxSize()) {
        SnPageTitle(stringResource(Res.string.nav_my_list), Modifier.padding(horizontal = SnLayout.gutter))
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
                        title = stringResource(Res.string.library_empty_title),
                        body = stringResource(Res.string.library_empty_body),
                        actionText = stringResource(Res.string.action_explore_series),
                        onAction = onExplore,
                    )
                } else {
                    LibraryFilterBar(type, sort, viewModel::selectType, viewModel::selectSort)
                    val names = remember { nameCollator() }
                    val shown = remember(items.value, type, sort) { arrangeLibrary(items.value, type, sort, names) }
                    if (shown.isEmpty()) {
                        SnMessage(
                            title = stringResource(Res.string.library_filtered_empty_title),
                            body = stringResource(Res.string.library_filtered_empty_body),
                        )
                    } else {
                        SnPosterGrid {
                            // Titles are told apart by type and id, as the core does.
                            items(shown.distinctBy { "${it.kind}/${it.id}" }, key = { "${it.kind}/${it.id}" }) { item ->
                                SnPosterCard(
                                    item.name,
                                    item.poster,
                                    item.statusLine(),
                                    onClick = { onOpenTitle(item.kind, item.id) },
                                    width = null,
                                    progress = item.progressFraction(),
                                    origin =
                                        TitleOrigin(
                                            "grid/${item.kind}/${item.id}",
                                            item.kind,
                                            item.id,
                                            item.poster,
                                        ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The type menu, then the pills, which scroll sideways when they do not fit. */
@Composable
private fun LibraryFilterBar(
    type: LibraryType,
    sort: LibrarySort,
    onType: (LibraryType) -> Unit,
    onSort: (LibrarySort) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = SnLayout.gutter, top = SnSpacing.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
    ) {
        SnMenuChip(
            options = LibraryType.entries,
            selected = type,
            label = { typeLabel(it) },
            onSelect = onType,
            menuLabel = stringResource(Res.string.library_type_menu),
        )
        SnChipRow(
            options = LibrarySort.entries,
            selected = sort,
            label = { sortLabel(it) },
            onSelect = onSort,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun typeLabel(type: LibraryType): String =
    when (type) {
        LibraryType.All -> stringResource(Res.string.library_type_all)
        LibraryType.Movies -> stringResource(Res.string.kind_movies)
        LibraryType.Series -> stringResource(Res.string.kind_series)
    }

@Composable
private fun sortLabel(sort: LibrarySort): String =
    stringResource(
        when (sort) {
            LibrarySort.LastWatched -> Res.string.library_sort_last_watched
            LibrarySort.AToZ -> Res.string.library_sort_a_to_z
            LibrarySort.ZToA -> Res.string.library_sort_z_to_a
            LibrarySort.MostWatched -> Res.string.library_sort_most_watched
            LibrarySort.Watched -> Res.string.library_sort_watched
            LibrarySort.NotWatched -> Res.string.library_sort_not_watched
        },
    )

/**
 * The line under a poster: the episode and time left when something is in progress, else
 * "Watched", else the kind of title.
 */
@Composable
private fun LibraryItem.statusLine(): String? {
    val playing = progress
    if (playing != null && playing.durationMs > playing.positionMs) {
        val season = playing.season?.toInt()
        val episode = playing.episode?.toInt()
        val minutesLeft = ((playing.durationMs - playing.positionMs).toLong() / 60_000).coerceAtLeast(1).toInt()
        return listOfNotNull(
            if (season != null && episode != null) stringResource(Res.string.episode_short, season, episode) else null,
            pluralStringResource(Res.plurals.time_left_minutes, minutesLeft, minutesLeft),
        ).joinToString(" · ")
    }
    if (watched) return stringResource(Res.string.library_status_watched)
    return when (kind) {
        "movie" -> stringResource(Res.string.type_movie)
        "series" -> stringResource(Res.string.type_series)
        else -> null
    }
}

/** How far into the video in progress the user is, from 0 to 1. */
private fun LibraryItem.progressFraction(): Float? {
    val playing = progress ?: return null
    if (playing.durationMs == 0uL) return null
    return playing.positionMs.toFloat() / playing.durationMs.toFloat()
}
