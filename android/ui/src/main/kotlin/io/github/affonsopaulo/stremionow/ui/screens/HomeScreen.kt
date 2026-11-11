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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import io.github.affonsopaulo.stremionow.core.CatalogRow
import io.github.affonsopaulo.stremionow.core.MetaItem
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.ui.R
import io.github.affonsopaulo.stremionow.ui.components.SnArtwork
import io.github.affonsopaulo.stremionow.ui.components.SnButton
import io.github.affonsopaulo.stremionow.ui.components.SnLoadError
import io.github.affonsopaulo.stremionow.ui.components.SnLoading
import io.github.affonsopaulo.stremionow.ui.components.SnMessage
import io.github.affonsopaulo.stremionow.ui.components.SnPosterCard
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.Loadable
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
) : ViewModel() {
    private val mutableRows = MutableStateFlow<Loadable<List<CatalogRow>>>(Loadable.Loading)
    val rows = mutableRows.asStateFlow()

    init {
        load()
    }

    fun load() {
        mutableRows.value = Loadable.Loading
        viewModelScope.launch {
            // Installing the defaults can fail offline; the cached home may still load.
            runCatching { core.ensureDefaultAddons() }
                .onFailure { android.util.Log.w(TAG, "Could not install the default addons", it) }
            mutableRows.value =
                runCatching { core.home() }
                    .onFailure { android.util.Log.w(TAG, "Could not load the home screen", it) }
                    .fold({ Loadable.Ready(it) }, { Loadable.Failed(it) })
        }
    }
}

private const val TAG = "StremioNow"

@Composable
fun HomeScreen(
    onOpenTitle: (MetaItem) -> Unit,
    onOpenAddons: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel = viewModel { HomeViewModel(AppGraph.core(context)) }
    val state by viewModel.rows.collectAsStateWithLifecycle()

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
                    title = stringResource(R.string.home_empty_title),
                    body = stringResource(R.string.home_empty_body),
                    actionText = stringResource(R.string.action_open_addons),
                    onAction = onOpenAddons,
                )
            } else {
                HomeContent(rows.value, onOpenTitle)
            }
        }
    }
}

@Composable
private fun HomeContent(
    rows: List<CatalogRow>,
    onOpenTitle: (MetaItem) -> Unit,
) {
    val featured = rows.firstNotNullOfOrNull { row -> row.items.firstOrNull { it.background != null } }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = SnSpacing.space16),
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space10),
    ) {
        if (featured != null) {
            item(key = "hero") { Hero(featured, onOpenTitle) }
        }
        items(rows, key = { "${it.catalog.addonManifestUrl}/${it.catalog.kind}/${it.catalog.catalogId}" }) { row ->
            CatalogRowSection(row, onOpenTitle)
        }
    }
}

@Composable
private fun Hero(
    item: MetaItem,
    onOpenTitle: (MetaItem) -> Unit,
) {
    val shape = RoundedCornerShape(SnRadius.radiusXl)
    val height = if (SnLayout.formFactor == FormFactor.Phone) 360.dp else 470.dp
    Box(
        Modifier
            .padding(horizontal = SnLayout.gutter)
            .fillMaxWidth()
            .height(height)
            .clip(shape),
    ) {
        SnArtwork(url = item.background, fallbackTitle = "", modifier = Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to SnColors.bg.copy(alpha = 0.95f),
                    0.4f to SnColors.bg.copy(alpha = 0.7f),
                    0.75f to SnColors.bg.copy(alpha = 0f),
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
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
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SnButton(
                text = stringResource(R.string.action_more_info),
                onClick = { onOpenTitle(item) },
                icon = SnIcons.Info,
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
    row: CatalogRow,
    onOpenTitle: (MetaItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space3)) {
        BasicText(
            text = row.catalog.name,
            modifier = Modifier.padding(horizontal = SnLayout.gutter),
            style = SnTypography.sectionTitle.copy(color = SnColors.fg),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = SnLayout.gutter, vertical = SnSpacing.space2),
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space4),
        ) {
            items(row.items, key = { it.id }) { item ->
                SnPosterCard(
                    title = item.name,
                    poster = item.poster,
                    subtitle = item.cardSubtitle(),
                    onClick = { onOpenTitle(item) },
                )
            }
        }
    }
}
