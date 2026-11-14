// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import io.github.affonsopaulo.stremionow.core.CatalogRole
import io.github.affonsopaulo.stremionow.core.CoreException
import io.github.affonsopaulo.stremionow.core.HomeEntry
import io.github.affonsopaulo.stremionow.core.HomeFeed
import io.github.affonsopaulo.stremionow.core.HomeListener
import io.github.affonsopaulo.stremionow.core.HomeReason
import io.github.affonsopaulo.stremionow.core.HomeSection
import io.github.affonsopaulo.stremionow.core.MetaItem
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.core.WatchProgress
import io.github.affonsopaulo.stremionow.ui.components.ClearHeaderOverHero
import io.github.affonsopaulo.stremionow.ui.components.EpisodeRowControlsCenter
import io.github.affonsopaulo.stremionow.ui.components.LocalHeaderInset
import io.github.affonsopaulo.stremionow.ui.components.PosterCardWidth
import io.github.affonsopaulo.stremionow.ui.components.SnButton
import io.github.affonsopaulo.stremionow.ui.components.SnEpisodeCard
import io.github.affonsopaulo.stremionow.ui.components.SnHeroBackdrop
import io.github.affonsopaulo.stremionow.ui.components.SnLoadError
import io.github.affonsopaulo.stremionow.ui.components.SnLoading
import io.github.affonsopaulo.stremionow.ui.components.SnMessage
import io.github.affonsopaulo.stremionow.ui.components.SnMinimalScroll
import io.github.affonsopaulo.stremionow.ui.components.SnPosterRow
import io.github.affonsopaulo.stremionow.ui.components.SnRow
import io.github.affonsopaulo.stremionow.ui.components.SnSectionHeader
import io.github.affonsopaulo.stremionow.ui.components.belowHeader
import io.github.affonsopaulo.stremionow.ui.components.heroHeight
import io.github.affonsopaulo.stremionow.ui.components.initialFocus
import io.github.affonsopaulo.stremionow.ui.components.snClickable
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.Loadable
import io.github.affonsopaulo.stremionow.ui.data.SecureStore
import io.github.affonsopaulo.stremionow.ui.data.io
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.platform.LocalPlatformContext
import io.github.affonsopaulo.stremionow.ui.platform.logWarning
import io.github.affonsopaulo.stremionow.ui.platform.rememberReducedMotion
import io.github.affonsopaulo.stremionow.ui.platform.screenHeightDp
import io.github.affonsopaulo.stremionow.ui.theme.FormFactor
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSize
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The home screen's rows, worked out by the core from the user's history and the addons'
 * catalogs (docs/adr/0011-home-curation.md). They arrive in stages: what the cache holds,
 * then fresh rows, then the personal recommendations; each stage replaces the last.
 */
class HomeViewModel(
    private val core: StremioCore,
    private val onSessionExpired: () -> Unit,
) : ViewModel() {
    private val mutableFeed = MutableStateFlow<Loadable<HomeFeed>>(Loadable.Loading)
    val feed = mutableFeed.asStateFlow()
    private val mutableContinueWatching = MutableStateFlow<List<WatchProgress>>(emptyList())

    /** Titles in progress; local, so it shows even offline. */
    val continueWatching = mutableContinueWatching.asStateFlow()
    private var feedJob: Job? = null
    private var started = false

    fun refreshContinueWatching() {
        viewModelScope.launch {
            mutableContinueWatching.value = runCatching { io { core.continueWatching() } }.getOrDefault(emptyList())
        }
    }

    init {
        load()
    }

    /** Loads the home from scratch, showing the loading state until the first rows arrive. */
    fun load() {
        mutableFeed.value = Loadable.Loading
        feedJob?.cancel()
        feedJob =
            viewModelScope.launch {
                // Installing the defaults can fail offline; the cached home may still load.
                runCatching { core.ensureDefaultAddons() }
                    .onFailure { logWarning("Could not install the default addons", it) }
                // Signed in: bring account changes from other devices first. An expired session
                // signs out quietly; local data stays and the app keeps working.
                runCatching { core.sync() }.onFailure { error ->
                    if (error is CoreException.SignIn) onSessionExpired()
                }
                started = true
                fetchFeed()
            }
    }

    /**
     * Works the home out again in place, as when returning from the player: the core answers
     * at once when nothing changed, and otherwise the rows update without a loading state.
     */
    fun refresh() {
        if (!started || feedJob?.isActive == true) return
        feedJob = viewModelScope.launch { fetchFeed() }
    }

    private suspend fun fetchFeed() {
        val listener =
            object : HomeListener {
                override fun onUpdate(feed: HomeFeed) {
                    mutableFeed.value = Loadable.Ready(feed)
                }
            }
        runCatching { core.homeFeed(listener) }
            .onSuccess { mutableFeed.value = Loadable.Ready(it) }
            .onFailure { error ->
                logWarning("Could not load the home screen", error)
                // Rows already on screen stay; a failure only shows when there is nothing.
                val shown = (mutableFeed.value as? Loadable.Ready)?.value
                if (shown == null || shown.sections.isEmpty()) mutableFeed.value = Loadable.Failed(error)
            }
    }
}

