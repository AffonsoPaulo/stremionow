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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.affonsopaulo.stremionow.core.AddonInfo
import io.github.affonsopaulo.stremionow.core.AddonPreview
import io.github.affonsopaulo.stremionow.core.CatalogAddon
import io.github.affonsopaulo.stremionow.core.CoreException
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.ui.components.SnButton
import io.github.affonsopaulo.stremionow.ui.components.SnChipRow
import io.github.affonsopaulo.stremionow.ui.components.SnIconButton
import io.github.affonsopaulo.stremionow.ui.components.SnLoadError
import io.github.affonsopaulo.stremionow.ui.components.SnLoading
import io.github.affonsopaulo.stremionow.ui.components.SnMessage
import io.github.affonsopaulo.stremionow.ui.components.SnPageTitle
import io.github.affonsopaulo.stremionow.ui.components.SnTabs
import io.github.affonsopaulo.stremionow.ui.components.SnTextField
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.io
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.platform.LocalPlatformContext
import io.github.affonsopaulo.stremionow.ui.platform.logWarning
import io.github.affonsopaulo.stremionow.ui.theme.FormFactor
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The two parts of the addons screen. */
enum class AddonsSection { Installed, Discover }

/** Why adding an addon from its link failed. */
enum class LinkError {
    /** Not an addon link, or what it points to is not a manifest. */
    Invalid,

    /** The addon could not be reached. */
    Unreachable,

    /** The addon is on the local network and the user declined access to it. */
    LocalNetworkDenied,
}

/** The "Add addon" dialog: a link to check, then the addon it points to, to confirm. */
data class LinkFlow(
    val url: String = "",
    val checking: Boolean = false,
    val error: LinkError? = null,
    val preview: AddonPreview? = null,
    val installing: Boolean = false,
    val installFailed: Boolean = false,
)

/** State of the addons screen. */
data class AddonsState(
    val installed: List<AddonInfo> = emptyList(),
    val installedLoaded: Boolean = false,
    val section: AddonsSection = AddonsSection.Installed,
    /** Null shows every type. */
    val type: AddonType? = null,
    val query: String = "",
    val catalog: List<CatalogAddon> = emptyList(),
    val catalogLoading: Boolean = false,
    val catalogFailed: Boolean = false,
    /** Manifest URLs of Discover addons being installed, and of those that failed to. */
    val installing: Set<String> = emptySet(),
    val installFailed: Set<String> = emptySet(),
    /** The "Add addon" dialog, when open. */
    val link: LinkFlow? = null,
) {
    val installedIds: Set<String> get() = installed.mapTo(HashSet()) { it.id }
}

