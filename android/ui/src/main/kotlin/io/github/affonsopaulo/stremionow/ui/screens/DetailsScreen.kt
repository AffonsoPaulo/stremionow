// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.affonsopaulo.stremionow.core.Episode
import io.github.affonsopaulo.stremionow.core.MetaDetails
import io.github.affonsopaulo.stremionow.core.MetaItem
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.ui.R
import io.github.affonsopaulo.stremionow.ui.components.SnArtwork
import io.github.affonsopaulo.stremionow.ui.components.SnButton
import io.github.affonsopaulo.stremionow.ui.components.SnButtonStyle
import io.github.affonsopaulo.stremionow.ui.components.SnChipRow
import io.github.affonsopaulo.stremionow.ui.components.SnEpisodeCard
import io.github.affonsopaulo.stremionow.ui.components.SnLoadError
import io.github.affonsopaulo.stremionow.ui.components.SnLoading
import io.github.affonsopaulo.stremionow.ui.components.SnPosterCard
import io.github.affonsopaulo.stremionow.ui.components.SnTabs
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.Loadable
import io.github.affonsopaulo.stremionow.ui.data.io
import io.github.affonsopaulo.stremionow.ui.theme.FormFactor
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DetailsViewModel(
    private val core: StremioCore,
    private val kind: String,
    private val id: String,
) : ViewModel() {
    private val mutableDetails = MutableStateFlow<Loadable<MetaDetails>>(Loadable.Loading)
    val details = mutableDetails.asStateFlow()
    private val mutableInLibrary = MutableStateFlow(false)
    val inLibrary = mutableInLibrary.asStateFlow()
    private val mutableRelated = MutableStateFlow<List<MetaItem>>(emptyList())
    val related = mutableRelated.asStateFlow()

    init {
        load()
    }

    fun load() {
        mutableDetails.value = Loadable.Loading
        viewModelScope.launch {
            mutableInLibrary.value = runCatching { io { core.isInLibrary(id) } }.getOrDefault(false)
            val result =
                runCatching { core.meta(kind, id) }
                    .onFailure { android.util.Log.w("StremioNow", "Could not load $kind/$id", it) }
            mutableDetails.value = result.fold({ Loadable.Ready(it) }, { Loadable.Failed(it) })
            result.getOrNull()?.let { loadRelated(it.item) }
        }
    }

    fun toggleLibrary(item: MetaItem) {
        viewModelScope.launch {
            val saved = mutableInLibrary.value
            runCatching { io { if (saved) core.removeFromLibrary(item.id) else core.addToLibrary(item) } }
                .onSuccess { mutableInLibrary.value = !saved }
        }
    }

    /** Titles from the same catalog type and main genre, a simple stand-in for recommendations. */
    private suspend fun loadRelated(item: MetaItem) {
        val genre = item.genres.firstOrNull() ?: return
        val catalog =
            runCatching { io { core.catalogs(kind) } }.getOrDefault(emptyList()).firstOrNull { genre in it.genres }
                ?: return
        mutableRelated.value =
            runCatching { core.catalogPage(catalog, genre, 0u) }
                .getOrDefault(emptyList())
                .filter { it.id != item.id }
                .take(20)
    }
}

@Composable
fun DetailsScreen(
    kind: String,
    id: String,
    onOpenTitle: (MetaItem) -> Unit,
    onPlay: ((MetaDetails, Episode?) -> Unit)?,
) {
    val context = LocalContext.current
    val viewModel = viewModel(key = "details-$kind-$id") { DetailsViewModel(AppGraph.core(context), kind, id) }
    val state by viewModel.details.collectAsStateWithLifecycle()
    val inLibrary by viewModel.inLibrary.collectAsStateWithLifecycle()
    val related by viewModel.related.collectAsStateWithLifecycle()

    when (val details = state) {
        Loadable.Loading -> {
            SnLoading()
        }

        is Loadable.Failed -> {
            SnLoadError(onRetry = viewModel::load)
        }

        is Loadable.Ready -> {
            DetailsContent(
                details = details.value,
                inLibrary = inLibrary,
                related = related,
                onToggleLibrary = { viewModel.toggleLibrary(details.value.item) },
                onOpenTitle = onOpenTitle,
                onPlay = onPlay,
            )
        }
    }
}

