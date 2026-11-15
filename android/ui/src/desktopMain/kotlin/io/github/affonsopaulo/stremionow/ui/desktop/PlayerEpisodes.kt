// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.affonsopaulo.stremionow.core.Episode
import io.github.affonsopaulo.stremionow.player.PlaybackState
import io.github.affonsopaulo.stremionow.ui.components.EpisodeRowControlsCenter
import io.github.affonsopaulo.stremionow.ui.components.SnEpisodeCard
import io.github.affonsopaulo.stremionow.ui.components.SnIconButton
import io.github.affonsopaulo.stremionow.ui.components.SnMenuChip
import io.github.affonsopaulo.stremionow.ui.components.SnRow
import io.github.affonsopaulo.stremionow.ui.data.PlaybackSession
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing

/** Seasons in the order the strip offers them: regular ones in order, specials (season 0) last. */
internal fun stripSeasons(episodes: List<Episode>): List<Int> {
    val seasons = episodes.mapNotNull { it.season?.toInt() }.distinct()
    return seasons.filter { it > 0 }.sorted() + seasons.filter { it == 0 }
}

/**
 * The episodes, in a strip along the bottom of the player, as in the design: a season menu and
 * a close button, then the season's episodes as cards with their picture, runtime, progress and
 * "Watched" badge; the one playing is marked "Watching now" and scrolled into view. Choosing
 * another episode plays it.
 */
@Composable
internal fun EpisodesStrip(
    selection: PlaybackSession.Selection,
    playback: PlaybackState,
    watched: Set<String>,
    onOpenEpisode: (Episode) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val episodes = selection.details.episodes
    val seasons = remember(episodes) { stripSeasons(episodes) }
    val currentSeason = selection.episode?.season?.toInt()
    var season by remember(selection.videoId) { mutableStateOf(currentSeason ?: seasons.firstOrNull()) }
    val shown = remember(episodes, season) { episodes.filter { it.season?.toInt() == season } }
    val listState = rememberLazyListState()
    LaunchedEffect(season) {
        val playing = shown.indexOfFirst { it.id == selection.videoId }
        if (playing >= 0) listState.scrollToItem(playing)
    }
    val title = stringResource(Res.string.player_episodes)
    Column(
        modifier
            .fillMaxWidth()
            .semantics { contentDescription = title }
            .background(SnColors.bg.copy(alpha = 0.96f))
            .padding(top = SnSpacing.space6, bottom = SnSpacing.space8),
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space4),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(SnColors.border))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = SnSpacing.space10),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (seasons.size > 1 && season != null) {
                SnMenuChip(
                    options = seasons,
                    selected = season!!,
                    label = {
                        if (it ==
                            0
                        ) {
                            stringResource(Res.string.details_specials)
                        } else {
                            stringResource(Res.string.details_season, it)
                        }
                    },
                    onSelect = { season = it },
                    menuLabel = stringResource(Res.string.player_season_menu),
                )
            }
            Box(Modifier.weight(1f))
            SnIconButton(
                SnIcons.Close,
                stringResource(Res.string.player_close_episodes),
                onClose,
                background = SnColors.surfaceMuted,
            )
        }
        // A row like Home's: buttons at its ends to page through it under the pointer, and the
        // mouse can drag it or turn it with Shift and the wheel.
        SnRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = SnSpacing.space10, vertical = SnSpacing.space2),
            spacing = SnSpacing.space4,
            controlsCenter = EpisodeRowControlsCenter,
            overContent = true,
        ) {
            items(shown, key = { it.id }) { episode ->
                val playing = episode.id == selection.videoId
                val number = episode.episode?.toInt()
                val name =
                    if (number !=
                        null
                    ) {
                        stringResource(Res.string.episode_numbered, number, episode.title)
                    } else {
                        episode.title
                    }
                val runtime = episode.runtimeMinutes?.toInt()?.let { stringResource(Res.string.episode_runtime, it) }
                val progress =
                    if (playing) {
                        playback.durationSeconds?.takeIf { it > 0 }?.let {
                            (playback.positionSeconds / it)
                                .toFloat()
                                .coerceIn(
                                    0f,
                                    1f,
                                )
                        }
                    } else {
                        null
                    }
                SnEpisodeCard(
                    title = name,
                    thumbnail = episode.thumbnail,
                    meta = if (playing) stringResource(Res.string.player_watching_now) else runtime,
                    overview = null,
                    onClick = { if (playing) onClose() else onOpenEpisode(episode) },
                    progress = progress,
                    highlighted = playing,
                    // "Watching now" under one card must not make it taller than the rest.
                    keepMetaLine = true,
                    watchedLabel =
                        if (!playing &&
                            episode.id in watched
                        ) {
                            stringResource(Res.string.episode_watched)
                        } else {
                            null
                        },
                )
            }
        }
    }
}
