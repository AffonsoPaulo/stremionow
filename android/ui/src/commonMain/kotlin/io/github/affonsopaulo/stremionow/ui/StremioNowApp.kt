// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.savedstate.read
import io.github.affonsopaulo.stremionow.ui.components.AppShortcuts
import io.github.affonsopaulo.stremionow.ui.components.HeaderOverlay
import io.github.affonsopaulo.stremionow.ui.components.HeaderSearchState
import io.github.affonsopaulo.stremionow.ui.components.LocalHeaderInset
import io.github.affonsopaulo.stremionow.ui.components.LocalHeaderOverlay
import io.github.affonsopaulo.stremionow.ui.components.LocalHeaderPage
import io.github.affonsopaulo.stremionow.ui.components.SnHeader
import io.github.affonsopaulo.stremionow.ui.components.SnHeaderSearch
import io.github.affonsopaulo.stremionow.ui.components.SnPhoneHeader
import io.github.affonsopaulo.stremionow.ui.components.SnTabBar
import io.github.affonsopaulo.stremionow.ui.components.TopDestination
import io.github.affonsopaulo.stremionow.ui.components.belowHeader
import io.github.affonsopaulo.stremionow.ui.components.rememberHeaderSearchState
import io.github.affonsopaulo.stremionow.ui.crash.crashReportPrompt
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.PlaybackSession
import io.github.affonsopaulo.stremionow.ui.data.TitleLink
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.platform.LocalPlatformContext
import io.github.affonsopaulo.stremionow.ui.screens.AddonsScreen
import io.github.affonsopaulo.stremionow.ui.screens.DetailsScreen
import io.github.affonsopaulo.stremionow.ui.screens.DevicesScreen
import io.github.affonsopaulo.stremionow.ui.screens.ExploreScreen
import io.github.affonsopaulo.stremionow.ui.screens.HomeScreen
import io.github.affonsopaulo.stremionow.ui.screens.LibraryScreen
import io.github.affonsopaulo.stremionow.ui.screens.OnboardingScreen
import io.github.affonsopaulo.stremionow.ui.screens.PlayerScreen
import io.github.affonsopaulo.stremionow.ui.screens.ProfileScreen
import io.github.affonsopaulo.stremionow.ui.screens.SearchScreen
import io.github.affonsopaulo.stremionow.ui.screens.SettingsScreen
import io.github.affonsopaulo.stremionow.ui.theme.FormFactor
import io.github.affonsopaulo.stremionow.ui.theme.LocalIsTv
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

private object Routes {
    const val HOME = "home"
    const val SERIES = "explore/series"
    const val MOVIES = "explore/movie"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val PROFILE = "profile"
    const val ADDONS = "addons"
    const val DETAILS = "details/{kind}/{id}"
    const val GENRE = "genre/{kind}/{genre}"
    const val PERSON = "person/{name}"
    const val SETTINGS = "settings"
    const val DEVICES = "settings/devices"
    const val ONBOARDING = "onboarding"
    const val SIGN_IN = "sign-in"
    const val PLAYER = "player"

    fun details(
        kind: String,
        id: String,
    ) = "details/${kind.encoded()}/${id.encoded()}"

    fun genre(
        kind: String,
        genre: String,
    ) = "genre/${kind.encoded()}/${genre.encoded()}"

    fun person(name: String) = "person/${name.encoded()}"

    /**
     * Percent-encodes a route argument as Android's `Uri.encode` does: ASCII letters, digits
     * and `_-!.~'()*` stay, every other UTF-8 byte becomes `%XX`. Navigation decodes it again.
     */
    private fun String.encoded(): String =
        buildString {
            for (byte in this@encoded.encodeToByteArray()) {
                val code = byte.toInt() and 0xFF
                val char = code.toChar()
                if (code < 0x80 && (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "_-!.~'()*")) {
                    append(char)
                } else {
                    append('%').append(HEX_DIGITS[code shr 4]).append(HEX_DIGITS[code and 0xF])
                }
            }
        }

    private const val HEX_DIGITS = "0123456789ABCDEF"
}

