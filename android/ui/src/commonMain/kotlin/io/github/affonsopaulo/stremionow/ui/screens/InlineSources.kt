// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.affonsopaulo.stremionow.core.StreamSource
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.core.WatchProgress
import io.github.affonsopaulo.stremionow.ui.components.SnFlagText
import io.github.affonsopaulo.stremionow.ui.components.SnIconButton
import io.github.affonsopaulo.stremionow.ui.components.SnLoading
import io.github.affonsopaulo.stremionow.ui.components.SnMessage
import io.github.affonsopaulo.stremionow.ui.components.SnMinimalScroll
import io.github.affonsopaulo.stremionow.ui.components.collectSnInteraction
import io.github.affonsopaulo.stremionow.ui.components.snClickable
import io.github.affonsopaulo.stremionow.ui.components.snStateBackground
import io.github.affonsopaulo.stremionow.ui.components.snStateBorder
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.io
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.pluralStringResource
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.platform.LocalPlatformContext
import io.github.affonsopaulo.stremionow.ui.platform.SnPointerTooltip
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Sources found so far for one video, and how many addons are still answering. */
data class SourcesState(
    /** The video these sources are for, or null before any was asked. */
    val videoId: String? = null,
    val sources: List<StreamSource> = emptyList(),
    val addonsAsked: Int = 0,
    val addonsAnswered: Int = 0,
    /** Every addon has answered (or there were none to ask). */
    val finished: Boolean = false,
    /** Identity of the source last played for this video, to offer it first. */
    val played: String? = null,
) {
    val searching: Boolean get() = !finished

    /** Addons that have not answered yet. */
    val addonsPending: Int get() = (addonsAsked - addonsAnswered).coerceAtLeast(0)
}

/**
 * Asks every stream addon for one video's sources at once, ranking each answer with the rest
 * as it arrives, so the list fills in while slower addons are still answering. It runs on
 * [scope] until [cancel] or the scope ends. It also reads which source was played last time.
 */
class SourcesLoader(
    private val core: StremioCore,
    private val scope: CoroutineScope,
    kind: String,
    val videoId: String,
) {
    private val mutableState = MutableStateFlow(SourcesState(videoId = videoId))
    val state: StateFlow<SourcesState> = mutableState.asStateFlow()

    /** When the sources were asked for, to ask again once their links may have expired. */
    val startedAt: Long = System.currentTimeMillis()
    private val merge = Mutex()
    private val job =
        scope.launch {
            launch {
                val played = runCatching { io { core.playedSource(videoId) } }.getOrNull()
                // A source played meanwhile is newer than the one read.
                if (played != null) mutableState.update { if (it.played == null) it.copy(played = played) else it }
            }
            val addons = runCatching { io { core.streamAddons(kind, videoId) } }.getOrDefault(emptyList())
            mutableState.update { it.copy(addonsAsked = addons.size, finished = addons.isEmpty()) }
            addons.forEach { addon ->
                launch {
                    val found =
                        runCatching { core.streamsFrom(addon.manifestUrl, kind, videoId) }
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
                            )
                        }
                    }
                }
            }
        }

    /**
     * Remembers [source] as the one played for this video, so it is offered first from now
     * on and next time. Only its identity is kept, never its address.
     */
    fun remember(source: StreamSource) {
        val identity = source.identity ?: return
        mutableState.update { it.copy(played = identity) }
        scope.launch { runCatching { io { core.rememberPlayedSource(videoId, identity) } } }
    }

    fun cancel() = job.cancel()
}

/** What the area above the sources says about the video, for series. */
data class SourcesSubject(
    /** "S1 E5 · Title". */
    val label: String,
    val onBack: () -> Unit,
    val backFocus: FocusRequester,
)

/**
 * The sources of a video in place of the episodes: a header with the way back (series), the
 * search, filters and order; then the source played last time, when it is among them; then
 * the rest in a grid of [columns], the recommended one first in the app's order; then how
 * many addons are still answering. [firstFocus] reaches the first source; [progress] is how
 * far into this video the user got, if anywhere.
 */