class AddonsViewModel(
    private val core: StremioCore,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AddonsState())
    val state = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun select(section: AddonsSection) {
        mutableState.update { it.copy(section = section) }
        val current = state.value
        if (section == AddonsSection.Discover && current.catalog.isEmpty() && !current.catalogLoading) loadCatalog()
    }

    fun setType(type: AddonType?) = mutableState.update { it.copy(type = type) }

    fun setQuery(query: String) = mutableState.update { it.copy(query = query.take(100)) }

    fun clearFilters() = mutableState.update { it.copy(type = null, query = "") }

    fun loadCatalog() {
        mutableState.update { it.copy(catalogLoading = true, catalogFailed = false) }
        viewModelScope.launch {
            val result =
                runCatching { core.discoverAddons() }
                    .onFailure { logWarning("Could not load the addon lists", it) }
            mutableState.update {
                it.copy(
                    catalog = result.getOrDefault(it.catalog),
                    catalogLoading = false,
                    catalogFailed = result.isFailure,
                )
            }
        }
    }

    /** Installs an addon from Discover, after the user confirmed it. */
    fun installFromCatalog(addon: AddonInfo) {
        val url = addon.manifestUrl
        if (url in state.value.installing) return
        mutableState.update { it.copy(installing = it.installing + url, installFailed = it.installFailed - url) }
        viewModelScope.launch {
            val result =
                runCatching { core.installAddon(url) }
                    .onFailure { logWarning("Could not install an addon from Discover", it) }
            refreshNow()
            mutableState.update {
                it.copy(
                    installing = it.installing - url,
                    installFailed = if (result.isFailure) it.installFailed + url else it.installFailed,
                )
            }
        }
    }

    fun openLink(url: String = "") = mutableState.update { it.copy(link = LinkFlow(url = url)) }

    fun closeLink() = mutableState.update { it.copy(link = null) }

    fun setLinkUrl(url: String) =
        mutableState.update { state ->
            state.copy(link = state.link?.copy(url = url.take(4096), error = null))
        }

    /** Back from the confirmation to the link. */
    fun editLink() = mutableState.update { it.copy(link = it.link?.copy(preview = null, installFailed = false)) }

    fun localNetworkDenied() =
        mutableState.update { it.copy(link = it.link?.copy(error = LinkError.LocalNetworkDenied)) }

    /** Downloads the manifest behind the link, for the user to confirm. */
    fun checkLink() {
        val link = state.value.link ?: return
        val url = link.url.trim()
        if (url.isEmpty() || link.checking) return
        mutableState.update { it.copy(link = it.link?.copy(checking = true, error = null)) }
        viewModelScope.launch {
            val result =
                runCatching { core.previewAddon(url) }
                    .onFailure { logWarning("Could not read an addon link", it) }
            mutableState.update { state ->
                state.copy(
                    link =
                        state.link?.copy(
                            checking = false,
                            preview = result.getOrNull(),
                            error =
                                result.exceptionOrNull()?.let { error ->
                                    when (error) {
                                        is CoreException.InvalidAddon -> LinkError.Invalid
                                        else -> LinkError.Unreachable
                                    }
                                },
                        ),
                )
            }
        }
    }

    /** Installs the addon the user confirmed in the dialog, then shows it in the installed list. */
    fun confirmLink() {
        val link = state.value.link ?: return
        val preview = link.preview ?: return
        if (link.installing) return
        mutableState.update { it.copy(link = it.link?.copy(installing = true, installFailed = false)) }
        viewModelScope.launch {
            val result =
                runCatching { core.installAddon(preview.addon.manifestUrl) }
                    .onFailure { logWarning("Could not install an addon", it) }
            refreshNow()
            mutableState.update { state ->
                if (result.isSuccess) {
                    state.copy(link = null, section = AddonsSection.Installed, type = null, query = "")
                } else {
                    state.copy(link = state.link?.copy(installing = false, installFailed = true))
                }
            }
        }
    }

    fun setEnabled(
        addon: AddonInfo,
        enabled: Boolean,
    ) = mutate { core.setAddonEnabled(addon.manifestUrl, enabled) }

    fun move(
        addon: AddonInfo,
        index: Int,
    ) = mutate { core.moveAddon(addon.manifestUrl, index.coerceAtLeast(0).toUInt()) }

    fun remove(addon: AddonInfo) = mutate { core.removeAddon(addon.manifestUrl) }

    private fun mutate(change: () -> Unit) {
        viewModelScope.launch {
            runCatching { io(change) }.onFailure { logWarning("Could not change the addons", it) }
            refreshNow()
        }
    }

    private fun refresh() {
        viewModelScope.launch { refreshNow() }
    }

    private suspend fun refreshNow() {
        val addons = runCatching { io { core.addons() } }.getOrNull()
        mutableState.update { it.copy(installed = addons ?: it.installed, installedLoaded = true) }
    }
}

