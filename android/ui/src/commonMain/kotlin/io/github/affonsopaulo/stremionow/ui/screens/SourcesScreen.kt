// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.affonsopaulo.stremionow.core.StreamSource
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.ui.components.SnDialog
import io.github.affonsopaulo.stremionow.ui.components.SnLoading
import io.github.affonsopaulo.stremionow.ui.components.SnMessage
import io.github.affonsopaulo.stremionow.ui.components.initialFocus
import io.github.affonsopaulo.stremionow.ui.components.snClickable
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.Connectivity
import io.github.affonsopaulo.stremionow.ui.data.PlaybackSession
import io.github.affonsopaulo.stremionow.ui.data.io
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.pluralStringResource
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.platform.LocalPlatformContext
import io.github.affonsopaulo.stremionow.ui.platform.PlatformContext
import io.github.affonsopaulo.stremionow.ui.platform.openExternalLink
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Sources found so far, and how many addons are still answering. */
data class SourcesState(
    val sources: List<StreamSource> = emptyList(),
    val addonsAsked: Int = 0,
    val addonsAnswered: Int = 0,
    val addonsWithSources: Int = 0,
    /** Every addon has answered (or there were none to ask). */
    val finished: Boolean = false,
) {
    val searching: Boolean get() = !finished
}