/** Root of the interface, shared by the phone, TV and desktop apps. */
@Composable
fun StremioNowApp(
    isTv: Boolean,
    link: TitleLink.Target? = null,
    onLinkOpened: () -> Unit = {},
    shortcuts: AppShortcuts? = null,
) {
    CompositionLocalProvider(LocalIsTv provides isTv) {
        // Asks about the last crash report; in a crash loop, before the app starts.
        if (crashReportPrompt()) return@CompositionLocalProvider
        val navController = rememberNavController()
        val entry by navController.currentBackStackEntryAsState()
        val current =
            when (entry?.destination?.route) {
                Routes.HOME -> TopDestination.Home
                Routes.SERIES -> TopDestination.Series
                Routes.MOVIES -> TopDestination.Movies
                Routes.SEARCH -> TopDestination.Search
                Routes.LIBRARY -> TopDestination.MyList
                Routes.PROFILE -> TopDestination.Profile
                else -> null
            }
        // The tab whose stack the user is in, so it stays highlighted on inner pages too. Kept
        // here rather than read from the back stack, whose API is internal to Navigation.
        var sectionRoute by rememberSaveable { mutableStateOf(Routes.HOME) }
        LaunchedEffect(entry?.destination?.route) {
            entry
                ?.destination
                ?.route
                ?.takeIf { it in TopRoutes }
                ?.let { sectionRoute = it }
        }
        val section = TopRoutes[sectionRoute]
        val phone = SnLayout.formFactor == FormFactor.Phone
        // The player owns the whole screen: no header, no tab bar, no insets.
        val immersive = entry?.destination?.route == Routes.PLAYER
        // First run shows the setup instead of the app's navigation.
        val context = LocalPlatformContext.current
        val startRoute =
            remember {
                val onboarded = runCatching { AppGraph.core(context).settings().onboarded }.getOrDefault(true)
                if (onboarded) Routes.HOME else Routes.ONBOARDING
            }
        val setup = entry?.destination?.route == Routes.ONBOARDING || entry?.destination?.route == Routes.SIGN_IN
        // Bumped when Search is chosen while already open, to focus the field and raise the keyboard.
        var searchFocusRequests by remember { mutableIntStateOf(0) }
        val openTab = { destination: TopDestination ->
            navController.openTop(destination, sectionRoute, { sectionRoute = it }) {
                if (destination == TopDestination.Search) searchFocusRequests++
            }
        }
        // While typing, the keyboard takes the tab bar's place instead of stacking above it.
        val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        // No chrome over the video or the first-run setup.
        val chrome = !immersive && !setup
        val headerOverlay = remember { HeaderOverlay() }
        val headerOpacity = rememberHeaderOpacity(headerOverlay, entry)
        // Large pointer screens search from a field in the header; TV keeps search in its
        // navigation row and phones in the tab bar.
        val headerSearch = rememberHeaderSearchState()
        val searchInHeader = SnLayout.formFactor == FormFactor.Large
        val searchActions =
            rememberHeaderSearchActions(headerSearch, entry?.destination?.route, sectionRoute, openTab)
        val appShortcuts = shortcuts ?: remember { AppShortcuts() }
        val shortcutsOn = searchInHeader && chrome
        DisposableEffect(appShortcuts, shortcutsOn) {
            appShortcuts.onSearch = if (shortcutsOn) headerSearch::focus else null
            onDispose { appShortcuts.onSearch = null }
        }
        Column(
            Modifier
                .fillMaxSize()
                // Without a host feeding it the window's keys, it sees them from inside the app.
                .then(if (shortcuts == null) Modifier.onPreviewKeyEvent(appShortcuts::onPreviewKeyEvent) else Modifier)
                .background(SnColors.bg)
                // The header pads itself for the status bar, so title art can run under both;
                // on phones the tab bar does the same for the navigation bar.
                .windowInsetsPadding(
                    if (immersive) {
                        WindowInsets(0)
                    } else {
                        var sides = WindowInsetsSides.Horizontal
                        if (!chrome) sides += WindowInsetsSides.Top
                        if (phone) {
                            WindowInsets.safeDrawing.only(sides).union(WindowInsets.ime)
                        } else {
                            WindowInsets.safeDrawing.only(sides + WindowInsetsSides.Bottom)
                        }
                    },
                ),
        ) {
            HeaderOverPages(
                modifier = Modifier.weight(1f),
                header =
                    if (chrome) {
                        {
                            AppHeader(
                                entry,
                                current,
                                section,
                                navController,
                                openTab,
                                opacity = { headerOpacity.value },
                                search =
                                    if (searchInHeader) {
                                        {
                                            SnHeaderSearch(
                                                state = headerSearch,
                                                expanded = searchActions.expanded,
                                                onQueryChange = searchActions::type,
                                                onClear = searchActions::dismiss,
                                                onDismiss = searchActions::dismiss,
                                            )
                                        }
                                    } else {
                                        null
                                    },
                            )
                        }
                    } else {
                        null
                    },
            ) {
                CompositionLocalProvider(LocalHeaderOverlay provides headerOverlay) {
                    AppNavHost(
                        navController,
                        startRoute,
                        searchFocusRequests,
                        openTab,
                        headerQuery = if (searchInHeader) headerSearch.query else null,
                    )
                }
            }
            // A link from outside (the TV home screen) opens its title over whatever is showing.
            LaunchedEffect(link, setup) {
                if (link != null && !setup) {
                    navController.navigate(Routes.details(link.kind, link.id)) { launchSingleTop = true }
                    onLinkOpened()
                }
            }
            if (phone && !keyboardOpen && !immersive && !setup) {
                SnTabBar(current = section, onNavigate = openTab)
            }
        }
    }
}

