// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.affonsopaulo.stremionow.core.Episode
import io.github.affonsopaulo.stremionow.core.FallbackReason
import io.github.affonsopaulo.stremionow.core.SkipSegment
import io.github.affonsopaulo.stremionow.core.TorrentPhase
import io.github.affonsopaulo.stremionow.core.TorrentProgress
import io.github.affonsopaulo.stremionow.core.TorrentSource
import io.github.affonsopaulo.stremionow.player.Chapters
import io.github.affonsopaulo.stremionow.player.Markers
import io.github.affonsopaulo.stremionow.player.PlaybackPrompt
import io.github.affonsopaulo.stremionow.player.PlaybackState
import io.github.affonsopaulo.stremionow.player.Segment
import io.github.affonsopaulo.stremionow.player.SkipKind
import io.github.affonsopaulo.stremionow.player.Track
import io.github.affonsopaulo.stremionow.ui.components.SnButton
import io.github.affonsopaulo.stremionow.ui.components.SnButtonStyle
import io.github.affonsopaulo.stremionow.ui.components.SnIconButton
import io.github.affonsopaulo.stremionow.ui.components.snClickable
import io.github.affonsopaulo.stremionow.ui.data.PlaybackSession
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.pluralStringResource
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.platform.formatShortFileSize
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography
import kotlinx.coroutines.delay
import java.util.Locale
import io.github.affonsopaulo.stremionow.core.SkipKind as CoreSkipKind

// Parts of the player screen that do not depend on the platform: the panels, the "Up next"
// card, the skip buttons, the error message and the torrent status line. Each platform's
// PlayerScreen lays them out over its own video surface and controls.

/** Seconds the "Up next" card counts down before playing the next episode. */
private const val UP_NEXT_COUNTDOWN = 10

/** Controls hide after this long without input while playing. */
internal const val CONTROLS_TIMEOUT_MS = 4_000L

/**
 * How far the corner prompt rises while the controls show, so it sits above the timeline and
 * the bottom buttons instead of covering them.
 */
internal val PromptControlsClearance = SnSpacing.space16 + SnSpacing.space4

/** Side panels of the player. */
internal enum class Panel { Tracks, Episodes }

/**
 * The prompt in the bottom corner once a file plays: "Skip intro", "Skip recap" or "Skip
 * credits" inside those segments, and the next episode's card near the end (see
 * [PlaybackPrompt.at] for which one shows when). Segments come from the file's chapters, then
 * from [skips] (AniSkip) for what the chapters do not mark.
 *
 * With [takeFocus], each prompt takes the focus when it appears, so a remote's OK acts on it.
 * Platforms set it only when nothing else on screen could have the focus.
 */
@Composable
internal fun PlaybackPrompts(
    state: PlaybackState,
    skips: Markers,
    next: Episode?,
    onSeekTo: (Double) -> Unit,
    onOpenEpisode: (Episode) -> Unit,
    modifier: Modifier = Modifier,
    takeFocus: Boolean = false,
) {
    val markers =
        remember(state.chapters, state.durationSeconds, skips) {
            Chapters.markers(state.chapters, state.durationSeconds).orElse(skips)
        }
    val prompt =
        PlaybackPrompt.at(
            markers = markers,
            positionSeconds = state.positionSeconds,
            durationSeconds = state.durationSeconds,
            hasNext = next != null,
            isEnded = state.isEnded,
        )
    val focus = remember { FocusRequester() }
    LaunchedEffect(prompt, takeFocus) {
        if (prompt != null && takeFocus) runCatching { focus.requestFocus() }
    }
    when (prompt) {
        is PlaybackPrompt.Skip -> {
            SnButton(
                text =
                    stringResource(
                        when (prompt.kind) {
                            SkipKind.Intro -> Res.string.player_skip_intro
                            SkipKind.Recap -> Res.string.player_skip_recap
                            SkipKind.Credits -> Res.string.player_skip_credits
                        },
                    ),
                icon = SnIcons.Skip,
                style = SnButtonStyle.Secondary,
                onClick = { onSeekTo(prompt.toSeconds) },
                modifier = modifier.focusRequester(focus),
            )
        }

        PlaybackPrompt.UpNext -> {
            if (next != null) {
                UpNext(
                    episode = next,
                    // mpv pauses itself at the end of a file; only a pause by the viewer stops the count.
                    paused = state.isPaused && !state.isEnded,
                    onPlay = { onOpenEpisode(next) },
                    modifier = modifier.focusRequester(focus),
                )
            }
        }

        null -> {}
    }
}

/** AniSkip's segments as the player's markers. */
internal fun List<SkipSegment>.toMarkers(): Markers =
    Markers.of(
        map { segment ->
            val kind =
                when (segment.kind) {
                    CoreSkipKind.INTRO -> SkipKind.Intro
                    CoreSkipKind.RECAP -> SkipKind.Recap
                    CoreSkipKind.CREDITS -> SkipKind.Credits
                }
            kind to Segment(segment.startSeconds, segment.endSeconds)
        },
    )