internal fun LazyListScope.sourcesItems(
    state: SourcesState,
    subject: SourcesSubject?,
    filters: SourceFilters,
    onFilters: (SourceFilters) -> Unit,
    progress: WatchProgress?,
    columns: Int,
    phone: Boolean,
    firstFocus: FocusRequester,
    onChoose: (StreamSource) -> Unit,
) {
    item(key = "sources-header") {
        // The phone's chips are a row of their own, which scrolls without the page header's inset.
        SnMinimalScroll {
            Column(
                Modifier.padding(bottom = SnSpacing.space4),
                verticalArrangement = Arrangement.spacedBy(SnSpacing.space4),
            ) {
                if (subject != null) SubjectRow(subject)
                if (state.sources.isNotEmpty()) SourcesToolbar(state.sources, filters, onFilters, phone)
            }
        }
    }
    when {
        state.sources.isEmpty() && state.searching -> {
            item(key = "sources-loading") { Box(Modifier.fillMaxWidth().height(SourcesMessageHeight)) { SnLoading() } }
        }

        state.sources.isEmpty() -> {
            item(key = "sources-empty") {
                Box(Modifier.fillMaxWidth().heightIn(min = SourcesMessageHeight)) {
                    SnMessage(
                        title = stringResource(Res.string.sources_empty_title),
                        body = stringResource(Res.string.sources_empty_body),
                    )
                }
            }
        }

        else -> {
            val shown = shownSources(state.sources, filters)
            val best = bestSource(state.sources)
            val layout = sourcesLayout(shown, state.played)
            if (shown.isEmpty()) {
                item(key = "sources-no-match") {
                    Box(Modifier.fillMaxWidth().heightIn(min = SourcesMessageHeight)) {
                        SnMessage(
                            title = stringResource(Res.string.sources_no_match_title),
                            body = stringResource(Res.string.sources_no_match_body),
                            actionText = stringResource(Res.string.sources_clear_filters),
                            onAction = { onFilters(SourceFilters(sort = filters.sort)) },
                        )
                    }
                }
            }
            layout.continueWith?.let { source ->
                item(key = "sources-continue-${state.videoId}") {
                    ContinueCard(
                        source = source,
                        best = source === best,
                        progress = progress,
                        onClick = { onChoose(source) },
                        modifier = Modifier.focusRequester(firstFocus),
                    )
                }
            }
            val rows = layout.grid.chunked(columns.coerceAtLeast(1))
            rows.forEachIndexed { rowIndex, row ->
                item(key = "sources-row-${state.videoId}-$rowIndex") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .padding(horizontal = SnLayout.gutter)
                            .padding(bottom = SnSpacing.space3),
                        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
                    ) {
                        row.forEachIndexed { index, source ->
                            val first = layout.continueWith == null && rowIndex == 0 && index == 0
                            SourceCard(
                                source = source,
                                best = source === best,
                                onClick = { onChoose(source) },
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .then(if (first) Modifier.focusRequester(firstFocus) else Modifier),
                            )
                        }
                        // Keeps the last row's cards as wide as the others.
                        repeat(columns - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
    if (state.searching && state.addonsAsked > 0) {
        item(key = "sources-footer") { LoadingFooter(state) }
    }
}

private val SourcesMessageHeight = 240.dp

/** The way back to the episodes, and which episode these sources are for. */
@Composable
private fun SubjectRow(subject: SourcesSubject) {
    Row(
        Modifier.padding(horizontal = SnLayout.gutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
    ) {
        SnIconButton(
            icon = SnIcons.ChevronLeft,
            label = stringResource(Res.string.sources_back_to_episodes),
            onClick = subject.onBack,
            modifier = Modifier.focusRequester(subject.backFocus),
            background = SnColors.surfaceMuted,
        )
        BasicText(
            subject.label,
            modifier = Modifier.weight(1f).semantics { heading() },
            style = SnTypography.sectionTitle.copy(color = SnColors.fg),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "2 addons still loading" over a bar of how many have answered. */
@Composable
private fun LoadingFooter(state: SourcesState) {
    Column(
        Modifier
            .padding(horizontal = SnLayout.gutter)
            .padding(top = SnSpacing.space2)
            .widthIn(max = 360.dp),
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space2),
    ) {
        BasicText(
            pluralStringResource(Res.plurals.sources_addons_loading, state.addonsPending, state.addonsPending),
            style = SnTypography.caption.copy(color = SnColors.fgMuted),
        )
        val share = if (state.addonsAsked == 0) 0f else state.addonsAnswered.toFloat() / state.addonsAsked
        ProgressBar(share)
    }
}

@Composable
private fun ProgressBar(
    share: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(SnRadius.radiusFull))
            .background(SnColors.progressTrack),
    ) {
        Box(
            Modifier
                .fillMaxWidth(share.coerceIn(0f, 1f))
                .height(4.dp)
                .background(SnColors.accent),
        )
    }
}

/**
 * One source in the grid, as tall as every other: its resolution, its name in two lines at
 * most (all of it in a tooltip and for screen readers), one line of what it is, and its addon
 * with how it plays. The recommended one is marked.
 */
@Composable
private fun SourceCard(
    source: StreamSource,
    best: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = remember(source) { sourceTitle(source) }
    val details = remember(source) { sourceDetailsLine(source) }
    CardFrame(best, onClick, modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3)) {
                ResolutionPill(source.quality)
                SourceName(title, maxLines = 2, modifier = Modifier.weight(1f))
                if (best) Badge(stringResource(Res.string.sources_best))
            }
            BasicText(
                details,
                style = SnTypography.caption.copy(color = SnColors.fgSecondary),
                minLines = 1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                Modifier.fillMaxWidth().heightIn(min = ServiceTileSize),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
            ) {
                BasicText(
                    source.addonName,
                    modifier = Modifier.weight(1f),
                    style = SnTypography.caption.copy(color = SnColors.fgMuted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                SourceStatusLabel(source)
            }
        }
    }
}

/**
 * The source played last time for this video, across the whole width under its label: its
 * resolution, name, details and addon, how it plays, and how much of the video is left.
 */
@Composable
private fun ContinueCard(
    source: StreamSource,
    best: Boolean,
    progress: WatchProgress?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = remember(source) { sourceTitle(source) }
    val details = remember(source) { listOf(sourceDetailsLine(source), source.addonName).filter { it.isNotEmpty() } }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = SnLayout.gutter)
            .padding(bottom = SnSpacing.space6),
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space2),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space2),
        ) {
            Image(SnIcons.Play, null, Modifier.size(14.dp), colorFilter = ColorFilter.tint(SnColors.accentText))
            BasicText(
                stringResource(Res.string.sources_continue),
                modifier = Modifier.semantics { heading() },
                style = SnTypography.label.copy(color = SnColors.fg),
            )
            Box(Modifier.weight(1f).height(1.dp).background(SnColors.border))
        }
        CardFrame(best = false, onClick = onClick, modifier = modifier.fillMaxWidth(), border = SnColors.borderStrong) {
            Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space3)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
                ) {
                    ResolutionPill(source.quality)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SnSpacing.space1)) {
                        SourceName(title, maxLines = 1, modifier = Modifier.fillMaxWidth())
                        BasicText(
                            details.joinToString(" · "),
                            style = SnTypography.caption.copy(color = SnColors.fgSecondary),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (best) Badge(stringResource(Res.string.sources_best))
                    SourceStatusLabel(source)
                }
                val share = progress?.fraction()?.takeIf { it > 0f }
                val left = progress?.let(::minutesLeft)
                if (share != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
                    ) {
                        ProgressBar(share, Modifier.weight(1f))
                        if (left != null) {
                            BasicText(
                                stringResource(Res.string.time_left, "$left min"),
                                style = SnTypography.caption.copy(color = SnColors.fgMuted),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A source's card with the shared feedback: it lifts under the pointer, lightens its border,
 * and the recommended one has the accent's colors.
 */
@Composable
private fun CardFrame(
    best: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    border: Color = SnColors.border,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(SnRadius.radiusLg)
    val interaction = remember { MutableInteractionSource() }
    val state = interaction.collectSnInteraction()
    Box(
        modifier =
            modifier
                .snClickable(shape, lift = true, highlight = false, interactionSource = interaction, onClick = onClick)
                .snStateBackground(if (best) SnColors.accentSurface else SnColors.surface, shape, state)
                .snStateBorder(if (best) SnColors.accentBorder else border, shape, state)
                .padding(SnSpacing.space4),
    ) {
        content()
    }
}

/**
 * A source's name, cut to [maxLines] with an ellipsis; it always takes that many lines, so
 * cards line up. All of it shows in a tooltip under the pointer and goes to screen readers.
 */
@Composable
private fun SourceName(
    title: String,
    maxLines: Int,
    modifier: Modifier,
) {
    Box(modifier) {
        SnPointerTooltip(title) {
            SnFlagText(
                title,
                modifier = Modifier.fillMaxWidth(),
                style = SnTypography.body.copy(color = SnColors.fg),
                minLines = maxLines,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The resolution as a pill: 4K and 8K light, the rest muted. Nothing when unknown. */
@Composable
private fun ResolutionPill(quality: String?) {
    if (quality == null) return
    val high = quality == "4K" || quality == "8K"
    BasicText(
        quality,
        modifier =
            Modifier
                .widthIn(min = ResolutionPillWidth)
                .background(if (high) SnColors.fg else SnColors.surfaceMuted, RoundedCornerShape(SnRadius.radiusSm))
                .padding(horizontal = SnSpacing.space2, vertical = 2.dp),
        style =
            SnTypography.badge.copy(
                color = if (high) SnColors.onInverse else SnColors.fgSecondary,
                textAlign = TextAlign.Center,
            ),
        maxLines = 1,
    )
}

private val ResolutionPillWidth = 44.dp

/**
 * How a source plays, at the end of its card: a torrent's seeds; whether a debrid service has
 * the file ready, when the addon says; or, for a link to another app, the service and that it
 * opens outside.
 */
@Composable
private fun SourceStatusLabel(source: StreamSource) {
    when (sourceStatus(source)) {
        SourceStatus.Seeds -> {
            val seeds = source.seeders?.toInt() ?: 0
            val label = pluralStringResource(Res.plurals.sources_seeders, seeds, seeds)
            StatusText(
                pluralStringResource(Res.plurals.sources_seeds, seeds, seeds),
                SnIcons.Peers,
                SnColors.success,
                Modifier.clearAndSetSemantics { contentDescription = label },
            )
        }

        SourceStatus.Cached -> {
            StatusText(stringResource(Res.string.sources_cached), SnIcons.Bolt, SnColors.success)
        }

        SourceStatus.NotCached -> {
            StatusText(stringResource(Res.string.sources_not_cached), null, SnColors.fgMuted)
        }

        SourceStatus.Outside -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SnSpacing.space2),
            ) {
                ServiceTile(source)
                StatusText(stringResource(Res.string.sources_opens_outside), null, SnColors.fgMuted)
            }
        }

        SourceStatus.None -> {}
    }
}

@Composable
private fun StatusText(
    text: String,
    icon: ImageVector?,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space1),
    ) {
        if (icon != null) Image(icon, null, Modifier.size(14.dp), colorFilter = ColorFilter.tint(tint))
        BasicText(text, style = SnTypography.caption.copy(color = SnColors.fgSecondary), maxLines = 1)
    }
}

/**
 * The streaming service a source opens on: its icon from its own site, through the core,
 * which only asks public HTTPS sites and caches the answer; meanwhile, or without one, the
 * service's initial in a neutral tile.
 */
@Composable
private fun ServiceTile(source: StreamSource) {
    val link = source.outsideLink()
    val context = LocalPlatformContext.current
    val icon by produceState<ByteArray?>(ServiceIcons.known(link), link) {
        if (link != null && value == null) value = ServiceIcons.load(AppGraph.core(context), link)
    }
    val shape = RoundedCornerShape(SnRadius.radiusSm)
    Box(
        Modifier
            .size(ServiceTileSize)
            .clip(shape)
            .background(SnColors.surfaceMuted, shape),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            serviceInitial(link, source.addonName),
            style = SnTypography.label.copy(color = SnColors.fgSecondary),
        )
        icon?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

private val ServiceTileSize = 24.dp

/** Service icons already asked for in this run, so lists do not ask the core again. */
private object ServiceIcons {
    private val icons = ConcurrentHashMap<String, ByteArray>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    fun known(link: String?): ByteArray? = link?.let { icons[it] }

    suspend fun load(
        core: StremioCore,
        link: String,
    ): ByteArray? {
        icons[link]?.let { return it }
        if (link in missing) return null
        val icon = runCatching { core.serviceIcon(link) }.getOrNull()
        if (icon == null) missing += link else icons[link] = icon
        return icon
    }
}

/** The small accent mark of the recommended source. */
@Composable
private fun Badge(text: String) {
    BasicText(
        text,
        modifier =
            Modifier
                .background(SnColors.accent, RoundedCornerShape(SnRadius.radiusSm))
                .padding(horizontal = SnSpacing.space2, vertical = 2.dp),
        style = SnTypography.badge.copy(color = SnColors.onAccent),
        maxLines = 1,
    )
}