class SourcesViewModel(
    private val core: StremioCore,
    private val selection: PlaybackSession.Selection,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SourcesState())
    val state = mutableState.asStateFlow()
    private val merge = Mutex()
    private val mutableWifiOnly = MutableStateFlow(false)

    /** The "Only on Wi-Fi" preference for torrents. */
    val wifiOnly = mutableWifiOnly.asStateFlow()

    init {
        viewModelScope.launch {
            mutableWifiOnly.value = runCatching { io { core.settings().torrentWifiOnly } }.getOrDefault(false)
        }
        viewModelScope.launch {
            val addons =
                runCatching {
                    io {
                        core.streamAddons(
                            selection.kind,
                            selection.videoId,
                        )
                    }
                }.getOrDefault(emptyList())
            mutableState.update { it.copy(addonsAsked = addons.size, finished = addons.isEmpty()) }
            // Ask every addon at once and show each answer as it arrives, ranked with the rest.
            addons.forEach { addon ->
                launch {
                    val found =
                        runCatching { core.streamsFrom(addon.manifestUrl, selection.kind, selection.videoId) }
                            .getOrDefault(emptyList())
                            .filter(StreamSource::isUsable)
                    merge.withLock {
                        val ranked =
                            if (found.isEmpty()) {
                                state.value.sources
                            } else {
                                withContext(Dispatchers.Default) { core.rankStreams(state.value.sources + found) }
                            }
                        mutableState.update {
                            it.copy(
                                sources = ranked,
                                addonsAnswered = it.addonsAnswered + 1,
                                finished = it.addonsAnswered + 1 >= it.addonsAsked,
                                addonsWithSources = it.addonsWithSources + if (found.isEmpty()) 0 else 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Sources the app can use: web addresses and torrents play in the app, links open elsewhere. */
private fun StreamSource.isUsable(): Boolean = url != null || infoHash != null || externalUrl != null || ytId != null

/** Whether the app plays the source itself rather than handing it to another app. */
private val StreamSource.playsInApp: Boolean get() = url != null || infoHash != null

/**
 * "Choose a source": every addon's offers for the chosen video, best first. Sources arrive
 * addon by addon, so the list fills in while slower addons are still answering.
 */
@Composable
fun SourcesScreen(
    onPlay: () -> Unit,
    onGone: () -> Unit,
) {
    val selection = PlaybackSession.selection
    if (selection == null) {
        LaunchedEffect(Unit) { onGone() }
        return
    }
    val context = LocalPlatformContext.current
    val viewModel =
        viewModel(key = "sources-${selection.videoId}") { SourcesViewModel(AppGraph.core(context), selection) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val wifiOnly by viewModel.wifiOnly.collectAsStateWithLifecycle()
    var confirmMobileData by remember { mutableStateOf<StreamSource?>(null) }
    val play = { source: StreamSource ->
        PlaybackSession.source = source
        PlaybackSession.alternatives = state.sources
        onPlay()
    }
    val choose = { source: StreamSource ->
        when {
            !source.playsInApp -> {
                openOutside(context, source)
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
    // Next episode: play the source of the same group as soon as it shows up; if none does,
    // the list stays for the user to choose.
    LaunchedEffect(state.sources, state.finished) {
        val group = PlaybackSession.continueWith ?: return@LaunchedEffect
        val match = state.sources.firstOrNull { it.bingeGroup == group && it.playsInApp }
        if (match != null) {
            PlaybackSession.continueWith = null
            play(match)
        } else if (state.finished) {
            PlaybackSession.continueWith = null
        }
    }
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

    Column(Modifier.fillMaxSize()) {
        Header(selection, state)
        when {
            state.sources.isEmpty() && state.searching -> {
                SnLoading()
            }

            state.sources.isEmpty() -> {
                SnMessage(
                    title = stringResource(Res.string.sources_empty_title),
                    body = stringResource(Res.string.sources_empty_body),
                )
            }

            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = SnLayout.gutter, vertical = SnSpacing.space4),
                    verticalArrangement = Arrangement.spacedBy(SnSpacing.space3),
                ) {
                    itemsIndexed(state.sources, key = {
                        index,
                        source,
                        ->
                        "$index/${source.addonName}/${source.name}"
                    }) { index, source ->
                        SourceRow(
                            source,
                            best = index == 0 && source.playsInApp,
                            onClick = { choose(source) },
                            modifier = if (index == 0) Modifier.initialFocus() else Modifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(
    selection: PlaybackSession.Selection,
    state: SourcesState,
) {
    Column(
        Modifier.padding(horizontal = SnLayout.gutter).widthIn(max = 880.dp),
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space1),
    ) {
        val episode = selection.episode
        val subject =
            if (episode?.season != null && episode.episode != null) {
                val label =
                    stringResource(Res.string.episode_short, episode.season!!.toInt(), episode.episode!!.toInt())
                "${selection.details.item.name} · $label"
            } else {
                selection.details.item.name
            }
        BasicText(
            subject,
            modifier = Modifier.semantics { heading() },
            style = SnTypography.headingMd.copy(color = SnColors.fg),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val status =
            when {
                state.addonsAsked == 0 && !state.searching -> {
                    null
                }

                state.searching -> {
                    stringResource(Res.string.sources_searching)
                }

                else -> {
                    stringResource(
                        Res.string.sources_summary,
                        pluralStringResource(Res.plurals.sources_count, state.sources.size, state.sources.size),
                        pluralStringResource(
                            Res.plurals.sources_addons,
                            state.addonsWithSources,
                            state.addonsWithSources,
                        ),
                    )
                }
            }
        status?.let { BasicText(it, style = SnTypography.body.copy(color = SnColors.fgMuted)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceRow(
    source: StreamSource,
    best: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(SnRadius.radiusLg)
    Column(
        modifier =
            modifier
                .widthIn(max = 880.dp)
                .fillMaxWidth()
                .snClickable(shape, onClick = onClick)
                .background(if (best) SnColors.accentSurface else SnColors.surface, shape)
                .border(1.dp, if (best) SnColors.accentBorder else SnColors.border, shape)
                .padding(SnSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space2),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space2),
        ) {
            BasicText(
                source.addonName,
                modifier = Modifier.weight(1f),
                style = SnTypography.label.copy(color = SnColors.fgSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (best) Badge(stringResource(Res.string.sources_best), accent = true)
            if (!source.playsInApp) Badge(stringResource(Res.string.sources_opens_outside), accent = false)
        }
        BasicText(
            source.name
                .lineSequence()
                .filter(String::isNotBlank)
                .joinToString(" · "),
            style = SnTypography.body.copy(color = SnColors.fg),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (source.badges.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(SnSpacing.space1),
                verticalArrangement = Arrangement.spacedBy(SnSpacing.space1),
            ) {
                source.badges.forEach { Badge(it, accent = false) }
            }
        }
        if (source.description.isNotBlank()) {
            BasicText(
                source.description,
                style = SnTypography.caption.copy(color = SnColors.fgMuted),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Badge(
    text: String,
    accent: Boolean,
) {
    BasicText(
        text,
        modifier =
            Modifier
                .background(
                    if (accent) SnColors.accent else SnColors.surfaceMuted,
                    RoundedCornerShape(SnRadius.radiusSm),
                ).padding(horizontal = SnSpacing.space2, vertical = 2.dp),
        style = SnTypography.badge.copy(color = if (accent) SnColors.onAccent else SnColors.fgSecondary),
        maxLines = 1,
    )
}

/** Opens a link the app does not play itself, such as a streaming service page or YouTube. */
private fun openOutside(
    context: PlatformContext,
    source: StreamSource,
) {
    val link = source.externalUrl ?: source.ytId?.let { "https://www.youtube.com/watch?v=$it" } ?: return
    openExternalLink(context, link)
}