/** Shown when the source could not be prepared or played: back, or choose another source. */
@Composable
internal fun PlaybackError(
    onChangeSource: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.widthIn(max = 480.dp).padding(SnSpacing.space6),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space3),
    ) {
        BasicText(
            stringResource(Res.string.player_error_title),
            style = SnTypography.headingMd.copy(color = SnColors.fg),
        )
        BasicText(
            stringResource(Res.string.player_error_body),
            style = SnTypography.body.copy(color = SnColors.fgSecondary),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(SnSpacing.space2)) {
            SnButton(stringResource(Res.string.nav_back), onBack, style = SnButtonStyle.Ghost)
            SnButton(stringResource(Res.string.player_choose_other), onChangeSource)
        }
    }
}

/** A panel over the right side of the video, closed by a tap outside it or its close button. */
@Composable
internal fun SidePanel(
    title: String,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(SnColors.scrimSoft)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose,
            ),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .width(380.dp)
                .background(SnColors.surfaceRaised)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .safeDrawingPadding()
                .padding(SnSpacing.space4),
            verticalArrangement = Arrangement.spacedBy(SnSpacing.space3),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    title,
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = SnTypography.sectionTitle.copy(color = SnColors.fg),
                )
                SnIconButton(SnIcons.Close, stringResource(Res.string.action_close), onClose)
            }
            content()
        }
    }
}

/** Audio and subtitle choices of the current file. */
@Composable
internal fun TracksList(
    tracks: List<Track>,
    onSelectAudio: (Int?) -> Unit,
    onSelectSubtitle: (Int?) -> Unit,
) {
    val audio = tracks.filter { it.type == Track.Type.Audio }
    val subtitles = tracks.filter { it.type == Track.Type.Subtitle }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(SnSpacing.space1)) {
        if (audio.size > 1) {
            item { PanelHeading(stringResource(Res.string.player_audio)) }
            items(audio, key = { "a${it.id}" }) { track ->
                PanelOption(trackLabel(track), track.isSelected) { onSelectAudio(track.id) }
            }
        }
        item { PanelHeading(stringResource(Res.string.player_subtitles)) }
        item(key = "off") {
            PanelOption(
                stringResource(Res.string.player_subtitles_off),
                subtitles.none { it.isSelected },
            ) { onSelectSubtitle(null) }
        }
        items(subtitles, key = { "s${it.id}" }) { track ->
            PanelOption(trackLabel(track), track.isSelected) { onSelectSubtitle(track.id) }
        }
    }
}

@Composable
private fun trackLabel(track: Track): String {
    val language =
        track.language?.let { code ->
            Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault()).takeIf { it.isNotBlank() && it != code }
                ?: code
        }
    return listOfNotNull(language, track.title?.takeIf { it.isNotBlank() && it != track.language })
        .joinToString(" · ")
        .ifEmpty { stringResource(Res.string.player_track_number, track.id) }
}

/** The episodes of the season being watched; the current one is marked. */
@Composable
internal fun EpisodesList(
    selection: PlaybackSession.Selection,
    onOpenEpisode: (Episode) -> Unit,
) {
    val season = selection.episode?.season
    val episodes = selection.details.episodes.filter { it.season == season }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(SnSpacing.space1)) {
        items(episodes, key = { it.id }) { episode ->
            val number = episode.episode?.toInt()
            val label =
                if (number !=
                    null
                ) {
                    stringResource(Res.string.episode_numbered, number, episode.title)
                } else {
                    episode.title
                }
            PanelOption(label, episode.id == selection.videoId) {
                if (episode.id != selection.videoId) onOpenEpisode(episode)
            }
        }
    }
}

@Composable
private fun PanelHeading(text: String) {
    BasicText(
        text.uppercase(),
        modifier = Modifier.padding(top = SnSpacing.space3, bottom = SnSpacing.space1).semantics { heading() },
        style = SnTypography.overline.copy(color = SnColors.fgMuted),
    )
}