@Composable
fun HomeScreen(
    onOpenTitle: (MetaItem) -> Unit,
    onOpenProgress: (WatchProgress) -> Unit,
    onOpenAddons: () -> Unit,
) {
    val context = LocalPlatformContext.current
    val viewModel =
        viewModel {
            HomeViewModel(AppGraph.core(context)) {
                signOutOfStremio(context)
            }
        }
    // Signing in or out from the avatar's dialog leaves Home on screen: load it again.
    val accountChanges by AccountEvents.changes.collectAsState()
    val accountAtStart = remember { accountChanges }
    LaunchedEffect(accountChanges) { if (accountChanges != accountAtStart) viewModel.load() }
    val state by viewModel.feed.collectAsStateWithLifecycle()
    val continueWatching by viewModel.continueWatching.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshContinueWatching()
        viewModel.refresh()
    }

    val feed = (state as? Loadable.Ready)?.value
    if (feed != null && (feed.sections.isNotEmpty() || continueWatching.isNotEmpty())) {
        HomeContent(feed, continueWatching, onOpenTitle, onOpenProgress)
        return
    }
    // Without the hero the page starts below the header, like any other.
    Box(Modifier.fillMaxSize().belowHeader()) {
        when {
            state is Loadable.Failed -> {
                SnLoadError(onRetry = viewModel::load)
            }

            feed != null && feed.complete -> {
                SnMessage(
                    title = stringResource(Res.string.home_empty_title),
                    body = stringResource(Res.string.home_empty_body),
                    actionText = stringResource(Res.string.action_open_addons),
                    onAction = onOpenAddons,
                )
            }

            else -> {
                SnLoading()
            }
        }
    }
}