@Composable
fun AddonsScreen() {
    val context = LocalPlatformContext.current
    val viewModel = viewModel { AddonsViewModel(AppGraph.core(context)) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val phone = SnLayout.formFactor == FormFactor.Phone

    // Dialogs live here, above the list, so they survive the list recomposing.
    var confirmInstall by remember { mutableStateOf<AddonInfo?>(null) }
    var sharing by remember { mutableStateOf<AddonInfo?>(null) }
    var configuring by remember { mutableStateOf<AddonInfo?>(null) }

    val installedIds = state.installedIds
    val shown: List<AddonInfo> =
        when (state.section) {
            AddonsSection.Installed -> state.installed
            AddonsSection.Discover -> state.catalog.map { it.addon }
        }
    val options = remember(shown) { typeOptions(shown.map { it.types }) }
    // A type picked in one section may not exist in the other; then everything shows.
    val type = state.type?.takeIf { it in options }
    val matches = { addon: AddonInfo ->
        (type == null || type in addonTypes(addon.types)) &&
            matchesQuery(state.query, addon.name, addon.description, addon.id)
    }
    val filtering = type != null || state.query.isNotBlank()

    val gridState = rememberLazyGridState()
    // After a removal, the focus moves to the Remove button of the card that takes its place,
    // instead of falling back to the top of the screen.
    val removeFocus = remember { mutableMapOf<String, FocusRequester>() }
    var focusAfterRemoval by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusAfterRemoval, state.installed) {
        val url = focusAfterRemoval ?: return@LaunchedEffect
        if (state.installed.none { it.manifestUrl == url }) return@LaunchedEffect
        withFrameNanos { }
        runCatching { removeFocus[url]?.requestFocus() }
        focusAfterRemoval = null
    }
    LaunchedEffect(state.section) { gridState.scrollToItem(0) }
    LazyVerticalGrid(
        state = gridState,
        columns =
            if (state.section == AddonsSection.Installed) {
                // One column: the order is the priority, and reads top to bottom.
                GridCells.Fixed(1)
            } else {
                GridCells.Adaptive(SnLayout.listCellMin)
            },
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                start = SnLayout.gutter,
                end = SnLayout.gutter,
                top = SnSpacing.space2,
                bottom = SnSpacing.space8,
            ),
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space3),
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
            Header(
                state = state,
                phone = phone,
                typeOptions = options,
                type = type,
                onSelect = viewModel::select,
                onAdd = { viewModel.openLink() },
                onType = viewModel::setType,
                onQuery = viewModel::setQuery,
            )
        }
        when (state.section) {
            AddonsSection.Installed -> {
                val visible = state.installed.filter(matches)
                when {
                    !state.installedLoaded -> {
                        Unit
                    }

                    state.installed.isEmpty() -> {
                        item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                            SnMessage(
                                title = stringResource(Res.string.addons_empty_title),
                                body = stringResource(Res.string.addons_empty_body),
                                actionText = stringResource(Res.string.addons_open_discover),
                                onAction = { viewModel.select(AddonsSection.Discover) },
                            )
                        }
                    }

                    visible.isEmpty() -> {
                        item(key = "no-match", span = { GridItemSpan(maxLineSpan) }) {
                            NoMatch(onClear = viewModel::clearFilters)
                        }
                    }

                    else -> {
                        if (filtering) {
                            item(key = "reorder-hint", span = { GridItemSpan(maxLineSpan) }) {
                                BasicText(
                                    stringResource(Res.string.addons_reorder_hint),
                                    style = SnTypography.caption.copy(color = SnColors.fgMuted),
                                )
                            }
                        }
                        itemsIndexed(visible, key = {
                            _,
                            addon,
                            ->
                            "installed/${addon.manifestUrl}"
                        }) { shownAt, addon ->
                            val index = state.installed.indexOf(addon)
                            val removeRequester =
                                remember(addon.manifestUrl) {
                                    removeFocus.getOrPut(addon.manifestUrl) { FocusRequester() }
                                }
                            InstalledAddonCard(
                                addon = addon,
                                removeFocus = removeRequester,
                                position = index,
                                // Reordering a filtered list would move addons past hidden ones.
                                canReorder = !filtering,
                                canMoveUp = index > 0,
                                canMoveDown = index < state.installed.lastIndex,
                                onlyTitleMetadata = isOnlyTitleMetadata(addon, state.installed),
                                onToggle = { viewModel.setEnabled(addon, !addon.enabled) },
                                onMoveUp = { viewModel.move(addon, index - 1) },
                                onMoveDown = { viewModel.move(addon, index + 1) },
                                onRemove = {
                                    val next = visible.getOrNull(shownAt + 1) ?: visible.getOrNull(shownAt - 1)
                                    focusAfterRemoval = next?.manifestUrl
                                    viewModel.remove(addon)
                                },
                                onShare = { sharing = addon },
                                onConfigure = { configuring = addon },
                            )
                        }
                    }
                }
            }

            AddonsSection.Discover -> {
                val visible = state.catalog.filter { matches(it.addon) }
                when {
                    state.catalog.isEmpty() && state.catalogFailed -> {
                        item(key = "catalog-error", span = { GridItemSpan(maxLineSpan) }) {
                            SnLoadError(onRetry = viewModel::loadCatalog)
                        }
                    }

                    state.catalog.isEmpty() -> {
                        item(key = "catalog-loading", span = { GridItemSpan(maxLineSpan) }) {
                            SnLoading(Modifier.height(240.dp))
                        }
                    }

                    visible.isEmpty() -> {
                        item(key = "no-match", span = { GridItemSpan(maxLineSpan) }) {
                            NoMatch(onClear = viewModel::clearFilters)
                        }
                    }

                    else -> {
                        items(visible, key = { "discover/${it.addon.manifestUrl}" }) { entry ->
                            val url = entry.addon.manifestUrl
                            DiscoverAddonCard(
                                entry = entry,
                                installed = entry.addon.id in installedIds,
                                installing = url in state.installing,
                                failed = url in state.installFailed,
                                onInstall = { confirmInstall = entry.addon },
                                onConfigure = { configuring = entry.addon },
                            )
                        }
                    }
                }
            }
        }
    }

    state.link?.let { link ->
        AddLinkDialog(
            link = link,
            onUrlChange = viewModel::setLinkUrl,
            onCheck = viewModel::checkLink,
            onLocalNetworkDenied = viewModel::localNetworkDenied,
            onEdit = viewModel::editLink,
            onConfirm = viewModel::confirmLink,
            onDismiss = viewModel::closeLink,
        )
    }
    confirmInstall?.let { addon ->
        InstallConfirmDialog(
            addon = addon,
            replaces = null,
            installing = false,
            failed = false,
            onConfirm = {
                confirmInstall = null
                viewModel.installFromCatalog(addon)
            },
            onDismiss = { confirmInstall = null },
        )
    }
    sharing?.let { addon -> ShareAddonDialog(addon, onDismiss = { sharing = null }) }
    configuring?.let { addon ->
        ConfigureAddonDialog(
            addon = addon,
            onAddLink = {
                configuring = null
                viewModel.openLink()
            },
            onDismiss = { configuring = null },
        )
    }
}

