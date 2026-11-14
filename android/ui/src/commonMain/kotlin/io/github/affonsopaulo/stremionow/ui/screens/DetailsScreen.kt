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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.affonsopaulo.stremionow.core.Episode
import io.github.affonsopaulo.stremionow.core.LastSource
import io.github.affonsopaulo.stremionow.core.MetaDetails
import io.github.affonsopaulo.stremionow.core.MetaItem
import io.github.affonsopaulo.stremionow.core.StreamSource
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.core.TitleRating
import io.github.affonsopaulo.stremionow.core.WatchProgress
import io.github.affonsopaulo.stremionow.ui.components.ClearHeaderOverHero
import io.github.affonsopaulo.stremionow.ui.components.EpisodeRowControlsCenter
import io.github.affonsopaulo.stremionow.ui.components.LocalHeaderInset
import io.github.affonsopaulo.stremionow.ui.components.SnButton
import io.github.affonsopaulo.stremionow.ui.components.SnButtonStyle
import io.github.affonsopaulo.stremionow.ui.components.SnChip
import io.github.affonsopaulo.stremionow.ui.components.SnChipRow
import io.github.affonsopaulo.stremionow.ui.components.SnDialog
import io.github.affonsopaulo.stremionow.ui.components.SnEpisodeCard
import io.github.affonsopaulo.stremionow.ui.components.SnHeroBackdrop
import io.github.affonsopaulo.stremionow.ui.components.SnLoadError
import io.github.affonsopaulo.stremionow.ui.components.SnLoading
import io.github.affonsopaulo.stremionow.ui.components.SnMinimalScroll
import io.github.affonsopaulo.stremionow.ui.components.SnPosterRow
import io.github.affonsopaulo.stremionow.ui.components.SnRow
import io.github.affonsopaulo.stremionow.ui.components.SnScrollArea
import io.github.affonsopaulo.stremionow.ui.components.SnTabs
import io.github.affonsopaulo.stremionow.ui.components.SnTextLink
import io.github.affonsopaulo.stremionow.ui.components.belowHeader
import io.github.affonsopaulo.stremionow.ui.components.heroHeight
import io.github.affonsopaulo.stremionow.ui.components.initialFocus
import io.github.affonsopaulo.stremionow.ui.components.snClickable
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.Connectivity
import io.github.affonsopaulo.stremionow.ui.data.Loadable
import io.github.affonsopaulo.stremionow.ui.data.PlaybackSession
import io.github.affonsopaulo.stremionow.ui.data.io
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.platform.LocalPlatformContext
import io.github.affonsopaulo.stremionow.ui.platform.SnBackHandler
import io.github.affonsopaulo.stremionow.ui.platform.logWarning
import io.github.affonsopaulo.stremionow.ui.platform.openExternalLink
import io.github.affonsopaulo.stremionow.ui.platform.screenHeightDp
import io.github.affonsopaulo.stremionow.ui.theme.FormFactor
import io.github.affonsopaulo.stremionow.ui.theme.LocalIsTv
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Locale

/** Sources asked for again after this long: debrid links expire. */
private const val SOURCES_FRESH_MS = 10 * 60 * 1000L

