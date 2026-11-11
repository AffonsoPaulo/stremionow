// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import io.github.affonsopaulo.stremionow.core.CatalogRow
import io.github.affonsopaulo.stremionow.core.CoreException
import io.github.affonsopaulo.stremionow.core.MetaItem
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.core.WatchProgress
import io.github.affonsopaulo.stremionow.ui.components.SnArtwork
import io.github.affonsopaulo.stremionow.ui.components.SnButton
import io.github.affonsopaulo.stremionow.ui.components.SnEpisodeCard
import io.github.affonsopaulo.stremionow.ui.components.SnLoadError
import io.github.affonsopaulo.stremionow.ui.components.SnLoading
import io.github.affonsopaulo.stremionow.ui.components.SnMessage
import io.github.affonsopaulo.stremionow.ui.components.SnMinimalScroll
import io.github.affonsopaulo.stremionow.ui.components.SnPosterCard
import io.github.affonsopaulo.stremionow.ui.components.SnPosterSection
import io.github.affonsopaulo.stremionow.ui.components.SnSectionHeader
import io.github.affonsopaulo.stremionow.ui.components.initialFocus
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.Loadable
import io.github.affonsopaulo.stremionow.ui.data.SecureStore
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

class HomeViewModel(
    private val core: StremioCore,
    private val onSessionExpired: () -> Unit,
) : ViewModel() {
    private val mutableRows = MutableStateFlow<Loadable<List<CatalogRow>>>(Loadable.Loading)
    val rows = mutableRows.asStateFlow()
    private val mutableContinueWatching = MutableStateFlow<List<WatchProgress>>(emptyList())

    /** Titles in progress; local, so it shows even offline. */
    val continueWatching = mutableContinueWatching.asStateFlow()

    fun refreshContinueWatching() {
        viewModelScope.launch {
            mutableContinueWatching.value = runCatching { io { core.continueWatching() } }.getOrDefault(emptyList())
        }
    }

    init {
        load()
    }

    fun load() {
        mutableRows.value = Loadable.Loading
        viewModelScope.launch {
            // Installing the defaults can fail offline; the cached home may still load.
            runCatching { core.ensureDefaultAddons() }
                .onFailure { logWarning("Could not install the default addons", it) }
            // Signed in: bring account changes from other devices first. An expired session
            // signs out quietly; local data stays and the app keeps working.
            runCatching { core.sync() }.onFailure { error ->
                if (error is CoreException.SignIn) onSessionExpired()
            }
            mutableRows.value =
                runCatching { core.home() }
                    .onFailure { logWarning("Could not load the home screen", it) }
                    .fold({ Loadable.Ready(it) }, { Loadable.Failed(it) })
        }
    }
}

private const val TAG = "StremioNow"

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
                SecureStore.clear(context, SecureStore.STREMIO_AUTH_KEY)
                AppGraph.scope.launch { runCatching { AppGraph.core(context).signOut() } }
            }
        }
    val state by viewModel.rows.collectAsStateWithLifecycle()
    val continueWatching by viewModel.continueWatching.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshContinueWatching() }

    when (val rows = state) {
        Loadable.Loading -> {
            SnLoading()
        }

        is Loadable.Failed -> {
            SnLoadError(onRetry = viewModel::load)
        }

        is Loadable.Ready -> {
            if (rows.value.isEmpty()) {
                SnMessage(
                    title = stringResource(Res.string.home_empty_title),
                    body = stringResource(Res.string.home_empty_body),
                    actionText = stringResource(Res.string.action_open_addons),
                    onAction = onOpenAddons,
                )
            } else {
                HomeContent(rows.value, continueWatching, onOpenTitle, onOpenProgress)
            }
        }
    }
}

@Composable
private fun HomeContent(
    rows: List<CatalogRow>,
    continueWatching: List<WatchProgress>,
    onOpenTitle: (MetaItem) -> Unit,
    onOpenProgress: (WatchProgress) -> Unit,
) {
    val sharedNames =
        remember(rows) {
            rows
                .groupingBy { it.catalog.name }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        }
    val featured = rows.firstNotNullOfOrNull { row -> row.items.firstOrNull { it.background != null } }
    SnMinimalScroll {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = SnSpacing.space16),
            verticalArrangement = Arrangement.spacedBy(SnSpacing.space10),
        ) {
            if (featured != null) {
                item(key = "hero") { Hero(featured, onOpenTitle) }
            }
            if (continueWatching.isNotEmpty()) {
                item(key = "continue") { ContinueWatchingRow(continueWatching, onOpenProgress) }
            }
            items(rows, key = { "${it.catalog.addonManifestUrl}/${it.catalog.kind}/${it.catalog.catalogId}" }) { row ->
                // Cinemeta names its movie and series catalogs alike ("Popular"); add the type then.
                val title =
                    if (row.catalog.name in
                        sharedNames
                    ) {
                        "${row.catalog.name} · ${kindLabel(row.catalog.kind)}"
                    } else {
                        row.catalog.name
                    }
                CatalogRowSection(title, row, onOpenTitle)
            }
        }
    }
}

@Composable
private fun Hero(
    item: MetaItem,
    onOpenTitle: (MetaItem) -> Unit,
) {
    val shape = RoundedCornerShape(SnRadius.radiusXl)
    // On TV the whole hero must fit under the header, logo included, with the first row peeking.
    val height =
        when (SnLayout.formFactor) {
            FormFactor.Phone -> 360.dp
            FormFactor.Large -> 470.dp
            FormFactor.Tv -> (screenHeightDp() * 0.62f).dp
        }
    Box(
        Modifier
            .padding(horizontal = SnLayout.gutter)
            .fillMaxWidth()
            // At least this tall, and taller when the logo and synopsis need it.
            .heightIn(min = height)
            .clip(shape),
    ) {
        SnArtwork(url = item.background, fallbackTitle = "", modifier = Modifier.matchParentSize())
        Box(
            Modifier.matchParentSize().background(
                Brush.horizontalGradient(
                    0f to SnColors.bg.copy(alpha = 0.95f),
                    0.4f to SnColors.bg.copy(alpha = 0.7f),
                    0.75f to SnColors.bg.copy(alpha = 0f),
                ),
            ),
        )
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0.6f to SnColors.bg.copy(alpha = 0f),
                    1f to SnColors.bg.copy(alpha = 0.6f),
                ),
            ),
        )
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .widthIn(max = 640.dp)
                    .padding(SnSpacing.space8),
            verticalArrangement = Arrangement.spacedBy(SnSpacing.space4),
        ) {
            TitleLogo(item)
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
                modifier = Modifier.initialFocus(),
            )
        }
    }
}

/** The title's logo image when the addon has one, else its name in the display face. */
@Composable
fun TitleLogo(
    item: MetaItem,
    modifier: Modifier = Modifier,
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
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CatalogRowSection(
    title: String,
    row: CatalogRow,
    onOpenTitle: (MetaItem) -> Unit,
) {
    SnPosterSection(title, row.items, onOpenTitle, subtitle = { it.cardSubtitle() })
}

/** Titles in progress, with how far along each one is and the time left. */
@Composable
private fun ContinueWatchingRow(
    items: List<WatchProgress>,
    onOpen: (WatchProgress) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space3)) {
        SnSectionHeader(stringResource(Res.string.home_continue_watching))
        LazyRow(
            contentPadding = PaddingValues(horizontal = SnLayout.gutter, vertical = SnSpacing.space2),
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space4),
        ) {
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