/** Whether [addon] is the only enabled addon that gives movies and series their information. */
internal fun isOnlyTitleMetadata(
    addon: AddonInfo,
    installed: List<AddonInfo>,
): Boolean =
    addon.providesTitleMetadata &&
        addon.enabled &&
        installed.none { it.manifestUrl != addon.manifestUrl && it.enabled && it.providesTitleMetadata }

@Composable
private fun Header(
    state: AddonsState,
    phone: Boolean,
    typeOptions: List<AddonType>,
    type: AddonType?,
    onSelect: (AddonsSection) -> Unit,
    onAdd: () -> Unit,
    onType: (AddonType?) -> Unit,
    onQuery: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = SnSpacing.space1),
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space4),
    ) {
        SnPageTitle(stringResource(Res.string.nav_addons))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space4),
        ) {
            SnTabs(
                tabs =
                    listOf(
                        stringResource(Res.string.addons_tab_installed),
                        stringResource(Res.string.addons_tab_discover),
                    ),
                selectedIndex = state.section.ordinal,
                onSelect = { onSelect(AddonsSection.entries[it]) },
                modifier = Modifier.weight(1f),
            )
            val addLabel = stringResource(Res.string.addons_add)
            if (phone) {
                // The phone's row is narrow; the round "+" keeps both tabs in view.
                SnIconButton(
                    SnIcons.Plus,
                    addLabel,
                    onAdd,
                    tint = SnColors.onInverse,
                    background = SnColors.inverse,
                )
            } else {
                SnButton(text = addLabel, onClick = onAdd, icon = SnIcons.Plus)
            }
        }
        BasicText(
            stringResource(
                if (state.section ==
                    AddonsSection.Installed
                ) {
                    Res.string.addons_intro
                } else {
                    Res.string.addons_discover_intro
                },
            ),
            style = SnTypography.body.copy(color = SnColors.fgMuted),
        )
        FilterBar(
            phone = phone,
            options = typeOptions,
            type = type,
            query = state.query,
            onType = onType,
            onQuery = onQuery,
        )
    }
}