/** Videos whose sources are kept while the page lives, for "Change source" and going back. */
private const val KEPT_SOURCES = 4

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
    private val mutableWatched = MutableStateFlow<Set<String>>(emptySet())

    /** Videos of this title the user has watched: episode ids, or the movie's own id. */
    val watched = mutableWatched.asStateFlow()
    private val mutableLastSource = MutableStateFlow<LastSource?>(null)

    /** The source last played for this title, for Resume to play it again. */
    val lastSource = mutableLastSource.asStateFlow()
    private val mutableRating = MutableStateFlow(TitleRating.NONE)
    val rating = mutableRating.asStateFlow()
    private val mutableWifiOnly = MutableStateFlow(false)

    /** The "Only on Wi-Fi" preference for torrents. */
    val wifiOnly = mutableWifiOnly.asStateFlow()
    private val mutableSourcesFor = MutableStateFlow<String?>(null)

    /** The video whose sources show in place of the episodes: always the movie, for movies. */
    val sourcesFor = mutableSourcesFor.asStateFlow()
    private val mutableSources = MutableStateFlow(SourcesState())
    val sources = mutableSources.asStateFlow()
    private val mutableReveal = MutableStateFlow(false)

    /** The sources should come into view, as after the player asked for them; see [revealed]. */
    val reveal = mutableReveal.asStateFlow()

    /** The page brought the sources into view. */
    fun revealed() {
        mutableReveal.value = false
    }

    private val loaders = LinkedHashMap<String, SourcesLoader>()
    private var following: Job? = null

    /** Reloads what changes after watching: the saved position and the watched videos. */
    fun refresh() {
        viewModelScope.launch {
            mutableProgress.value = runCatching { io { core.progress(id) } }.getOrNull()
            mutableWatched.value = runCatching { io { core.watchedVideos(id) } }.getOrDefault(emptyList()).toSet()
            mutableLastSource.value = runCatching { io { core.lastSource(id) } }.getOrNull()
        }
    }

    init {
        load()
        viewModelScope.launch {
            mutableWifiOnly.value = runCatching { io { core.settings().torrentWifiOnly } }.getOrDefault(false)
        }
    }

    fun load() {
        mutableDetails.value = Loadable.Loading
        viewModelScope.launch {
            mutableInLibrary.value = runCatching { io { core.isInLibrary(id) } }.getOrDefault(false)
            mutableRating.value = runCatching { io { core.rating(id) } }.getOrDefault(TitleRating.NONE)
            val result =
                runCatching { core.meta(kind, id) }
                    .onFailure { logWarning("Could not load $kind/$id", it) }
            mutableDetails.value = result.fold({ Loadable.Ready(it) }, { Loadable.Failed(it) })
            result.getOrNull()?.let { details ->
                // A movie opens with its sources already where the episodes would be.
                if (details.episodes.isEmpty() && mutableSourcesFor.value == null) openSources(details.item.id)
                launch { refreshRating(details.item) }
                loadRelated(details.item)
            }
        }
    }

    /** Shows the sources of [videoId] (an episode's id, or the movie's), asking addons once. */
    fun openSources(
        videoId: String,
        reveal: Boolean = false,
    ) {
        mutableSourcesFor.value = videoId
        val kept = loaders[videoId]?.takeIf { System.currentTimeMillis() - it.startedAt < SOURCES_FRESH_MS }
        val loader =
            kept ?: SourcesLoader(core, viewModelScope, kind, videoId).also { fresh ->
                loaders.remove(videoId)?.cancel()
                loaders[videoId] = fresh
                while (loaders.size > KEPT_SOURCES) {
                    val oldest = loaders.keys.first()
                    loaders.remove(oldest)?.cancel()
                }
            }
        following?.cancel()
        following = viewModelScope.launch { loader.state.collect { mutableSources.value = it } }
        if (reveal) mutableReveal.value = true
    }

    /** Remembers [source] as the one played for [videoId], to offer it first next time. */
    fun rememberSource(
        videoId: String,
        source: StreamSource,
    ) {
        loaders[videoId]?.remember(source)
    }

    /** Back to the episodes; a movie keeps its sources. */
    fun closeSources() {
        val details = (mutableDetails.value as? Loadable.Ready)?.value ?: return
        if (details.episodes.isNotEmpty()) mutableSourcesFor.value = null
    }

    fun toggleLibrary(item: MetaItem) {
        viewModelScope.launch {
            val saved = mutableInLibrary.value
            runCatching { io { if (saved) core.removeFromLibrary(item.id) else core.addToLibrary(item) } }
                .onSuccess { mutableInLibrary.value = !saved }
        }
    }

    /** Marks [videoIds] watched, or unwatched, and shows the result. */
    fun setWatched(
        videoIds: List<String>,
        watched: Boolean,
    ) {
        if (videoIds.isEmpty()) return
        viewModelScope.launch {
            runCatching { io { core.setWatched(id, videoIds, watched) } }
                .onFailure { logWarning("Could not mark videos as watched", it) }
            refresh()
        }
    }

    fun rate(
        item: MetaItem,
        rating: TitleRating,
    ) {
        mutableRating.value = rating
        viewModelScope.launch {
            mutableRating.value = runCatching { core.setRating(item.kind, item.id, rating) }.getOrDefault(rating)
        }
    }

    /** The account's rating, when signed in; otherwise it stays the device's. */
    private suspend fun refreshRating(item: MetaItem) {
        runCatching { core.refreshRating(item.kind, item.id) }.onSuccess { mutableRating.value = it }
    }

    /**
     * Titles like this one, scored by the core on genres, era, director and cast, without the
     * ones the user has watched. A row of suggestions: on failure it simply stays empty.
     */
    private suspend fun loadRelated(item: MetaItem) {
        mutableRelated.value =
            runCatching { core.similarTitles(item.kind, item.id) }
                .onFailure { logWarning("Could not find titles like ${item.kind}/${item.id}", it) }
                .getOrDefault(emptyList())
    }
}

