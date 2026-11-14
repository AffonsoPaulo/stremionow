// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.affonsopaulo.stremionow.core.StreamSource
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.ui.components.SnChipRow
import io.github.affonsopaulo.stremionow.ui.components.SnIconButton
import io.github.affonsopaulo.stremionow.ui.components.SnLoading
import io.github.affonsopaulo.stremionow.ui.components.SnMessage
import io.github.affonsopaulo.stremionow.ui.components.SnMinimalScroll
import io.github.affonsopaulo.stremionow.ui.components.snClickable
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.io
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.pluralStringResource
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.platform.LocalPlatformContext
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
) {
    val searching: Boolean get() = !finished

    /** Addons that have not answered yet. */
    val addonsPending: Int get() = (addonsAsked - addonsAnswered).coerceAtLeast(0)
}

/**
 * Asks every stream addon for one video's sources at once, ranking each answer with the rest
 * as it arrives, so the list fills in while slower addons are still answering. It runs on
 * [scope] until [cancel] or the scope ends.
 */
class SourcesLoader(
    private val core: StremioCore,
    scope: CoroutineScope,
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
 * The sources of a video in place of the episodes: a header with the way back (series) and
 * the addon filter, then the sources, best first, in as many [columns] as fit, then how many
 * addons are still answering. [firstFocus] reaches the first source.
 */
internal fun LazyListScope.sourcesItems(
    state: SourcesState,
    subject: SourcesSubject?,
    filter: String?,
    onFilter: (String?) -> Unit,
    columns: Int,
    firstFocus: FocusRequester,
    onChoose: (StreamSource) -> Unit,
) {
    val counts = addonCounts(state.sources)
    // A filter whose addon has no sources (any more) shows them all.
    val addon = filter?.takeIf { name -> counts.any { it.first == name } }
    val shown = state.sources.fromAddon(addon)
    item(key = "sources-header") {
        // The filter is a row of its own, which scrolls without the page header's inset.
        SnMinimalScroll { SourcesHeader(subject, counts, state.sources.size, addon, onFilter) }
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
            val best = state.sources.firstOrNull()?.takeIf { it.playsInApp }
            val rows = shown.withIndex().chunked(columns.coerceAtLeast(1))
            rows.forEachIndexed { rowIndex, row ->
                item(key = "sources-row-${state.videoId}-$addon-$rowIndex") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = SnLayout.gutter)
                            .padding(bottom = SnSpacing.space3),
                        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
                    ) {
                        row.forEach { (index, source) ->
                            SourceCard(
                                source = source,
                                best = source === best,
                                onClick = { onChoose(source) },
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
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

@Composable
private fun SourcesHeader(
    subject: SourcesSubject?,
    counts: List<Pair<String, Int>>,
    total: Int,
    filter: String?,
    onFilter: (String?) -> Unit,
) {
    Column(
        Modifier.padding(bottom = SnSpacing.space3),
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space3),
    ) {
        if (subject != null) {
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
        // Only worth a filter when more than one addon answered with sources.
        if (counts.size > 1) {
            val options: List<String?> = listOf(null) + counts.map { it.first }
            val allLabel = stringResource(Res.string.sources_filter_all, total)
            SnChipRow(
                options = options,
                selected = filter,
                label = { name ->
                    if (name == null) {
                        allLabel
                    } else {
                        stringResource(Res.string.sources_filter_addon, name, counts.first { it.first == name }.second)
                    }
                },
                onSelect = onFilter,
                modifier = Modifier.padding(horizontal = SnLayout.gutter),
            )
        }
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
        Box(
            Modifier
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
}

/**
 * One source: its addon and quality, the title or file name, the details the core read from
 * it (seeds, size, release group) and its badges. Sources that open in another app say so
 * and show the service they open on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceCard(
    source: StreamSource,
    best: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(SnRadius.radiusLg)
    Column(
        modifier =
            modifier
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
            if (!source.playsInApp) ServiceTile(source)
            BasicText(
                listOfNotNull(source.addonName, source.quality).joinToString(" · "),
                modifier = Modifier.weight(1f),
                style = SnTypography.label.copy(color = SnColors.fgSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (best) Badge(stringResource(Res.string.sources_best), accent = true)
        }
        val lines = descriptionLines(source.description).ifEmpty { descriptionLines(source.name).drop(1) }
        if (lines.isNotEmpty()) {
            BasicText(
                lines.joinToString("\n"),
                style = SnTypography.body.copy(color = SnColors.fg),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SourceDetails(source)
        val badges = source.badges
        if (badges.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(SnSpacing.space1),
                verticalArrangement = Arrangement.spacedBy(SnSpacing.space1),
            ) {
                badges.forEach { Badge(it, accent = false) }
            }
        }
        if (!source.playsInApp) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SnSpacing.space1),
            ) {
                Image(
                    SnIcons.ExternalLink,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    colorFilter = ColorFilter.tint(SnColors.fgMuted),
                )
                BasicText(
                    stringResource(Res.string.sources_opens_outside),
                    style = SnTypography.caption.copy(color = SnColors.fgMuted),
                )
            }
        }
    }
}

/** Seeds, size and release group, as far as the source tells them. */
@Composable
private fun SourceDetails(source: StreamSource) {
    // The size is usually a badge already; a size known only from the addon's hints is not.
    val size = source.size?.takeIf { it !in source.badges }
    if (source.seeders == null && size == null && source.releaseGroup == null) return
    val style = SnTypography.caption.copy(color = SnColors.fgSecondary)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
    ) {
        source.seeders?.let { seeders ->
            val label = pluralStringResource(Res.plurals.sources_seeders, seeders.toInt(), seeders.toInt())
            Row(
                Modifier.semantics(mergeDescendants = true) { contentDescription = label },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Image(
                    SnIcons.Seeds,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    colorFilter = ColorFilter.tint(SnColors.success),
                )
                BasicText(seeders.toString(), style = style)
            }
        }
        size?.let { BasicText(it, style = style) }
        source.releaseGroup?.let {
            BasicText(it, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
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