@Composable
private fun DetailsContent(
    details: MetaDetails,
    inLibrary: Boolean,
    related: List<MetaItem>,
    onToggleLibrary: () -> Unit,
    onOpenTitle: (MetaItem) -> Unit,
    onPlay: ((MetaDetails, Episode?) -> Unit)?,
) {
    val item = details.item
    val isSeries = details.episodes.isNotEmpty()
    val tabs =
        buildList {
            if (isSeries) add(stringResource(R.string.details_tab_episodes))
            add(stringResource(R.string.details_tab_related))
            add(stringResource(R.string.details_tab_details))
        }
    var tab by rememberSaveable(item.id) { mutableIntStateOf(0) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = SnSpacing.space16)) {
        item(key = "hero") {
            val height = if (SnLayout.formFactor == FormFactor.Phone) 420.dp else 560.dp
            Box(Modifier.fillMaxWidth().height(height)) {
                SnArtwork(url = item.background ?: item.poster, fallbackTitle = "", modifier = Modifier.fillMaxSize())
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(
                            0f to SnColors.bg.copy(alpha = 0.95f),
                            0.36f to SnColors.bg.copy(alpha = 0.7f),
                            0.7f to SnColors.bg.copy(alpha = 0f),
                        ),
                    ),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0.55f to SnColors.bg.copy(alpha = 0f),
                            1f to SnColors.bg,
                        ),
                    ),
                )
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .widthIn(max = 660.dp)
                        .padding(horizontal = SnLayout.gutter, vertical = SnSpacing.space6),
                    verticalArrangement = Arrangement.spacedBy(SnSpacing.space4),
                ) {
                    TitleLogo(item)
                    BasicText(
                        item.metaLine(details.runtime),
                        style = SnTypography.label.copy(color = SnColors.fgSecondary),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3)) {
                        if (onPlay != null) {
                            SnButton(
                                text = stringResource(R.string.action_watch),
                                icon = SnIcons.Play,
                                onClick = { onPlay(details, details.episodes.firstOrNull { (it.season ?: 0u) > 0u }) },
                            )
                        }
                        SnButton(
                            text =
                                stringResource(
                                    if (inLibrary) R.string.action_in_list else R.string.action_add_to_list,
                                ),
                            icon = if (inLibrary) SnIcons.Check else SnIcons.Plus,
                            style = SnButtonStyle.Secondary,
                            onClick = onToggleLibrary,
                        )
                    }
                    item.description?.let {
                        BasicText(
                            it,
                            style = SnTypography.bodyLg.copy(color = SnColors.fg),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        item(key = "tabs") {
            SnTabs(tabs, tab.coerceAtMost(tabs.lastIndex), { tab = it }, Modifier.padding(horizontal = SnLayout.gutter))
        }
        item(key = "tab-content") {
            Box(Modifier.padding(top = SnSpacing.space6)) {
                when (tabs.getOrNull(tab)) {
                    stringResource(R.string.details_tab_episodes) -> EpisodesTab(details, onPlay)
                    stringResource(R.string.details_tab_related) -> RelatedTab(related, onOpenTitle)
                    else -> InfoTab(details)
                }
            }
        }
    }
}

@Composable
private fun EpisodesTab(
    details: MetaDetails,
    onPlay: ((MetaDetails, Episode?) -> Unit)?,
) {
    // Regular seasons first, specials (season 0) last.
    val seasons =
        remember(details) {
            details.episodes
                .groupBy { it.season ?: 0u }
                .toSortedMap(compareBy<UInt> { it == 0u }.thenBy { it })
        }
    var season by rememberSaveable(details.item.id) { mutableStateOf(seasons.keys.firstOrNull()?.toLong() ?: 0L) }
    val specials = stringResource(R.string.details_specials)
    Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space4)) {
        if (seasons.size > 1) {
            SnChipRow(
                options = seasons.keys.map { it.toLong() },
                selected = season,
                label = { if (it == 0L) specials else stringResource(R.string.details_season, it.toInt()) },
                onSelect = { season = it },
                modifier = Modifier.padding(horizontal = SnLayout.gutter),
            )
        }
        val episodes = seasons[season.toUInt()].orEmpty()
        if (episodes.isEmpty()) {
            BasicText(
                stringResource(R.string.details_no_episodes),
                Modifier.padding(horizontal = SnLayout.gutter),
                style = SnTypography.body.copy(color = SnColors.fgMuted),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = SnLayout.gutter, vertical = SnSpacing.space2),
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space4),
        ) {
            items(episodes, key = { it.id }) { episode ->
                val number = episode.episode?.toInt()
                SnEpisodeCard(
                    title =
                        if (number !=
                            null
                        ) {
                            stringResource(R.string.episode_numbered, number, episode.title)
                        } else {
                            episode.title
                        },
                    thumbnail = episode.thumbnail,
                    meta = episode.released?.take(10),
                    overview = episode.overview,
                    onClick = { onPlay?.invoke(details, episode) },
                )
            }
        }
    }
}

@Composable
private fun RelatedTab(
    related: List<MetaItem>,
    onOpenTitle: (MetaItem) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = SnLayout.gutter, vertical = SnSpacing.space2),
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space4),
    ) {
        items(related, key = { it.id }) { item ->
            SnPosterCard(item.name, item.poster, item.cardSubtitle(), onClick = { onOpenTitle(item) })
        }
    }
}

@Composable
private fun InfoTab(details: MetaDetails) {
    val rows =
        listOfNotNull(
            details.item.description?.let { stringResource(R.string.details_overview) to it },
            details.item.genres
                .joinToString(", ")
                .ifEmpty { null }
                ?.let { stringResource(R.string.details_genres) to it },
            details.runtime?.let { stringResource(R.string.details_runtime) to it },
            details.cast
                .take(12)
                .joinToString(", ")
                .ifEmpty { null }
                ?.let { stringResource(R.string.details_cast) to it },
            details.directors
                .joinToString(", ")
                .ifEmpty { null }
                ?.let { stringResource(R.string.details_directors) to it },
        )
    Column(
        Modifier.padding(horizontal = SnLayout.gutter).widthIn(max = 880.dp),
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space6),
    ) {
        rows.forEach { (label, value) ->
            Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space1)) {
                BasicText(label.uppercase(), style = SnTypography.overline.copy(color = SnColors.fgMuted))
                BasicText(value, style = SnTypography.body.copy(color = SnColors.fg))
            }
        }
    }
}