@Composable
private fun HomeContent(
    feed: HomeFeed,
    continueWatching: List<WatchProgress>,
    onOpenTitle: (MetaItem) -> Unit,
    onOpenProgress: (WatchProgress) -> Unit,
) {
    // Catalogs that share a name ("Popular" movies and series of one addon) say their type.
    val sharedNames =
        remember(feed.sections) {
            feed.sections
                .mapNotNull { (it.reason as? HomeReason.Catalog)?.catalog?.takeIf { c -> c.role == CatalogRole.OTHER } }
                .groupingBy { it.name }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        }
    val hero = feed.hero
    val listState = rememberLazyListState()
    // The hero's art runs under the header, which stays clear until it scrolls away.
    if (hero.isNotEmpty()) ClearHeaderOverHero(listState)
    val headerInset = LocalHeaderInset.current
    val heroHover = remember { MutableInteractionSource() }
    var heroFocused by remember { mutableStateOf(false) }
    val pick = if (hero.isEmpty()) null else rememberHeroPick(hero, listState, heroHover, heroFocused)
    // Rows arrive in stages, and the hero can appear after the first ones. A lazy list keeps
    // showing the item it showed, which would leave the new hero above the screen; so while
    // the list is at its top, it stays there. Before the layout that applies the change, so
    // the position read is the one on screen.
    val heroShown = hero.isNotEmpty()
    val hadHero = remember { booleanArrayOf(heroShown) }
    SideEffect {
        if (heroShown && !hadHero[0] && listState.firstVisibleItemIndex == 0 &&
            listState.firstVisibleItemScrollOffset == 0
        ) {
            listState.requestScrollToItem(0)
        }
        hadHero[0] = heroShown
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val page = DpSize(maxWidth, maxHeight)
        // The hero's size comes from the window alone, never from its image or text, so
        // nothing moves when they arrive.
        val heroHeight = heroItemHeight(page)
        if (pick != null) {
            // The art lies behind the list and moves with it, so it can run on under the
            // first row, fading out, with no bare band between them; the rows draw over it.
            HeroArt(
                item = pick.current,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(heroHeight + HeroArtOverlap)
                        .offset { IntOffset(0, -listState.heroScroll()) },
            )
        }
        SnMinimalScroll(topInset = headerInset) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding =
                    PaddingValues(top = if (hero.isEmpty()) headerInset else 0.dp, bottom = SnSpacing.space16),
                verticalArrangement = Arrangement.spacedBy(SnSpacing.space10),
            ) {
                if (pick != null) {
                    item(key = "hero") {
                        Hero(
                            pick = pick,
                            items = hero,
                            height = heroHeight,
                            onOpenTitle = onOpenTitle,
                            modifier =
                                Modifier
                                    .hoverable(heroHover)
                                    .onFocusChanged { heroFocused = it.hasFocus },
                        )
                    }
                }
                // Rows scroll sideways without the header's inset.
                if (continueWatching.isNotEmpty()) {
                    item(key = "continue") { SnMinimalScroll { ContinueWatchingRow(continueWatching, onOpenProgress) } }
                }
                items(feed.sections, key = { it.key }) { section ->
                    SnMinimalScroll { HomeRow(section, sharedNames, onOpenTitle) }
                }
            }
        }
    }
}

/** How far the hero has scrolled up, or far beyond the screen once it has scrolled away. */
private fun LazyListState.heroScroll(): Int = if (firstVisibleItemIndex == 0) firstVisibleItemScrollOffset else 100_000

/** One row of the home, named for why it is there. */
@Composable
private fun HomeRow(
    section: HomeSection,
    sharedNames: Set<String>,
    onOpenTitle: (MetaItem) -> Unit,
) {
    val name =
        when (val reason = section.reason) {
            HomeReason.NewEpisodes -> {
                RowName(stringResource(Res.string.home_new_episodes))
            }

            is HomeReason.BecauseYouWatched -> {
                RowName(
                    stringResource(Res.string.home_because_you_watched, reason.title),
                )
            }

            is HomeReason.MoreGenre -> {
                RowName(stringResource(Res.string.home_more_genre, reason.genre))
            }

            HomeReason.FromYourList -> {
                RowName(stringResource(Res.string.home_from_your_list))
            }

            is HomeReason.Catalog -> {
                catalogRowName(reason.catalog, reason.catalog.name in sharedNames)
            }
        }
    Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space3)) {
        SnSectionHeader(name.title, caption = name.caption)
        when {
            section.loading -> PlaceholderRow()
            section.reason is HomeReason.NewEpisodes -> NewEpisodesRow(section.entries, onOpenTitle)
            else -> SnPosterRow(section.entries.map { it.item }, onOpenTitle, subtitle = { it.cardSubtitle() })
        }
    }
}

/** Series with a new episode: the episode's still, the series' name and the episode. */
@Composable
private fun NewEpisodesRow(
    entries: List<HomeEntry>,
    onOpenTitle: (MetaItem) -> Unit,
) {
    SnRow(controlsCenter = EpisodeRowControlsCenter) {
        items(entries, key = { it.item.id }) { entry ->
            val episode = entry.episode
            val season = episode?.season?.toInt()
            val number = episode?.episode?.toInt()
            val label =
                if (season != null && number != null) {
                    stringResource(
                        Res.string.home_new_episode_meta,
                        stringResource(Res.string.episode_short, season, number),
                    )
                } else {
                    null
                }
            SnEpisodeCard(
                title = entry.item.name,
                thumbnail = episode?.thumbnail ?: entry.item.background ?: entry.item.poster,
                meta = label,
                overview = episode?.title?.takeIf { it.isNotBlank() },
                onClick = { onOpenTitle(entry.item) },
                highlighted = true,
            )
        }
    }
}