/**
 * The phone top bar or the large-screen header, over a background at [opacity] that also
 * covers the status bar.
 */
@Composable
private fun AppHeader(
    entry: NavBackStackEntry?,
    current: TopDestination?,
    section: TopDestination?,
    navController: NavHostController,
    openTab: (TopDestination) -> Unit,
    opacity: () -> Float,
    search: (@Composable () -> Unit)?,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(SnColors.bg.copy(alpha = opacity())) }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
    ) {
        if (SnLayout.formFactor == FormFactor.Phone) {
            SnPhoneHeader(
                title = phoneTitle(entry?.destination?.route, entry?.arguments?.read { getStringOrNull("genre") }),
                onBack = if (current == null) ({ navController.navigateUp() }) else null,
                profileSelected = section == TopDestination.Profile,
                onOpenProfile = { openTab(TopDestination.Profile) },
            )
        } else {
            SnHeader(
                current = section,
                onNavigate = openTab,
                onBack =
                    if (current == null && SnLayout.formFactor != FormFactor.Tv) {
                        { navController.navigateUp() }
                    } else {
                        null
                    },
                search = search,
            )
        }
    }
}

/** The app's pages. Those with title art at the top (home, a title) run under the header. */
@Composable
private fun AppNavHost(
    navController: NavHostController,
    startRoute: String,
    searchFocusRequests: Int,
    openTab: (TopDestination) -> Unit,
    headerQuery: String?,
) {
    NavHost(navController = navController, startDestination = startRoute, modifier = Modifier.fillMaxSize()) {
        val openTitle = { kind: String, id: String -> navController.navigate(Routes.details(kind, id)) }
        page(Routes.HOME, underHeader = true) {
            HomeScreen(
                onOpenTitle = { openTitle(it.kind, it.id) },
                onOpenProgress = { openTitle(it.kind, it.id) },
                onOpenAddons = { navController.navigate(Routes.ADDONS) { launchSingleTop = true } },
            )
        }
        page(Routes.SERIES) { ExploreScreen("series", onOpenTitle = { openTitle(it.kind, it.id) }) }
        page(Routes.MOVIES) { ExploreScreen("movie", onOpenTitle = { openTitle(it.kind, it.id) }) }
        page(Routes.SEARCH) {
            SearchScreen(
                onOpenTitle = { openTitle(it.kind, it.id) },
                focusRequests = searchFocusRequests,
                headerQuery = headerQuery,
            )
        }
        page(
            Routes.GENRE,
            arguments =
                listOf(
                    navArgument("kind") { type = NavType.StringType },
                    navArgument("genre") { type = NavType.StringType },
                ),
        ) { backStack ->
            ExploreScreen(
                kind = backStack.arguments?.read { getStringOrNull("kind") }.orEmpty(),
                initialGenre = backStack.arguments?.read { getStringOrNull("genre") },
                onOpenTitle = { openTitle(it.kind, it.id) },
            )
        }
        page(Routes.PERSON, arguments = listOf(navArgument("name") { type = NavType.StringType })) {
            val name = it.arguments?.read { getStringOrNull("name") }.orEmpty()
            SearchScreen(
                initialQuery = name,
                onOpenTitle = { item -> openTitle(item.kind, item.id) },
                // Where the header has the search field, the page names whose titles these are.
                title = if (headerQuery != null) name else null,
            )
        }
        page(Routes.LIBRARY) {
            LibraryScreen(onOpenTitle = openTitle, onExplore = { openTab(TopDestination.Series) })
        }
        page(Routes.PROFILE) {
            ProfileScreen(
                onOpenAddons = { navController.navigate(Routes.ADDONS) { launchSingleTop = true } },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                onSignIn = { navController.navigate(Routes.SIGN_IN) { launchSingleTop = true } },
            )
        }
        page(Routes.ADDONS) { AddonsScreen() }
        page(Routes.SETTINGS) {
            SettingsScreen(onOpenDevices = { navController.navigate(Routes.DEVICES) { launchSingleTop = true } })
        }
        page(Routes.DEVICES) { DevicesScreen() }
        page(Routes.SIGN_IN) {
            OnboardingScreen(onFinished = { navController.popBackStack() }, signInOnly = true)
        }
        page(Routes.ONBOARDING) {
            OnboardingScreen(
                onFinished = {
                    navController.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                },
            )
        }
        page(
            Routes.DETAILS,
            arguments =
                listOf(
                    navArgument("kind") { type = NavType.StringType },
                    navArgument("id") { type = NavType.StringType },
                ),
            underHeader = true,
        ) { backStack ->
            DetailsScreen(
                kind = backStack.arguments?.read { getStringOrNull("kind") }.orEmpty(),
                id = backStack.arguments?.read { getStringOrNull("id") }.orEmpty(),
                onOpenTitle = { openTitle(it.kind, it.id) },
                onOpenGenre = { kind, genre -> navController.navigate(Routes.genre(kind, genre)) },
                onSearchPerson = { navController.navigate(Routes.person(it)) },
                // The sources are on the title's page, so Back from the player returns there.
                onPlay = { navController.navigate(Routes.PLAYER) { launchSingleTop = true } },
            )
        }
        page(Routes.PLAYER) {
            PlayerScreen(
                onBack = { navController.popBackStack() },
                // Both return to the title's page, which shows the sources in place of the
                // episodes; for the next episode it plays the source of the same binge group
                // by itself when one shows up.
                onChangeSource = {
                    PlaybackSession.returnToSources()
                    navController.popBackStack()
                },
                onOpenEpisode = { episode ->
                    val current = PlaybackSession.selection ?: return@PlayerScreen
                    PlaybackSession.continueWith = PlaybackSession.source?.bingeGroup
                    PlaybackSession.select(current.copy(episode = episode))
                    PlaybackSession.returnToSources()
                    navController.popBackStack()
                },
            )
        }
    }
}