/**
 * A title's page: its art and facts, the main buttons and the actions under them, then the
 * episodes, or the sources of the episode chosen, in the same place, as the official app
 * does; movies open with their sources there. [onPlay] opens the player once a source is
 * chosen; the choice is in [PlaybackSession].
 */
@Composable
fun DetailsScreen(
    kind: String,
    id: String,
    onOpenTitle: (MetaItem) -> Unit,
    onOpenGenre: (kind: String, genre: String) -> Unit,
    onSearchPerson: (name: String) -> Unit,
    onPlay: () -> Unit,
) {
    val context = LocalPlatformContext.current
    val viewModel = viewModel(key = "details-$kind-$id") { DetailsViewModel(AppGraph.core(context), kind, id) }
    val state by viewModel.details.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refresh()
        // Back from the player to choose a source: "Change source", or the next episode.
        val selection = PlaybackSession.selection
        if (PlaybackSession.showSources && selection?.details?.item?.id == id) {
            PlaybackSession.showSources = false
            viewModel.openSources(selection.videoId, reveal = true)
        }
    }

    when (val details = state) {
        Loadable.Loading -> {
            Box(Modifier.fillMaxSize().belowHeader()) { SnLoading() }
        }

        is Loadable.Failed -> {
            Box(Modifier.fillMaxSize().belowHeader()) { SnLoadError(onRetry = viewModel::load) }
        }

        is Loadable.Ready -> {
            DetailsContent(details.value, viewModel, onOpenTitle, {
                onOpenGenre(details.value.item.kind, it)
            }, onSearchPerson, onPlay)
        }
    }
}

/** The parts of the page below the hero. */
private enum class DetailsTab { Episodes, Sources, Related, Info }

