// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.affonsopaulo.stremionow.core.Episode
import io.github.affonsopaulo.stremionow.core.MetaDetails
import io.github.affonsopaulo.stremionow.ui.components.SnHeader
import io.github.affonsopaulo.stremionow.ui.components.SnPhoneHeader
import io.github.affonsopaulo.stremionow.ui.components.SnTabBar
import io.github.affonsopaulo.stremionow.ui.components.TopDestination
import io.github.affonsopaulo.stremionow.ui.data.PlaybackSession
import io.github.affonsopaulo.stremionow.ui.screens.AddonsScreen
import io.github.affonsopaulo.stremionow.ui.screens.DetailsScreen
import io.github.affonsopaulo.stremionow.ui.screens.ExploreScreen
import io.github.affonsopaulo.stremionow.ui.screens.HomeScreen
import io.github.affonsopaulo.stremionow.ui.screens.LibraryScreen
import io.github.affonsopaulo.stremionow.ui.screens.PlayerScreen
import io.github.affonsopaulo.stremionow.ui.screens.ProfileScreen
import io.github.affonsopaulo.stremionow.ui.screens.SearchScreen
import io.github.affonsopaulo.stremionow.ui.screens.SettingsScreen
import io.github.affonsopaulo.stremionow.ui.screens.SourcesScreen
import io.github.affonsopaulo.stremionow.ui.theme.FormFactor
import io.github.affonsopaulo.stremionow.ui.theme.LocalIsTv
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout

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
    const val SOURCES = "sources"
    const val SETTINGS = "settings"
    const val PLAYER = "player"

    fun details(
        kind: String,
        id: String,
    ) = "details/${Uri.encode(kind)}/${Uri.encode(id)}"

    fun genre(
        kind: String,
        genre: String,
    ) = "genre/${Uri.encode(kind)}/${Uri.encode(genre)}"

    fun person(name: String) = "person/${Uri.encode(name)}"
}