/**
 * A destination of [AppNavHost]. Pages start below the header unless [underHeader] is set,
 * in which case they run under it and keep their own content clear of it.
 */
private fun NavGraphBuilder.page(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    underHeader: Boolean = false,
    content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable(route, arguments) { entry ->
        CompositionLocalProvider(LocalHeaderPage provides entry) {
            Box(if (underHeader) Modifier.fillMaxSize() else Modifier.fillMaxSize().belowHeader()) { content(entry) }
        }
    }
}

/**
 * Lays the header over the pages, measuring it first so the pages learn its height through
 * [LocalHeaderInset] in the same frame, even as it changes with the window.
 */
@Composable
private fun HeaderOverPages(
    header: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    pages: @Composable () -> Unit,
) {
    SubcomposeLayout(modifier) { constraints ->
        val loose = constraints.copy(minHeight = 0)
        val headerPlaceables = header?.let { subcompose(HeaderSlot.Header, it).map { part -> part.measure(loose) } }
        val inset = (headerPlaceables?.maxOfOrNull { it.height } ?: 0).toDp()
        val pagePlaceables =
            subcompose(HeaderSlot.Pages) {
                CompositionLocalProvider(LocalHeaderInset provides inset, content = pages)
            }.map { it.measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            pagePlaceables.forEach { it.place(0, 0) }
            // Placed over the pages, so it draws on top and takes touches first; composed
            // before them, so it still comes first in focus order.
            headerPlaceables?.forEach { it.place(0, 0, zIndex = 1f) }
        }
    }
}

private enum class HeaderSlot { Header, Pages }

/**
 * The header background's opacity over the current page. It follows the page's scroll frame
 * by frame and eases over [HEADER_FADE_MS] when it would otherwise jump, as when another page
 * opens or the page's art appears, so the header never flickers.
 */
@Composable
private fun rememberHeaderOpacity(
    overlay: HeaderOverlay,
    entry: NavBackStackEntry?,
): State<Float> {
    val opacity = remember { Animatable(1f) }
    LaunchedEffect(overlay, entry) {
        snapshotFlow { overlay.opacityOver(entry) }.collectLatest { target ->
            if (abs(target - opacity.value) < HEADER_SNAP) {
                opacity.snapTo(target)
            } else {
                opacity.animateTo(target, tween(HEADER_FADE_MS))
            }
        }
    }
    return opacity.asState()
}