/**
 * Type chips and search. Large screens and TV show both in one row; a phone shows the chips
 * with a search button that opens the field above them.
 */
@Composable
private fun FilterBar(
    phone: Boolean,
    options: List<AddonType>,
    type: AddonType?,
    query: String,
    onType: (AddonType?) -> Unit,
    onQuery: (String) -> Unit,
) {
    val searchLabel = stringResource(Res.string.addons_search)
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val chips: @Composable (Modifier) -> Unit = { modifier ->
        if (options.isNotEmpty()) {
            val all = stringResource(Res.string.addons_filter_all)
            SnChipRow(
                options = listOf<AddonType?>(null) + options,
                selected = type,
                label = { it?.let { kind -> stringResource(kind.label()) } ?: all },
                onSelect = onType,
                modifier = modifier,
            )
        } else {
            Box(modifier)
        }
    }
    val clear: @Composable () -> Unit = {
        if (query.isNotEmpty()) {
            SnIconButton(SnIcons.Close, stringResource(Res.string.addons_search_clear), {
                onQuery("")
                // The button goes away with the text; the field keeps the focus instead.
                runCatching { searchFocus.requestFocus() }
            }, tint = SnColors.fgSecondary)
        }
    }
    if (!phone) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space4),
        ) {
            chips(Modifier.weight(1f))
            SnTextField(
                value = query,
                onValueChange = onQuery,
                label = searchLabel,
                hint = searchLabel,
                leadingIcon = SnIcons.Search,
                imeAction = ImeAction.Search,
                focusRequester = searchFocus,
                modifier = Modifier.width(320.dp),
                trailing = clear,
            )
        }
        return
    }
    if (searchOpen || query.isNotEmpty()) {
        LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space2),
        ) {
            SnTextField(
                value = query,
                onValueChange = onQuery,
                label = searchLabel,
                hint = searchLabel,
                leadingIcon = SnIcons.Search,
                imeAction = ImeAction.Search,
                focusRequester = searchFocus,
                modifier = Modifier.weight(1f),
            )
            SnIconButton(
                SnIcons.Close,
                stringResource(Res.string.addons_search_close),
                {
                    onQuery("")
                    searchOpen = false
                },
                tint = SnColors.fgSecondary,
            )
        }
        chips(Modifier.fillMaxWidth())
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space2),
        ) {
            chips(Modifier.weight(1f))
            SnIconButton(SnIcons.Search, searchLabel, { searchOpen = true }, tint = SnColors.fgSecondary)
        }
    }
}

@Composable
private fun NoMatch(onClear: () -> Unit) {
    SnMessage(
        title = stringResource(Res.string.addons_no_match_title),
        body = stringResource(Res.string.addons_no_match_body),
        actionText = stringResource(Res.string.addons_clear_filters),
        onAction = onClear,
    )
}
