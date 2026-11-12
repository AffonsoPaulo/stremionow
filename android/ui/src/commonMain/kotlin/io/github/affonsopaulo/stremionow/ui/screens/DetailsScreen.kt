// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.affonsopaulo.stremionow.core.Episode
import io.github.affonsopaulo.stremionow.core.MetaDetails
import io.github.affonsopaulo.stremionow.core.MetaItem
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.core.WatchProgress
import io.github.affonsopaulo.stremionow.ui.components.ClearHeaderOverHero
import io.github.affonsopaulo.stremionow.ui.components.EpisodeRowControlsCenter
import io.github.affonsopaulo.stremionow.ui.components.LocalHeaderInset
import io.github.affonsopaulo.stremionow.ui.components.SnButton
import io.github.affonsopaulo.stremionow.ui.components.SnButtonStyle
import io.github.affonsopaulo.stremionow.ui.components.SnChip
import io.github.affonsopaulo.stremionow.ui.components.SnChipRow
import io.github.affonsopaulo.stremionow.ui.components.SnEpisodeCard
import io.github.affonsopaulo.stremionow.ui.components.SnHeroBackdrop
import io.github.affonsopaulo.stremionow.ui.components.SnLoadError
import io.github.affonsopaulo.stremionow.ui.components.SnLoading
import io.github.affonsopaulo.stremionow.ui.components.SnMinimalScroll
import io.github.affonsopaulo.stremionow.ui.components.SnPosterCard
import io.github.affonsopaulo.stremionow.ui.components.SnPosterRow
import io.github.affonsopaulo.stremionow.ui.components.SnRow
import io.github.affonsopaulo.stremionow.ui.components.SnTabs
import io.github.affonsopaulo.stremionow.ui.components.belowHeader
import io.github.affonsopaulo.stremionow.ui.components.heroHeight
import io.github.affonsopaulo.stremionow.ui.components.initialFocus
import io.github.affonsopaulo.stremionow.ui.components.snClickable
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.Loadable
import io.github.affonsopaulo.stremionow.ui.data.io
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.platform.LocalPlatformContext
import io.github.affonsopaulo.stremionow.ui.platform.logWarning
import io.github.affonsopaulo.stremionow.ui.platform.screenHeightDp
import io.github.affonsopaulo.stremionow.ui.theme.FormFactor
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
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
    private val mutableProgress = MutableStateFlow<WatchProgress?>(null)

    /** Where the user stopped in this title, for the Resume button. */
    val progress = mutableProgress.asStateFlow()

    /** Reloads the saved position, which changes after watching. */
    fun refreshProgress() {
        viewModelScope.launch { mutableProgress.value = runCatching { io { core.progress(id) } }.getOrNull() }
    }

    init {
        load()
    }

    fun load() {
        mutableDetails.value = Loadable.Loading
        viewModelScope.launch {
            mutableInLibrary.value = runCatching { io { core.isInLibrary(id) } }.getOrDefault(false)
            val result =
                runCatching { core.meta(kind, id) }
                    .onFailure { logWarning("Could not load $kind/$id", it) }
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
    onOpenGenre: (kind: String, genre: String) -> Unit,
    onSearchPerson: (name: String) -> Unit,
    onPlay: (MetaDetails, Episode?) -> Unit,
) {
    val context = LocalPlatformContext.current
    val viewModel = viewModel(key = "details-$kind-$id") { DetailsViewModel(AppGraph.core(context), kind, id) }
    val state by viewModel.details.collectAsStateWithLifecycle()
    val inLibrary by viewModel.inLibrary.collectAsStateWithLifecycle()
    val related by viewModel.related.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshProgress() }

    when (val details = state) {
        Loadable.Loading -> {
            Box(Modifier.fillMaxSize().belowHeader()) { SnLoading() }
        }

        is Loadable.Failed -> {
            Box(Modifier.fillMaxSize().belowHeader()) { SnLoadError(onRetry = viewModel::load) }
        }

        is Loadable.Ready -> {
            DetailsContent(
                details = details.value,
                inLibrary = inLibrary,
                related = related,
                progress = progress,
                onToggleLibrary = { viewModel.toggleLibrary(details.value.item) },
                onOpenTitle = onOpenTitle,
                onOpenGenre = { onOpenGenre(details.value.item.kind, it) },
                onSearchPerson = onSearchPerson,
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
    onOpenGenre: (String) -> Unit,
    onSearchPerson: (String) -> Unit,
    onPlay: (MetaDetails, Episode?) -> Unit,
    progress: WatchProgress?,
) {
    val item = details.item
    val isSeries = details.episodes.isNotEmpty()
    val tabs =
        buildList {
            if (isSeries) add(stringResource(Res.string.details_tab_episodes))
            add(stringResource(Res.string.details_tab_related))
            add(stringResource(Res.string.details_tab_details))
        }
    var tab by rememberSaveable(item.id) { mutableIntStateOf(0) }
    val art = item.background ?: item.poster
    val listState = rememberLazyListState()
    // The art runs under the header, which stays clear until it scrolls away. A title without
    // any starts below a solid header instead, over the plain placeholder.
    if (art != null) ClearHeaderOverHero(listState)
    val headerInset = LocalHeaderInset.current
    val heroInset = if (art != null) headerInset else 0.dp

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val page = DpSize(maxWidth, maxHeight)
        SnMinimalScroll(topInset = headerInset) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(top = headerInset - heroInset, bottom = SnSpacing.space16),
            ) {
                item(key = "hero") {
                    val height =
                        when (SnLayout.formFactor) {
                            FormFactor.Phone -> {
                                420.dp + heroInset
                            }

                            FormFactor.Tv -> {
                                (screenHeightDp() * 0.8f).dp + heroInset
                            }

                            // The picture's own shape as the window grows, up to most of its height.
                            FormFactor.Large -> {
                                heroHeight(page.width, page.height, min = 560.dp + heroInset, maxFraction = 0.8f)
                            }
                        }
                    // Grows by the header's height, so its content stays where it was.
                    Box(Modifier.fillMaxWidth().heightIn(min = height)) {
                        SnHeroBackdrop(
                            url = art,
                            textWidth = DetailsTextWidth,
                            modifier = Modifier.matchParentSize(),
                        )
                        Column(
                            Modifier
                                .align(Alignment.BottomStart)
                                .widthIn(max = DetailsTextWidth + SnLayout.gutter * 2)
                                .padding(top = heroInset)
                                .padding(horizontal = SnLayout.gutter, vertical = SnSpacing.space6),
                            verticalArrangement = Arrangement.spacedBy(SnSpacing.space4),
                        ) {
                            TitleLogo(item)
                            MetaLine(item, details.runtime, onOpenGenre)
                            Row(horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3)) {
                                WatchButton(details, progress, onPlay)
                                SnButton(
                                    text =
                                        stringResource(
                                            if (inLibrary) Res.string.action_in_list else Res.string.action_add_to_list,
                                        ),
                                    icon = if (inLibrary) SnIcons.Check else SnIcons.Plus,
                                    style = SnButtonStyle.Secondary,
                                    onClick = onToggleLibrary,
                                )
                            }
                            item.description?.let { ExpandableDescription(item.id, it) }
                        }
                    }
                }
                // Tabs and rows scroll sideways without the header's inset.
                item(key = "tabs") {
                    SnMinimalScroll {
                        SnTabs(
                            tabs,
                            tab.coerceAtMost(tabs.lastIndex),
                            { tab = it },
                            Modifier.padding(horizontal = SnLayout.gutter),
                        )
                    }
                }
                item(key = "tab-content") {
                    SnMinimalScroll {
                        Box(Modifier.padding(top = SnSpacing.space6)) {
                            when (tabs.getOrNull(tab)) {
                                stringResource(Res.string.details_tab_episodes) -> {
                                    EpisodesTab(details, progress, onPlay)
                                }

                                stringResource(Res.string.details_tab_related) -> {
                                    RelatedTab(related, onOpenTitle)
                                }

                                else -> {
                                    InfoTab(details, onOpenGenre, onSearchPerson)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** How wide the title's logo, facts, buttons and synopsis may grow over its art. */
private val DetailsTextWidth = 580.dp

/**
 * Watch, or Resume with the episode where the user stopped. A new series starts at its first
 * regular episode, skipping specials.
 */
@Composable
private fun WatchButton(
    details: MetaDetails,
    progress: WatchProgress?,
    onPlay: (MetaDetails, Episode?) -> Unit,
) {
    val resumeEpisode = progress?.let { saved -> details.episodes.firstOrNull { it.id == saved.videoId } }
    val text =
        when {
            progress == null -> {
                stringResource(Res.string.action_watch)
            }

            resumeEpisode?.season != null && resumeEpisode.episode != null -> {
                stringResource(
                    Res.string.action_resume_episode,
                    stringResource(
                        Res.string.episode_short,
                        resumeEpisode.season!!.toInt(),
                        resumeEpisode.episode!!.toInt(),
                    ),
                )
            }

            else -> {
                stringResource(Res.string.action_resume)
            }
        }
    SnButton(
        text = text,
        icon = SnIcons.Play,
        modifier = Modifier.initialFocus(),
        onClick = {
            val episode =
                resumeEpisode
                    ?: details.episodes.firstOrNull { (it.season ?: 0u) > 0u }
                    ?: details.episodes.firstOrNull()
            onPlay(details, episode)
        },
    )
}

@Composable
private fun EpisodesTab(
    details: MetaDetails,
    progress: WatchProgress?,
    onPlay: (MetaDetails, Episode?) -> Unit,
) {
    // Regular seasons first, specials (season 0) last.
    val seasons =
        remember(details) {
            details.episodes
                .groupBy { it.season ?: 0u }
                .toSortedMap(compareBy<UInt> { it == 0u }.thenBy { it })
        }
    var season by rememberSaveable(details.item.id) { mutableStateOf(seasons.keys.firstOrNull()?.toLong() ?: 0L) }
    val specials = stringResource(Res.string.details_specials)
    Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space4)) {
        if (seasons.size > 1) {
            SnChipRow(
                options = seasons.keys.map { it.toLong() },
                selected = season,
                label = { if (it == 0L) specials else stringResource(Res.string.details_season, it.toInt()) },
                onSelect = { season = it },
                modifier = Modifier.padding(horizontal = SnLayout.gutter),
            )
        }
        val episodes = seasons[season.toUInt()].orEmpty()
        if (episodes.isEmpty()) {
            BasicText(
                stringResource(Res.string.details_no_episodes),
                Modifier.padding(horizontal = SnLayout.gutter),
                style = SnTypography.body.copy(color = SnColors.fgMuted),
            )
        }
        SnRow(controlsCenter = EpisodeRowControlsCenter) {
            items(episodes, key = { it.id }) { episode ->
                val number = episode.episode?.toInt()
                SnEpisodeCard(
                    title =
                        if (number !=
                            null
                        ) {
                            stringResource(Res.string.episode_numbered, number, episode.title)
                        } else {
                            episode.title
                        },
                    thumbnail = episode.thumbnail,
                    meta = episode.released?.take(10),
                    overview = episode.overview,
                    onClick = { onPlay(details, episode) },
                    progress =
                        progress
                            ?.takeIf { it.videoId == episode.id && it.durationMs > 0u }
                            ?.let { it.positionMs.toFloat() / it.durationMs.toFloat() },
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
    SnPosterRow(related, onOpenTitle, subtitle = { it.cardSubtitle() })
}

/**
 * Year, runtime and rating, then the genres as links to their catalogs. A flow row rather
 * than one string, so each genre is its own focusable, tappable target.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetaLine(
    item: MetaItem,
    runtime: String?,
    onOpenGenre: (String) -> Unit,
) {
    val style = SnTypography.label.copy(color = SnColors.fgSecondary)
    val facts =
        listOfNotNull(
            item.releaseInfo,
            runtime,
            item.imdbRating?.let { stringResource(Res.string.imdb_rating, it) },
        )
    val genres = item.genres.take(3)
    FlowRow(verticalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) {
        facts.forEachIndexed { index, fact ->
            if (index > 0) BasicText(" · ", style = style)
            BasicText(fact, style = style)
        }
        genres.forEachIndexed { index, genre ->
            BasicText(
                if (index == 0 && facts.isNotEmpty()) {
                    " · "
                } else if (index > 0) {
                    ", "
                } else {
                    ""
                },
                style = style,
            )
            GenreLink(genre, style, onClick = { onOpenGenre(genre) })
        }
    }
}

@Composable
private fun GenreLink(
    genre: String,
    style: TextStyle,
    onClick: () -> Unit,
) {
    BasicText(
        genre,
        modifier =
            Modifier
                .snClickable(
                    RoundedCornerShape(SnRadius.radiusSm),
                    onClickLabel = stringResource(Res.string.details_open_genre, genre),
                    onClick = onClick,
                ).padding(vertical = SnSpacing.space1),
        style = style.copy(color = SnColors.fg, textDecoration = TextDecoration.Underline),
    )
}

/**
 * Synopsis clamped to four lines. When it is cut, a tap (or OK on a remote) shows all of it
 * and a "More" hint says so; the same gesture folds it back.
 */
@Composable
private fun ExpandableDescription(
    id: String,
    text: String,
) {
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    var overflowing by remember(id) { mutableStateOf(false) }
    val toggleable = expanded || overflowing
    val hint = stringResource(if (expanded) Res.string.details_show_less else Res.string.details_show_more)
    Column(
        modifier =
            if (toggleable) {
                Modifier.snClickable(RoundedCornerShape(SnRadius.radiusMd), onClickLabel = hint) {
                    expanded = !expanded
                }
            } else {
                Modifier
            },
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space1),
    ) {
        BasicText(
            text,
            style = SnTypography.bodyLg.copy(color = SnColors.fg),
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow },
        )
        if (toggleable) {
            BasicText(hint, style = SnTypography.label.copy(color = SnColors.fg, fontWeight = FontWeight.SemiBold))
        }
    }
}

/** Full details: synopsis, then genres, cast and direction as chips that lead further. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoTab(
    details: MetaDetails,
    onOpenGenre: (String) -> Unit,
    onSearchPerson: (String) -> Unit,
) {
    val overview: @Composable () -> Unit = {
        details.item.description?.let { InfoSection(stringResource(Res.string.details_overview)) { InfoText(it) } }
    }
    val facts: @Composable () -> Unit = {
        details.runtime?.let { InfoSection(stringResource(Res.string.details_runtime)) { InfoText(it) } }
        ChipSection(stringResource(Res.string.details_genres), details.item.genres, onOpenGenre)
        ChipSection(stringResource(Res.string.details_cast), details.cast.take(12), onSearchPerson)
        ChipSection(stringResource(Res.string.details_directors), details.directors, onSearchPerson)
    }
    if (SnLayout.formFactor == FormFactor.Phone) {
        Column(
            Modifier.padding(horizontal = SnLayout.gutter),
            verticalArrangement = Arrangement.spacedBy(SnSpacing.space6),
        ) {
            overview()
            facts()
        }
    } else {
        // Wide screens: the synopsis keeps a readable line length on the left and the facts
        // fill the rest, so the page uses its width instead of leaving half of it empty.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = SnLayout.gutter),
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space8),
        ) {
            Column(Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(SnSpacing.space6)) { overview() }
            Column(Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(SnSpacing.space6)) { facts() }
        }
    }
}

@Composable
private fun InfoSection(
    label: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space2)) {
        BasicText(
            label.uppercase(),
            modifier = Modifier.semantics { heading() },
            style = SnTypography.overline.copy(color = SnColors.fgMuted),
        )
        content()
    }
}

@Composable
private fun InfoText(text: String) {
    BasicText(text, style = SnTypography.body.copy(color = SnColors.fg))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipSection(
    label: String,
    values: List<String>,
    onClick: (String) -> Unit,
) {
    if (values.isEmpty()) return
    InfoSection(label) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space2),
            verticalArrangement = Arrangement.spacedBy(SnSpacing.space2),
        ) {
            values.forEach { value ->
                SnChip(text = value, selected = false, onClick = { onClick(value) })
            }
        }
    }
}