/** Root of the interface, shared by the phone and TV apps. */
@Composable
fun StremioNowApp(isTv: Boolean) {
    CompositionLocalProvider(LocalIsTv provides isTv) {
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
        // The tab whose stack the user is in, so it stays highlighted on inner pages too.
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
        // Bumped when Search is chosen while already open, to focus the field and raise the keyboard.
        var searchFocusRequests by remember { mutableIntStateOf(0) }
        val openTab = { destination: TopDestination ->
            navController.openTop(destination, sectionRoute, { sectionRoute = it }) {
                if (destination == TopDestination.Search) searchFocusRequests++
            }
        }
        // While typing, the keyboard takes the tab bar's place instead of stacking above it.
        val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        Column(
            Modifier
                .fillMaxSize()
                .background(SnColors.bg)
                // On phones the tab bar pads itself so its background reaches the screen edge.
                .windowInsetsPadding(
                    if (immersive) {
                        WindowInsets(0)
                    } else if (phone) {
                        WindowInsets.safeDrawing
                            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                            .union(WindowInsets.ime)
                    } else {
                        WindowInsets.safeDrawing
                    },
                ),
        ) {
            if (immersive) {
                // No chrome over the video.
            } else if (phone) {
                SnPhoneHeader(
                    title = phoneTitle(entry?.destination?.route, entry?.arguments?.getString("genre")),
                    onBack = if (current == null) ({ navController.navigateUp() }) else null,
                    profileSelected = section == TopDestination.Profile,
                    onOpenProfile = { openTab(TopDestination.Profile) },
                )
            } else {
                SnHeader(current = section, onNavigate = openTab)
            }
            NavHost(navController = navController, startDestination = Routes.HOME, modifier = Modifier.weight(1f)) {
                val openTitle = { kind: String, id: String -> navController.navigate(Routes.details(kind, id)) }
                val watch = { details: MetaDetails, episode: Episode? ->
                    PlaybackSession.select(PlaybackSession.Selection(details, episode))
                    navController.navigate(Routes.SOURCES) { launchSingleTop = true }
                }
                composable(Routes.HOME) {
                    HomeScreen(
                        onOpenTitle = { openTitle(it.kind, it.id) },
                        onOpenProgress = { openTitle(it.kind, it.id) },
                        onOpenAddons = { navController.navigate(Routes.ADDONS) { launchSingleTop = true } },
                    )
                }
                composable(Routes.SERIES) { ExploreScreen("series", onOpenTitle = { openTitle(it.kind, it.id) }) }
                composable(Routes.MOVIES) { ExploreScreen("movie", onOpenTitle = { openTitle(it.kind, it.id) }) }
                composable(Routes.SEARCH) {
                    SearchScreen(onOpenTitle = { openTitle(it.kind, it.id) }, focusRequests = searchFocusRequests)
                }
                composable(
                    Routes.GENRE,
                    arguments =
                        listOf(
                            navArgument("kind") { type = NavType.StringType },
                            navArgument("genre") { type = NavType.StringType },
                        ),
                ) { backStack ->
                    ExploreScreen(
                        kind = backStack.arguments?.getString("kind").orEmpty(),
                        initialGenre = backStack.arguments?.getString("genre"),
                        onOpenTitle = { openTitle(it.kind, it.id) },
                    )
                }
                composable(Routes.PERSON, arguments = listOf(navArgument("name") { type = NavType.StringType })) {
                    SearchScreen(
                        initialQuery = it.arguments?.getString("name").orEmpty(),
                        onOpenTitle = { item -> openTitle(item.kind, item.id) },
                    )
                }
                composable(Routes.LIBRARY) {
                    LibraryScreen(onOpenTitle = openTitle, onExplore = { openTab(TopDestination.Series) })
                }
                composable(Routes.PROFILE) {
                    ProfileScreen(
                        onOpenAddons = { navController.navigate(Routes.ADDONS) { launchSingleTop = true } },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                    )
                }
                composable(Routes.ADDONS) { AddonsScreen() }
                composable(Routes.SETTINGS) { SettingsScreen() }
                composable(
                    Routes.DETAILS,
                    arguments =
                        listOf(
                            navArgument("kind") { type = NavType.StringType },
                            navArgument("id") { type = NavType.StringType },
                        ),
                ) { backStack ->
                    DetailsScreen(
                        kind = backStack.arguments?.getString("kind").orEmpty(),
                        id = backStack.arguments?.getString("id").orEmpty(),
                        onOpenTitle = { openTitle(it.kind, it.id) },
                        onOpenGenre = { kind, genre -> navController.navigate(Routes.genre(kind, genre)) },
                        onSearchPerson = { navController.navigate(Routes.person(it)) },
                        onPlay = watch,
                    )
                }
                composable(Routes.SOURCES) {
                    SourcesScreen(
                        // The picker gives way to the player, so Back from the player returns
                        // to the title rather than to the list.
                        onPlay = {
                            navController.navigate(
                                Routes.PLAYER,
                            ) { popUpTo(Routes.SOURCES) { inclusive = true } }
                        },
                        onGone = { navController.popBackStack() },
                    )
                }
                composable(Routes.PLAYER) {
                    PlayerScreen(
                        onBack = { navController.popBackStack() },
                        onChangeSource = {
                            navController.navigate(Routes.SOURCES) { popUpTo(Routes.PLAYER) { inclusive = true } }
                        },
                        onOpenEpisode = { episode ->
                            val current = PlaybackSession.selection ?: return@PlayerScreen
                            PlaybackSession.select(current.copy(episode = episode))
                            navController.navigate(Routes.SOURCES) { popUpTo(Routes.PLAYER) { inclusive = true } }
                        },
                    )
                }
            }
            if (phone && !keyboardOpen && !immersive) {
                SnTabBar(current = section, onNavigate = openTab)
            }
        }
    }
}

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
        Routes.PERSON -> stringResource(R.string.nav_search)
        Routes.SERIES -> stringResource(R.string.nav_series)
        Routes.MOVIES -> stringResource(R.string.nav_movies)
        Routes.SEARCH -> stringResource(R.string.nav_search)
        Routes.LIBRARY -> stringResource(R.string.nav_my_list)
        Routes.PROFILE -> stringResource(R.string.profile_title)
        Routes.ADDONS -> stringResource(R.string.nav_addons)
        Routes.DETAILS -> ""
        Routes.SOURCES -> stringResource(R.string.sources_title)
        Routes.SETTINGS -> stringResource(R.string.settings_title)
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