@Composable
private fun PanelOption(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(SnRadius.radiusMd)
    Row(
        Modifier
            .fillMaxWidth()
            .snClickable(shape, onClick = onClick)
            .background(if (selected) SnColors.surfaceSelected else Color.Transparent, shape)
            .padding(horizontal = SnSpacing.space3, vertical = SnSpacing.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
    ) {
        BasicText(
            text,
            modifier = Modifier.weight(1f),
            style = SnTypography.body.copy(color = if (selected) SnColors.fg else SnColors.fgSecondary),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (selected) {
            Image(
                SnIcons.Check,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                colorFilter = ColorFilter.tint(SnColors.accent),
            )
        }
    }
}

/**
 * "Looking for peers…", "Checking downloaded data…" or "12 peers · 2.4 MB/s", after a line
 * saying which device downloads the torrent when it is not this one, or why this one took over.
 */
@Composable
internal fun torrentStatusText(progress: TorrentProgress): String {
    val source =
        when (val source = progress.source) {
            is TorrentSource.Remote -> {
                stringResource(Res.string.torrent_on_device, source.name)
            }

            is TorrentSource.FellBack -> {
                stringResource(
                    when (source.reason) {
                        FallbackReason.UNREACHABLE -> Res.string.torrent_fallback_unreachable
                        FallbackReason.UNPAIRED -> Res.string.torrent_fallback_unpaired
                        FallbackReason.PAUSED -> Res.string.torrent_fallback_paused
                    },
                    source.name,
                )
            }

            TorrentSource.ThisDevice -> {
                null
            }
        }
    return listOfNotNull(source, torrentPhaseText(progress)).joinToString("\n")
}

@Composable
private fun torrentPhaseText(progress: TorrentProgress): String =
    when (progress.phase) {
        TorrentPhase.METADATA -> {
            stringResource(Res.string.torrent_finding_peers)
        }

        TorrentPhase.CHECKING -> {
            stringResource(Res.string.torrent_checking)
        }

        TorrentPhase.FAILED -> {
            stringResource(Res.string.player_error_title)
        }

        else -> {
            val peers = progress.peers.toInt()
            val speed = formatShortFileSize(progress.downloadSpeed.toLong())
            pluralStringResource(Res.plurals.torrent_peers, peers, peers, speed)
        }
    }

/**
 * The next episode's card, counting down before it plays. Pausing stops the countdown, and the
 * card is a regular focusable button, so a remote can start it at once.
 */
@Composable
internal fun UpNext(
    episode: Episode,
    paused: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var remaining by remember(episode.id) { mutableIntStateOf(UP_NEXT_COUNTDOWN) }
    LaunchedEffect(episode.id, paused) {
        while (!paused && remaining > 0) {
            delay(1_000)
            remaining--
        }
        if (remaining == 0) onPlay()
    }
    val number = episode.episode?.toInt()
    val season = episode.season?.toInt()
    val label = if (season != null && number != null) stringResource(Res.string.episode_short, season, number) else null
    Column(
        modifier =
            modifier
                .widthIn(max = 360.dp)
                .snClickable(RoundedCornerShape(SnRadius.radiusLg), onClick = onPlay)
                .background(SnColors.surfaceRaised, RoundedCornerShape(SnRadius.radiusLg))
                .padding(SnSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space2),
    ) {
        BasicText(
            stringResource(Res.string.player_up_next, remaining),
            style = SnTypography.label.copy(color = SnColors.accentText),
        )
        BasicText(
            listOfNotNull(label, episode.title.ifBlank { null }).joinToString(" · "),
            style = SnTypography.bodyLg.copy(color = SnColors.fg),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** How long the HDR prompt stays before it counts as "not now". */
internal const val HDR_PROMPT_TIMEOUT_MS = 10_000

/**
 * A small prompt in the corner, in the place of the skip and up-next prompts, asking whether to
 * turn on HDR for this title ([onTurnOn]) or for every HDR video ([onAlways]). Playback goes on
 * meanwhile. Unanswered, it fades away after [HDR_PROMPT_TIMEOUT_MS] through [onTimeout], which
 * changes nothing; a thin line shows the time left. It never takes the focus, so the player's
 * keys keep working.
 */
@Composable
internal fun HdrPrompt(
    onTurnOn: () -> Unit,
    onAlways: () -> Unit,
    onTimeout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(Res.string.player_hdr_title)
    val remaining = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        remaining.animateTo(0f, tween(HDR_PROMPT_TIMEOUT_MS, easing = LinearEasing))
        onTimeout()
    }
    val shape = RoundedCornerShape(SnRadius.radiusLg)
    Column(
        modifier =
            modifier
                .widthIn(max = 360.dp)
                .semantics {
                    paneTitle = title
                    liveRegion = LiveRegionMode.Polite
                }.background(SnColors.surfaceRaised, shape)
                .clip(shape),
    ) {
        Column(
            Modifier.padding(SnSpacing.space4),
            verticalArrangement = Arrangement.spacedBy(SnSpacing.space1),
        ) {
            BasicText(title, style = SnTypography.bodyLg.copy(color = SnColors.fg))
            BasicText(
                stringResource(Res.string.player_hdr_body),
                style = SnTypography.body.copy(color = SnColors.fgSecondary),
            )
            Row(
                Modifier.padding(top = SnSpacing.space2),
                horizontalArrangement = Arrangement.spacedBy(SnSpacing.space2),
            ) {
                SnButton(stringResource(Res.string.player_hdr_turn_on), onTurnOn)
                SnButton(stringResource(Res.string.player_hdr_always), onAlways, style = SnButtonStyle.Secondary)
            }
        }
        Box(
            Modifier
                .fillMaxWidth(remaining.value)
                .height(2.dp)
                .background(SnColors.accent),
        )
    }
}