/** Largest opacity change applied at once; a scroll moves less than this in a frame. */
private const val HEADER_SNAP = 0.34f

private const val HEADER_FADE_MS = 180

/**
 * Title for the phone top bar: null shows the wordmark (Home), an empty string shows nothing
 * (the details page has its own title art).
 */
@Composable
private fun phoneTitle(
    route: String?,
    genre: String?,
): String? =
    when (route) {
        Routes.GENRE -> genre.orEmpty()
        Routes.PERSON -> stringResource(Res.string.nav_search)
        Routes.SERIES -> stringResource(Res.string.nav_series)
        Routes.MOVIES -> stringResource(Res.string.nav_movies)
        Routes.SEARCH -> stringResource(Res.string.nav_search)
        Routes.LIBRARY -> stringResource(Res.string.nav_my_list)
        Routes.PROFILE -> stringResource(Res.string.profile_title)
        Routes.ADDONS -> stringResource(Res.string.nav_addons)
        Routes.DETAILS -> ""
        Routes.SETTINGS -> stringResource(Res.string.settings_title)
        Routes.DEVICES -> stringResource(Res.string.devices_title)
        else -> null
    }

/** Routes of the top-level destinations. */
private val TopRoutes =
    mapOf(
        Routes.HOME to TopDestination.Home,
        Routes.SERIES to TopDestination.Series,
        Routes.MOVIES to TopDestination.Movies,
        Routes.SEARCH to TopDestination.Search,
        Routes.LIBRARY to TopDestination.MyList,
        Routes.PROFILE to TopDestination.Profile,
    )

/**
 * Opens a top-level destination, keeping a single copy of each on the back stack and each
 * tab's inner pages for when the user comes back to it. Choosing the tab the user is already
 * in returns to its start instead, as on every Android app with tabs; restoring its saved
 * state there would reopen the very page the user is trying to leave. [onReselect] runs then,
 * for screens that react to a second tap, like Search focusing its field.
 */
private fun NavHostController.openTop(
    destination: TopDestination,
    currentSection: String,
    onSection: (String) -> Unit,
    onReselect: () -> Unit = {},
) {
    val route = TopRoutes.entries.first { it.value == destination }.key
    if (currentSection == route) {
        popBackStack(route, inclusive = false)
        onReselect()
        return
    }
    onSection(route)
    navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * What the header's search field does in the app: typing opens the Search page and drives its
 * query; clearing it or pressing Esc closes it and returns to the tab the user searched from.
 * On the Search page the field stays open, showing its query.
 */
@Stable
private class HeaderSearchActions(
    private val state: HeaderSearchState,
) {
    var route: String? by mutableStateOf(null)
    var sectionRoute: String = Routes.HOME
    var openTab: (TopDestination) -> Unit = {}

    /** The tab the user searched from, to return to when the search closes. */
    private var returnTo: String? = null

    val expanded: Boolean get() = state.open || route == Routes.SEARCH

    fun type(text: String) {
        state.query = text
        if (text.isBlank() || route == Routes.SEARCH) return
        if (sectionRoute != Routes.SEARCH) returnTo = sectionRoute
        openTab(TopDestination.Search)
    }

    fun dismiss() {
        state.query = ""
        state.open = false
        if (route == Routes.SEARCH) openTab(TopRoutes[returnTo ?: Routes.HOME] ?: TopDestination.Home)
    }

    /** Opening the field away from earlier results starts a new search. */
    fun onOpen() {
        if (sectionRoute != Routes.SEARCH) state.query = ""
    }
}

@Composable
private fun rememberHeaderSearchActions(
    state: HeaderSearchState,
    route: String?,
    sectionRoute: String,
    openTab: (TopDestination) -> Unit,
): HeaderSearchActions {
    val actions = remember(state) { HeaderSearchActions(state) }
    actions.route = route
    actions.sectionRoute = sectionRoute
    actions.openTab = openTab
    val focusManager = LocalFocusManager.current
    LaunchedEffect(state.open) { if (state.open) actions.onOpen() }
    // Leaving the field away from the Search page closes it; so does opening a result.
    LaunchedEffect(state.focused, route) {
        if (!state.focused && route != Routes.SEARCH) state.open = false
    }
    // Closed from its own keys (Esc, clear), it lets go of the keyboard focus as well.
    LaunchedEffect(state.open, route) {
        if (!state.open && route != Routes.SEARCH && state.focused) focusManager.clearFocus()
    }
    return actions
}