/** Where a row still loading will be: blank posters, hidden from screen readers. */
@Composable
private fun PlaceholderRow() {
    Row(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {}
            .padding(horizontal = SnLayout.gutter, vertical = SnSpacing.space2),
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space4),
    ) {
        repeat(PLACEHOLDER_CARDS) {
            Box(
                Modifier
                    .width(PosterCardWidth)
                    .aspectRatio(2f / 3f)
                    .background(SnColors.surfaceMuted, RoundedCornerShape(SnRadius.radiusMd)),
            )
        }
    }
}

private const val PLACEHOLDER_CARDS = 12

/** How wide the hero's title, synopsis and buttons may grow. */
private val HeroTextWidth = 560.dp

/** How far the hero's art runs on under the first row, fading out. */
private val HeroArtOverlap = 96.dp

/** How long each title stays in the hero before the next one. */
private const val HERO_ROTATION_MS = 8_000L
private const val HERO_CROSSFADE_MS = 700

/**
 * The title the hero shows, where it is among the picks, whether it ever moved on, and how
 * to show another pick.
 */
private class HeroPick(
    val current: MetaItem,
    val position: Int,
    /**
     * On TV, whether the shown title's button takes the first focus: only the first title
     * shown, before anything moved, so a later title never pulls focus from the rows.
     */
    val takesFocus: Boolean,
    val select: (Int) -> Unit,
)

/**
 * Rotates the hero through its picks every few seconds. It holds still while the pointer is
 * over it or focus is in it, so nothing moves under the user's hand or remote, while it is
 * scrolled away or the app is in the background, and when the system asks for less motion.
 * Choosing a pick by hand restarts the wait.
 *
 * The title on show is followed by id, so later updates of the picks (personal ones arriving)
 * never swap it out from under the user; while they are on it, it stays even if it left them.
 */
@Composable
private fun rememberHeroPick(
    items: List<MetaItem>,
    listState: LazyListState,
    hover: MutableInteractionSource,
    focused: Boolean,
): HeroPick {
    val hovered by hover.collectIsHoveredAsState()
    // Only the first title takes the initial focus on TV; later ones must never take it.
    var rotated by rememberSaveable { mutableStateOf(false) }
    var shownId by rememberSaveable { mutableStateOf(items.first().id) }
    var lastShown by remember { mutableStateOf(items.first()) }
    val current = items.firstOrNull { it.id == shownId } ?: if (focused || hovered) lastShown else items.first()
    SideEffect {
        lastShown = current
        shownId = current.id
    }
    val position = items.indexOfFirst { it.id == current.id }
    val firstId = remember { current.id }
    val reducedMotion = rememberReducedMotion()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow
        .collectAsState()
    val onScreen by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val still = hovered || focused || reducedMotion || !onScreen || !lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(items, still, current.id) {
        if (still || items.size < 2) return@LaunchedEffect
        delay(HERO_ROTATION_MS)
        rotated = true
        shownId = items[(position + 1) % items.size].id
    }
    return HeroPick(current, position, takesFocus = !rotated && current.id == firstId) { index ->
        rotated = true
        shownId = items[index.mod(items.size)].id
    }
}

/** Height of the home hero, header included: from the window alone, never from its content. */
@Composable
private fun heroItemHeight(page: DpSize): Dp {
    val headerInset = LocalHeaderInset.current
    return when (SnLayout.formFactor) {
        FormFactor.Phone -> 360.dp + headerInset

        // On TV the whole hero must fit on screen, logo included, with the first row peeking.
        FormFactor.Tv -> (screenHeightDp() * 0.62f).dp + headerInset

        // The picture's own shape as the window grows, leaving room for the first row.
        FormFactor.Large -> heroHeight(page.width, page.height, min = 420.dp + headerInset)
    }
}