@Composable
private fun DetailsContent(
    details: MetaDetails,
    viewModel: DetailsViewModel,
    onOpenTitle: (MetaItem) -> Unit,
    onOpenGenre: (String) -> Unit,
    onSearchPerson: (String) -> Unit,
    onPlay: () -> Unit,
) {
    val context = LocalPlatformContext.current
    val isTv = LocalIsTv.current
    val inLibrary by viewModel.inLibrary.collectAsStateWithLifecycle()
    val related by viewModel.related.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val watched by viewModel.watched.collectAsStateWithLifecycle()
    val lastSource by viewModel.lastSource.collectAsStateWithLifecycle()
    val rating by viewModel.rating.collectAsStateWithLifecycle()
    val wifiOnly by viewModel.wifiOnly.collectAsStateWithLifecycle()
    val sourcesFor by viewModel.sourcesFor.collectAsStateWithLifecycle()
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val reveal by viewModel.reveal.collectAsStateWithLifecycle()

    val item = details.item
    val isSeries = details.episodes.isNotEmpty()
    val now = remember(watched, progress) { Instant.now() }
    val next = remember(details, progress, watched, now) { nextUp(details, progress, watched, now) }
    val tabs =
        if (isSeries) {
            listOf(DetailsTab.Episodes, DetailsTab.Related, DetailsTab.Info)
        } else {
            listOf(DetailsTab.Sources, DetailsTab.Related, DetailsTab.Info)
        }
    var tabIndex by rememberSaveable(item.id) { mutableIntStateOf(0) }
    val tab = tabs[tabIndex.coerceIn(0, tabs.lastIndex)]
    // Regular seasons first, specials (season 0) last.
    val seasons =
        remember(details) {
            details.episodes
                .groupBy { it.season ?: 0u }
                .toSortedMap(compareBy<UInt> { it == 0u }.thenBy { it })
        }
    var season by rememberSaveable(item.id) {
        mutableLongStateOf((next.episode?.season ?: seasons.keys.firstOrNull() ?: 0u).toLong())
    }
    // Opens on the season of the episode the main button leads to, once progress is known,
    // unless the user picked another one.
    var seasonPicked by rememberSaveable(item.id) { mutableStateOf(false) }
    LaunchedEffect(next.episode?.season) {
        val nextSeason = next.episode?.season
        if (!seasonPicked && nextSeason != null) season = nextSeason.toLong()
    }
    // Each season's row keeps its scroll position while sources show in its place.
    val rowStates = remember(item.id) { mutableMapOf<Long, LazyListState>() }
    val sourcesEpisode = details.episodes.firstOrNull { it.id == sourcesFor }
    var focusEpisode by remember(item.id) { mutableStateOf<String?>(null) }
    // The search, filters and order of the sources hold for every video of the page.
    var sourceFilters by remember(item.id) { mutableStateOf(SourceFilters()) }
    var confirmMobileData by remember { mutableStateOf<StreamSource?>(null) }
    var watchedChoices by remember { mutableStateOf<List<WatchedChoice>?>(null) }
    var sharing by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val firstSourceFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    val tabsIndex = 1

    /** Brings the sources into view and, on TV, focus to them. */
    fun revealSources() {
        scope.launch {
            listState.animateScrollToItem(tabsIndex)
            if (isTv) {
                runCatching { firstSourceFocus.requestFocus() }
                    .recoverCatching { if (isSeries) backFocus.requestFocus() }
            }
        }
    }

    fun showSources(episode: Episode?) {
        if (episode != null) {
            season = (episode.season ?: 0u).toLong()
            viewModel.openSources(episode.id)
        }
        tabIndex = 0
        revealSources()
    }

    fun closeSources() {
        focusEpisode = sourcesFor
        viewModel.closeSources()
    }

    val play = { source: StreamSource ->
        // The episode these sources are for; none for a movie.
        val episode = details.episodes.firstOrNull { it.id == sources.videoId }
        sources.videoId?.let { viewModel.rememberSource(it, source) }
        PlaybackSession.select(PlaybackSession.Selection(details, episode))
        PlaybackSession.source = source
        PlaybackSession.alternatives = sources.sources
        onPlay()
    }
    val choose: (StreamSource) -> Unit = { source ->
        when {
            !source.playsInApp -> {
                source.outsideLink()?.let { openExternalLink(context, it) }
            }

            // Respect "Only on Wi-Fi", but let the user decide case by case.
            source.infoHash != null && source.url == null && wifiOnly && Connectivity.onMobileData(context) -> {
                confirmMobileData = source
            }

            else -> {
                play(source)
            }
        }
    }
    // "Resume" goes straight to the player with the source chosen before, looked up again
    // (ResumePlayback.kt); without one that could be found, the sources show as before.
    val resume = {
        val request = if (next.started) resumeRequest(details, next.episode, lastSource) else null
        if (request != null) {
            PlaybackSession.resume(request)
            onPlay()
        } else {
            showSources(next.episode)
        }
    }
    LaunchedEffect(reveal) {
        if (reveal) {
            viewModel.revealed()
            sourcesEpisode?.let { season = (it.season ?: 0u).toLong() }
            showSources(null)
        }
    }
    // The next episode: play the source of the same group as soon as it shows up; if none
    // does, its sources stay for the user to choose.
    LaunchedEffect(sources) {
        val group = PlaybackSession.continueWith ?: return@LaunchedEffect
        if (sources.videoId == null || sources.videoId != PlaybackSession.selection?.videoId) return@LaunchedEffect
        val match = sources.sources.firstOrNull { it.bingeGroup == group && it.playsInApp }
        if (match != null) {
            PlaybackSession.continueWith = null
            play(match)
        } else if (sources.finished) {
            PlaybackSession.continueWith = null
        }
    }
    // Back from an episode's sources returns to the episodes, where the user was.
    SnBackHandler(enabled = isSeries && sourcesFor != null && tab == DetailsTab.Episodes) { closeSources() }

    confirmMobileData?.let { source ->
        SnDialog(
            title = stringResource(Res.string.sources_wifi_title),
            body = stringResource(Res.string.sources_wifi_body),
            confirmText = stringResource(Res.string.sources_wifi_play_anyway),
            dismissText = stringResource(Res.string.action_cancel),
            onConfirm = {
                confirmMobileData = null
                play(source)
            },
            onDismiss = { confirmMobileData = null },
        )
    }
    watchedChoices?.let { WatchedDialog(it, onDismiss = { watchedChoices = null }) }
    val shareUrl = details.shareUrl
    if (sharing && shareUrl != null) ShareTitleDialog(item.name, shareUrl, onDismiss = { sharing = false })

    val trailer =
        details.trailerYoutubeIds.firstOrNull()?.let { trailerId ->
            {
                openExternalLink(context, youtubeLink(trailerId))
            }
        }
    val seriesCovered = remember(details, now) { coveredVideos(details, WatchScope.Series, null, null, now) }
    val watchedChoicesFor =
        watchedChoices(details, sourcesEpisode ?: next.episode, season.toUInt(), watched, now, viewModel)
    val actions =
        DetailActions(
            onTrailer = rowTrailer(isSeries, trailer),
            inLibrary = inLibrary,
            onToggleLibrary = { viewModel.toggleLibrary(item) },
            watched = allWatched(seriesCovered, watched),
            onWatched = {
                if (isSeries) {
                    watchedChoices = watchedChoicesFor
                } else {
                    viewModel.setWatched(seriesCovered, !allWatched(seriesCovered, watched))
                }
            },
            rating = rating,
            onRate = { viewModel.rate(item, it) },
            onShare = shareUrl?.let { { sharing = true } },
        )

    val art = item.background ?: item.poster
    // The art runs under the header, which stays clear until it scrolls away. A title without
    // any starts below a solid header instead, over the plain placeholder.
    if (art != null) ClearHeaderOverHero(listState)
    val headerInset = LocalHeaderInset.current
    val heroInset = if (art != null) headerInset else 0.dp
    val sourcesHeading = sourcesEpisode?.let { episodeHeading(it) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val page = DpSize(maxWidth, maxHeight)
        val phone = SnLayout.formFactor == FormFactor.Phone
        val columns = sourceGridColumns((page.width - SnLayout.gutter * 2).value, SnSpacing.space3.value, phone)
        SnScrollArea(listState, topInset = headerInset, minimalScroll = true) {
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
                                heroHeight(
                                    page.width,
                                    page.height,
                                    min = 560.dp + heroInset,
                                    maxFraction = 0.8f,
                                )
                            }
                        }
                    // Grows by the header's height, so its content stays where it was.
                    Box(Modifier.fillMaxWidth().heightIn(min = height)) {
                        SnHeroBackdrop(url = art, textWidth = DetailsTextWidth, modifier = Modifier.matchParentSize())
                        Column(
                            Modifier
                                .align(Alignment.BottomStart)
                                .widthIn(max = DetailsTextWidth + SnLayout.gutter * 2)
                                .padding(top = heroInset)
                                .padding(horizontal = SnLayout.gutter, vertical = SnSpacing.space6),
                            verticalArrangement = Arrangement.spacedBy(SnSpacing.space4),
                        ) {
                            TitleLogo(item)
                            MetaLine(item, details.runtime, fourK = hasPlayable4k(sources.sources), onOpenGenre)
                            HeroButtons(
                                isSeries = isSeries,
                                next = next,
                                hasTrailer = trailer != null,
                                onPrimary = resume,
                                onStartOver = {
                                    showSources(
                                        details.episodes.firstOrNull { (it.season ?: 0u) > 0u }
                                            ?: details.episodes.first(),
                                    )
                                },
                                onTrailer = { trailer?.invoke() },
                            )
                            ActionRow(actions)
                            item.description?.let { ExpandableDescription(item.id, it) }
                        }
                    }
                }
                // Tabs and rows scroll sideways without the header's inset.
                item(key = "tabs") {
                    SnMinimalScroll {
                        SnTabs(
                            tabs.map { it.label() },
                            tabIndex.coerceIn(0, tabs.lastIndex),
                            { tabIndex = it },
                            Modifier.padding(horizontal = SnLayout.gutter),
                        )
                    }
                }
                item(key = "tab-gap") { Spacer(Modifier.height(SnSpacing.space6)) }
                val showingSources = tab == DetailsTab.Sources || (tab == DetailsTab.Episodes && sourcesFor != null)
                if (showingSources) {
                    val subject =
                        sourcesHeading?.let { SourcesSubject(it, onBack = ::closeSources, backFocus = backFocus) }
                    sourcesItems(
                        state = sources,
                        subject = subject,
                        filters = sourceFilters,
                        onFilters = { sourceFilters = it },
                        progress = progress?.takeIf { it.videoId == sources.videoId },
                        columns = columns,
                        phone = phone,
                        firstFocus = firstSourceFocus,
                        onChoose = choose,
                    )
                } else {
                    item(key = "tab-content-$tab") {
                        SnMinimalScroll {
                            when (tab) {
                                DetailsTab.Related -> {
                                    SnPosterRow(related, onOpenTitle, subtitle = { it.cardSubtitle() })
                                }

                                DetailsTab.Info -> {
                                    InfoTab(details, onOpenGenre, onSearchPerson)
                                }

                                else -> {
                                    EpisodesTab(
                                        seasons = seasons,
                                        season = season,
                                        onSeason = {
                                            season = it
                                            seasonPicked = true
                                        },
                                        rowState = rowStates.getOrPut(season) { LazyListState() },
                                        progress = progress,
                                        watched = watched,
                                        focusEpisode = if (isTv) focusEpisode else null,
                                        onFocused = { focusEpisode = null },
                                        onChoose = { showSources(it) },
                                    )
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

@Composable
private fun DetailsTab.label(): String =
    stringResource(
        when (this) {
            DetailsTab.Episodes -> Res.string.details_tab_episodes
            DetailsTab.Sources -> Res.string.details_tab_sources
            DetailsTab.Related -> Res.string.details_tab_related
            DetailsTab.Info -> Res.string.details_tab_details
        },
    )

/** "S1 E5", or the episode's title when it has no numbers. */
@Composable
private fun episodeLabel(episode: Episode): String {
    val season = episode.season
    val number = episode.episode
    return if (season != null && number != null) {
        stringResource(Res.string.episode_short, season.toInt(), number.toInt())
    } else {
        episode.title
    }
}

/** "S1 E5 · Title", over an episode's sources. */
@Composable
private fun episodeHeading(episode: Episode): String {
    val label = episodeLabel(episode)
    return if (episode.title.isBlank() || label == episode.title) label else "$label · ${episode.title}"
}

/** The series' "Mark as watched" choices: the episode, the selected season and the series. */
@Composable
private fun watchedChoices(
    details: MetaDetails,
    episode: Episode?,
    season: UInt,
    watched: Set<String>,
    now: Instant,
    viewModel: DetailsViewModel,
): List<WatchedChoice> =
    buildList {
        val choice = { covered: List<String>, mark: String, unmark: String ->
            if (covered.isNotEmpty()) {
                val all = allWatched(covered, watched)
                add(WatchedChoice(if (all) unmark else mark) { viewModel.setWatched(covered, !all) })
            }
        }
        if (episode != null) {
            val label = episodeLabel(episode)
            choice(
                coveredVideos(details, WatchScope.Episode, episode, null, now),
                stringResource(Res.string.watched_episode_mark, label),
                stringResource(Res.string.watched_episode_unmark, label),
            )
        }
        val seasonCovered = coveredVideos(details, WatchScope.Season, null, season, now)
        if (season == 0u) {
            choice(
                seasonCovered,
                stringResource(Res.string.watched_specials_mark),
                stringResource(Res.string.watched_specials_unmark),
            )
        } else {
            choice(
                seasonCovered,
                stringResource(Res.string.watched_season_mark, season.toInt()),
                stringResource(Res.string.watched_season_unmark, season.toInt()),
            )
        }
        choice(
            coveredVideos(details, WatchScope.Series, null, null, now),
            stringResource(Res.string.watched_series_mark),
            stringResource(Res.string.watched_series_unmark),
        )
    }

/**
 * The main button, which names the episode it leads to and shows how far into it the user
 * got, and the second one: "Start over" on a series under way, the trailer on a movie.
 */
@Composable
private fun HeroButtons(
    isSeries: Boolean,
    next: NextUp,
    hasTrailer: Boolean,
    onPrimary: () -> Unit,
    onStartOver: () -> Unit,
    onTrailer: () -> Unit,
) {
    val episode = next.episode
    val text =
        when {
            isSeries && episode != null && next.started -> {
                stringResource(
                    Res.string.action_resume_episode,
                    episodeLabel(episode),
                )
            }

            isSeries && episode != null -> {
                stringResource(Res.string.action_watch_episode, episodeLabel(episode))
            }

            next.started -> {
                stringResource(Res.string.action_resume)
            }

            else -> {
                stringResource(Res.string.action_watch)
            }
        }
    Row(horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3)) {
        Box(Modifier.initialFocus()) {
            SnButton(text = text, icon = SnIcons.Play, onClick = onPrimary)
            next.progress?.takeIf { it > 0f }?.let { share ->
                // A thin bar inside the pill, under the label.
                Box(
                    Modifier
                        .matchParentSize()
                        .padding(horizontal = SnSpacing.space6, vertical = 6.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(SnColors.inverseTrack, RoundedCornerShape(SnRadius.radiusFull)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(share)
                                .height(3.dp)
                                .background(SnColors.accent, RoundedCornerShape(SnRadius.radiusFull)),
                        )
                    }
                }
            }
        }
        if (isSeries && next.started) {
            SnButton(stringResource(Res.string.action_start_over), onStartOver, style = SnButtonStyle.Secondary)
        } else if (!isSeries && hasTrailer) {
            SnButton(
                stringResource(Res.string.action_trailer),
                onTrailer,
                style = SnButtonStyle.Secondary,
                icon = SnIcons.Film,
            )
        }
    }
}

@Composable
private fun EpisodesTab(
    seasons: Map<UInt, List<Episode>>,
    season: Long,
    onSeason: (Long) -> Unit,
    rowState: LazyListState,
    progress: WatchProgress?,
    watched: Set<String>,
    focusEpisode: String?,
    onFocused: () -> Unit,
    onChoose: (Episode) -> Unit,
) {
    val specials = stringResource(Res.string.details_specials)
    val watchedLabel = stringResource(Res.string.episode_watched)
    val locale = remember { Locale.getDefault(Locale.Category.FORMAT) }
    Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space4)) {
        if (seasons.size > 1) {
            SnChipRow(
                options = seasons.keys.map { it.toLong() },
                selected = season,
                label = { if (it == 0L) specials else stringResource(Res.string.details_season, it.toInt()) },
                onSelect = onSeason,
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
        SnRow(state = rowState, controlsCenter = EpisodeRowControlsCenter) {
            items(episodes, key = { it.id }) { episode ->
                val number = episode.episode?.toInt()
                val requester = remember { FocusRequester() }
                if (episode.id == focusEpisode) {
                    // Back from this episode's sources: the remote's focus returns here.
                    LaunchedEffect(Unit) {
                        runCatching { requester.requestFocus() }
                        onFocused()
                    }
                }
                val runtime = episode.runtimeMinutes?.let { stringResource(Res.string.episode_runtime, it.toInt()) }
                val date = remember(episode.released, locale) { formatReleaseDate(episode.released, locale) }
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
                    meta = listOfNotNull(runtime, date).joinToString(" · ").ifEmpty { null },
                    overview = episode.overview,
                    onClick = { onChoose(episode) },
                    modifier = Modifier.focusRequester(requester),
                    progress = progress?.takeIf { it.videoId == episode.id }?.fraction(),
                    watchedLabel = watchedLabel.takeIf { episode.id in watched },
                )
            }
        }
    }
}

/**
 * Year and runtime, the genres as links to their catalogs, then the badges that are known:
 * "4K available" once a 4K source was found, and the IMDb rating. A flow row rather than one
 * string, so each genre is its own focusable, tappable target.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetaLine(
    item: MetaItem,
    runtime: String?,
    fourK: Boolean,
    onOpenGenre: (String) -> Unit,
) {
    val style = SnTypography.label.copy(color = SnColors.fgSecondary)
    val facts = listOfNotNull(item.releaseInfo, runtime)
    val genres = item.genres.take(3)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space1),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
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
        if (fourK) MetaBadge(stringResource(Res.string.details_4k_available))
        item.imdbRating?.let { MetaBadge(stringResource(Res.string.imdb_rating, it)) }
    }
}

@Composable
private fun MetaBadge(text: String) {
    BasicText(
        text,
        modifier =
            Modifier
                .padding(start = SnSpacing.space2)
                .background(SnColors.overlayLight, RoundedCornerShape(SnRadius.radiusSm))
                .padding(horizontal = SnSpacing.space2, vertical = 2.dp),
        style = SnTypography.badge.copy(color = SnColors.fg),
        maxLines = 1,
    )
}

@Composable
private fun GenreLink(
    genre: String,
    style: TextStyle,
    onClick: () -> Unit,
) {
    SnTextLink(
        genre,
        onClick = onClick,
        modifier = Modifier.padding(vertical = SnSpacing.space1),
        style = style.copy(textDecoration = TextDecoration.Underline),
        color = SnColors.fg,
        onClickLabel = stringResource(Res.string.details_open_genre, genre),
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
