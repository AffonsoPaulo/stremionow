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
import androidx.compose.runtime.getValue
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
import io.github.affonsopaulo.stremionow.ui.screens.AddonsScreen
import io.github.affonsopaulo.stremionow.ui.screens.DetailsScreen
import io.github.affonsopaulo.stremionow.ui.screens.ExploreScreen
import io.github.affonsopaulo.stremionow.ui.screens.HomeScreen
import io.github.affonsopaulo.stremionow.ui.screens.LibraryScreen
import io.github.affonsopaulo.stremionow.ui.screens.ProfileScreen
import io.github.affonsopaulo.stremionow.ui.screens.SearchScreen
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

    fun details(
        kind: String,
        id: String,
    ) = "details/${Uri.encode(kind)}/${Uri.encode(id)}"
}

/**
 * Root of the interface, shared by the phone and TV apps. [onPlay] opens playback for a
 * title or episode; it is wired by each app.
 */
@Composable
fun StremioNowApp(
    isTv: Boolean,
    onPlay: ((MetaDetails, Episode?) -> Unit)? = null,
) {
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
        // Addons is a page of the profile, so the avatar stays highlighted there.
        val section =
            current ?: TopDestination.Profile.takeIf { entry?.destination?.route == Routes.ADDONS }
        val phone = SnLayout.formFactor == FormFactor.Phone
        // While typing, the keyboard takes the tab bar's place instead of stacking above it.
        val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        Column(
            Modifier
                .fillMaxSize()
                .background(SnColors.bg)
                // On phones the tab bar pads itself so its background reaches the screen edge.
                .windowInsetsPadding(
                    if (phone) {
                        WindowInsets.safeDrawing
                            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                            .union(WindowInsets.ime)
                    } else {
                        WindowInsets.safeDrawing
                    },
                ),
        ) {
            if (phone) {
                SnPhoneHeader(
                    title = phoneTitle(entry?.destination?.route),
                    onBack = if (current == null) ({ navController.navigateUp() }) else null,
                    profileSelected = section == TopDestination.Profile,
                    onOpenProfile = { navController.openTop(TopDestination.Profile) },
                )
            } else {
                SnHeader(current = section, onNavigate = { navController.openTop(it) })
            }
            NavHost(navController = navController, startDestination = Routes.HOME, modifier = Modifier.weight(1f)) {
                val openTitle = { kind: String, id: String -> navController.navigate(Routes.details(kind, id)) }
                composable(Routes.HOME) {
                    HomeScreen(
                        onOpenTitle = { openTitle(it.kind, it.id) },
                        onOpenAddons = { navController.navigate(Routes.ADDONS) { launchSingleTop = true } },
                    )
                }
                composable(Routes.SERIES) { ExploreScreen("series", onOpenTitle = { openTitle(it.kind, it.id) }) }
                composable(Routes.MOVIES) { ExploreScreen("movie", onOpenTitle = { openTitle(it.kind, it.id) }) }
                composable(Routes.SEARCH) { SearchScreen(onOpenTitle = { openTitle(it.kind, it.id) }) }
                composable(Routes.LIBRARY) {
                    LibraryScreen(onOpenTitle = openTitle, onExplore = { navController.openTop(TopDestination.Series) })
                }
                composable(Routes.PROFILE) {
                    ProfileScreen(onOpenAddons = { navController.navigate(Routes.ADDONS) { launchSingleTop = true } })
                }
                composable(Routes.ADDONS) { AddonsScreen() }
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
                        onPlay = onPlay,
                    )
                }
            }
            if (phone && !keyboardOpen) {
                SnTabBar(current = current, onNavigate = { navController.openTop(it) })
            }
        }
    }
}

/**
 * Title for the phone top bar: null shows the wordmark (Home), an empty string shows nothing
 * (the details page has its own title art).
 */
@Composable
private fun phoneTitle(route: String?): String? =
    when (route) {
        Routes.SERIES -> stringResource(R.string.nav_series)
        Routes.MOVIES -> stringResource(R.string.nav_movies)
        Routes.SEARCH -> stringResource(R.string.nav_search)
        Routes.LIBRARY -> stringResource(R.string.nav_my_list)
        Routes.PROFILE -> stringResource(R.string.profile_title)
        Routes.ADDONS -> stringResource(R.string.nav_addons)
        Routes.DETAILS -> ""
        else -> null
    }

/** Opens a top-level destination, keeping a single copy of each on the back stack. */
private fun NavHostController.openTop(destination: TopDestination) {
    val route =
        when (destination) {
            TopDestination.Search -> Routes.SEARCH
            TopDestination.Home -> Routes.HOME
            TopDestination.Series -> Routes.SERIES
            TopDestination.Movies -> Routes.MOVIES
            TopDestination.MyList -> Routes.LIBRARY
            TopDestination.Profile -> Routes.PROFILE
        }
    navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