/** The hero's art, crossfading from one title to the next. */
@Composable
private fun HeroArt(
    item: MetaItem,
    modifier: Modifier = Modifier,
) {
    Crossfade(targetState = item, animationSpec = tween(HERO_CROSSFADE_MS), modifier = modifier, label = "hero-art") {
        SnHeroBackdrop(url = it.background, textWidth = HeroTextWidth, modifier = Modifier.fillMaxSize())
    }
}

private val DotSize = 8.dp

/** Width of a dot's target: narrower than the 44 dp minimum, but as tall, side by side. */
private val DotTargetWidth = 28.dp

/**
 * Previous and next buttons around one dot per pick. Each dot shows its pick, and says which
 * it is ("Pick 2 of 5: Halloween"); the controls are small, like the dots, with targets as
 * tall as any button. They take focus like any button, never on their own.
 */
@Composable
private fun HeroControls(
    items: List<MetaItem>,
    pick: HeroPick,
    modifier: Modifier = Modifier,
) {
    // A phone swipes the hero, and its button leaves little room beside it: dots only, with
    // narrower targets. Elsewhere, previous and next buttons around the dots.
    val phone = SnLayout.formFactor == FormFactor.Phone
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val dotTarget = if (phone) PhoneDotTargetWidth else DotTargetWidth
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (!phone) {
            HeroStep(
                icon = if (rtl) SnIcons.ChevronRight else SnIcons.ChevronLeft,
                label = stringResource(Res.string.home_hero_previous),
                onClick = { pick.select(pick.position - 1) },
            )
        }
        items.forEachIndexed { index, item ->
            val active = index == pick.position
            Box(
                Modifier.size(if (active) DotSize * 3 + SnSpacing.space3 else dotTarget, SnSize.controlMd),
                contentAlignment = Alignment.Center,
            ) {
                val label = stringResource(Res.string.home_hero_pick, index + 1, items.size, item.name)
                Box(
                    Modifier
                        .matchParentSize()
                        .semantics {
                            contentDescription = label
                            selected = active
                        }.snClickable(RoundedCornerShape(SnRadius.radiusFull)) { pick.select(index) },
                )
                HeroDot(active)
            }
        }
        if (!phone) {
            HeroStep(
                icon = if (rtl) SnIcons.ChevronLeft else SnIcons.ChevronRight,
                label = stringResource(Res.string.home_hero_next),
                onClick = { pick.select(pick.position + 1) },
            )
        }
    }
}

/** On phones, a dot's target is narrower still, so the dots fit beside a long button label. */
private val PhoneDotTargetWidth = 22.dp

/** One dot of the hero's indicator, longer for the title on show. */
@Composable
private fun HeroDot(active: Boolean) {
    val width by animateDpAsState(if (active) DotSize * 3 else DotSize, tween(HERO_CROSSFADE_MS / 2))
    Box(
        Modifier
            .height(DotSize)
            .width(width)
            .background(
                if (active) SnColors.fg else SnColors.fg.copy(alpha = 0.45f),
                RoundedCornerShape(SnRadius.radiusFull),
            ),
    )
}

/** A small previous or next button of the hero. */
@Composable
private fun HeroStep(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(SnSize.controlMd)
            .semantics { contentDescription = label }
            .snClickable(CircleShape, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            colorFilter = ColorFilter.tint(SnColors.fgSecondary),
        )
    }
}

/** The hero's title, facts, synopsis and button, over the art drawn behind the list. */
@Composable
private fun Hero(
    pick: HeroPick,
    items: List<MetaItem>,
    height: Dp,
    onOpenTitle: (MetaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val headerInset = LocalHeaderInset.current
    val swipe = SwipeThreshold.let { with(LocalDensity.current) { it.toPx() } }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var dragged by remember { mutableFloatStateOf(0f) }
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            // A sideways swipe shows the next or previous pick, as on any carousel.
            .then(
                if (items.size > 1) {
                    Modifier.draggable(
                        state = rememberDraggableState { dragged += it },
                        orientation = Orientation.Horizontal,
                        onDragStarted = { dragged = 0f },
                        onDragStopped = {
                            val toNext = if (rtl) dragged > swipe else dragged < -swipe
                            val toPrevious = if (rtl) dragged < -swipe else dragged > swipe
                            when {
                                toNext -> pick.select(pick.position + 1)
                                toPrevious -> pick.select(pick.position - 1)
                            }
                        },
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        Crossfade(
            targetState = pick.current,
            animationSpec = tween(HERO_CROSSFADE_MS),
            modifier = Modifier.align(Alignment.BottomStart),
            label = "hero-text",
        ) { item ->
            Column(
                modifier =
                    Modifier
                        .widthIn(max = HeroTextWidth + SnLayout.gutter * 2)
                        .padding(top = headerInset + SnSpacing.space8)
                        .padding(horizontal = SnLayout.gutter),
                verticalArrangement = Arrangement.spacedBy(SnSpacing.space4),
            ) {
                TitleLogo(item, maxLines = 2)
                BasicText(item.metaLine(), style = SnTypography.label.copy(color = SnColors.fgSecondary), maxLines = 1)
                item.description?.let {
                    BasicText(
                        it,
                        style = SnTypography.bodyLg.copy(color = SnColors.fg),
                        maxLines = if (SnLayout.formFactor == FormFactor.Tv) 2 else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SnButton(
                    text = stringResource(Res.string.action_more_info),
                    onClick = { onOpenTitle(item) },
                    icon = SnIcons.Info,
                    modifier = if (pick.takesFocus && item.id == pick.current.id) Modifier.initialFocus() else Modifier,
                )
            }
        }
        if (items.size > 1) {
            HeroControls(
                items = items,
                pick = pick,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            // The arrows' own padding stands in for part of the gutter.
                            end =
                                if (SnLayout.formFactor == FormFactor.Phone) {
                                    SnLayout.gutter
                                } else {
                                    SnLayout.gutter - SnSpacing.space3
                                },
                            bottom =
                                (SnSize.controlLg - SnSize.controlMd) / 2,
                        ),
            )
        }
    }
}

/** How far a swipe on the hero must travel to change the pick. */
private val SwipeThreshold = 48.dp

/** The title's logo image when the addon has one, else its name in the display face. */
@Composable
fun TitleLogo(
    item: MetaItem,
    modifier: Modifier = Modifier,
    maxLines: Int = 3,
) {
    if (item.logo != null) {
        AsyncImage(
            model = item.logo,
            contentDescription = item.name,
            modifier = modifier.heightIn(max = 96.dp).widthIn(max = 420.dp),
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
        )
    } else {
        BasicText(
            text = item.name.uppercase(),
            modifier = modifier,
            style = SnTypography.logoXl.copy(color = SnColors.fg),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Titles in progress, with how far along each one is and the time left. */
@Composable
private fun ContinueWatchingRow(
    items: List<WatchProgress>,
    onOpen: (WatchProgress) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space3)) {
        SnSectionHeader(stringResource(Res.string.home_continue_watching))
        SnRow(controlsCenter = EpisodeRowControlsCenter) {
            items(items, key = { it.id }) { progress ->
                val season = progress.season?.toInt()
                val episode = progress.episode?.toInt()
                val minutesLeft = ((progress.durationMs - progress.positionMs).toLong() / 60_000).coerceAtLeast(1)
                val meta =
                    listOfNotNull(
                        if (season != null &&
                            episode != null
                        ) {
                            stringResource(Res.string.episode_short, season, episode)
                        } else {
                            null
                        },
                        stringResource(Res.string.time_left, "$minutesLeft min"),
                    ).joinToString(" · ")
                SnEpisodeCard(
                    title = progress.name,
                    thumbnail = progress.background ?: progress.poster,
                    meta = meta,
                    overview = null,
                    onClick = { onOpen(progress) },
                    progress =
                        if (progress.durationMs >
                            0u
                        ) {
                            progress.positionMs.toFloat() / progress.durationMs.toFloat()
                        } else {
                            null
                        },
                )
            }
        }
    }
}
