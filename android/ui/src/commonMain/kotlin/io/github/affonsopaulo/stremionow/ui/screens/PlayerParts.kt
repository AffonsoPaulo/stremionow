// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.affonsopaulo.stremionow.core.AppSettings
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
import io.github.affonsopaulo.stremionow.ui.components.SnLogoLoader
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

/**
 * What the "Up next" card does, from Settings › Playback: count down [countdownSeconds] and then
 * play the next episode, or show without a countdown (null). With [playsAtEnd], the next episode
 * plays once this one ends, for a card that does not count down.
 */
internal data class UpNextBehavior(
    val countdownSeconds: Int?,
    val playsAtEnd: Boolean,
) {
    companion object {
        /** Ten seconds, then the next episode: the defaults. */
        val DEFAULT = UpNextBehavior(countdownSeconds = 10, playsAtEnd = false)

        /**
         * With "Play the next episode automatically" off, the card only offers it. On, it counts
         * down for the chosen length, or, with no countdown, the next episode plays at the end.
         */
        fun from(settings: AppSettings?): UpNextBehavior {
            if (settings == null) return DEFAULT
            val countdown = settings.upNextCountdownSeconds.toInt()
            return when {
                !settings.autoplayNext -> UpNextBehavior(countdownSeconds = null, playsAtEnd = false)
                countdown <= 0 -> UpNextBehavior(countdownSeconds = null, playsAtEnd = true)
                else -> UpNextBehavior(countdownSeconds = countdown, playsAtEnd = false)
            }
        }
    }
}

/** Countdown lengths offered in Settings, in seconds; 0 is none. The core keeps 5 to 90. */
internal val UpNextCountdownChoices = listOf(0, 5, 10, 15, 20, 30, 45, 60, 90)

/** Controls hide after this long without input while playing. */
internal const val CONTROLS_TIMEOUT_MS = 4_000L

/**
 * How far the corner prompt rises while the controls show, so it sits above the timeline and
 * the bottom buttons instead of covering them.
 */
internal val PromptControlsClearance = SnSpacing.space16 + SnSpacing.space4

/** Side panels of the player. */
internal enum class Panel { Tracks, Playback, Episodes }

/**
 * The prompt in the bottom corner once a file plays: "Skip intro", "Skip recap" or "Skip
 * credits" inside those segments, and the next episode's card near the end (see
 * [PlaybackPrompt.at] for which one shows when). Segments come from the file's chapters, then
 * from [skips] (AniSkip) for what the chapters do not mark.
 *
 * With [takeFocus], each prompt takes the focus when it appears, so a remote's OK acts on it.
 * Platforms set it only when nothing else on screen could have the focus. [upNext] is what the
 * card does, from Settings.
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
    upNext: UpNextBehavior = UpNextBehavior.DEFAULT,
) {
    // Without a countdown, "Play the next episode automatically" moves on when this one ends.
    val currentOnOpenEpisode by rememberUpdatedState(onOpenEpisode)
    LaunchedEffect(state.isEnded, next?.id, upNext.playsAtEnd) {
        if (upNext.playsAtEnd && state.isEnded && next != null) currentOnOpenEpisode(next)
    }
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
    var creditsChosen by remember(next?.id) { mutableStateOf(false) }
    LaunchedEffect(prompt, takeFocus) {
        if (prompt != null && takeFocus) runCatching { focus.requestFocus() }
    }
    when (prompt) {
        is PlaybackPrompt.Skip -> {
            SkipPill(
                text =
                    stringResource(
                        when (prompt.kind) {
                            SkipKind.Intro -> Res.string.player_skip_intro
                            SkipKind.Recap -> Res.string.player_skip_recap
                            SkipKind.Credits -> Res.string.player_skip_credits
                        },
                    ),
                onClick = { onSeekTo(prompt.toSeconds) },
                modifier = modifier.focusRequester(focus),
            )
        }

        PlaybackPrompt.UpNext -> {
            // "Watch credits" hides the card until the episode ends.
            if (next != null && (!creditsChosen || state.isEnded)) {
                UpNext(
                    episode = next,
                    countdownSeconds = upNext.countdownSeconds,
                    // mpv pauses itself at the end of a file; only a pause by the viewer stops the count.
                    paused = state.isPaused && !state.isEnded,
                    onPlay = { onOpenEpisode(next) },
                    onWatchCredits = { creditsChosen = true },
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

/** A track's language in the device's language, and its title when it adds something. */
@Composable
internal fun trackLabel(track: Track): String {
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

/** The "Skip intro" (recap, credits) prompt: a pill with a white border over the video. */
@Composable
internal fun SkipPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(SnRadius.radiusFull)
    Row(
        modifier
            .heightIn(min = 52.dp)
            .snClickable(shape, onClick = onClick)
            .background(SnColors.scrimSoft, shape)
            .border(2.dp, SnColors.fg.copy(alpha = 0.85f), shape)
            .padding(horizontal = SnSpacing.space6),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
    ) {
        BasicText(text, style = SnTypography.bodyLg.copy(color = SnColors.fg, fontWeight = FontWeight.Bold))
        Image(
            SnIcons.Skip,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            colorFilter = ColorFilter.tint(SnColors.fg),
        )
    }
}

/**
 * The next episode, near the end: its picture with "Next episode · S1 E6" and its title, a
 * "Next episode" button that fills up as the countdown runs (it plays the episode when full,
 * unless the viewer pauses), "Watch credits", which hides the card, and the time left in words.
 * Without [countdownSeconds] the button is simply full and nothing plays by itself.
 */
@Composable
internal fun UpNext(
    episode: Episode,
    countdownSeconds: Int?,
    paused: Boolean,
    onPlay: () -> Unit,
    onWatchCredits: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val countdown = countdownSeconds?.takeIf { it > 0 }
    var remaining by remember(episode.id, countdown) { mutableIntStateOf(countdown ?: 0) }
    LaunchedEffect(episode.id, paused, countdown) {
        if (countdown == null) return@LaunchedEffect
        while (!paused && remaining > 0) {
            delay(1_000)
            remaining--
        }
        if (remaining == 0) onPlay()
    }
    val fill by animateFloatAsState(
        targetValue = if (countdown == null) 1f else 1f - remaining.toFloat() / countdown,
        animationSpec = tween(1_000, easing = LinearEasing),
        label = "up-next-fill",
    )
    val number = episode.episode?.toInt()
    val season = episode.season?.toInt()
    val code = if (season != null && number != null) stringResource(Res.string.episode_short, season, number) else null
    val heading = listOfNotNull(stringResource(Res.string.player_next), code).joinToString(" · ")
    val card = RoundedCornerShape(SnRadius.radiusLg)
    Column(modifier.width(PROMPT_WIDTH), verticalArrangement = Arrangement.spacedBy(SnSpacing.space3)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .shadow(18.dp, card)
                .clip(card)
                .background(SnColors.surfaceRaised),
        ) {
            if (episode.thumbnail != null) {
                AsyncImage(
                    model = episode.thumbnail,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to SnColors.scrim)),
            )
            Column(
                Modifier.align(Alignment.BottomStart).padding(SnSpacing.space4),
                verticalArrangement = Arrangement.spacedBy(SnSpacing.space1),
            ) {
                BasicText(heading.uppercase(), style = SnTypography.overline.copy(color = SnColors.fgSecondary))
                if (episode.title.isNotBlank()) {
                    BasicText(
                        episode.title,
                        style = SnTypography.headingMd.copy(color = SnColors.fg),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FillingButton(
                text = stringResource(Res.string.player_next),
                fill = fill,
                onClick = onPlay,
                modifier = Modifier.weight(1f),
            )
            OutlineButton(stringResource(Res.string.player_watch_credits), onWatchCredits)
        }
        if (countdown != null) {
            BasicText(
                stringResource(Res.string.player_starts_in, remaining),
                style = SnTypography.caption.copy(color = SnColors.fgSecondary),
            )
        }
    }
}

/** A white pill that fills from the left as [fill] goes from 0 to 1, over a grey one. */
@Composable
private fun FillingButton(
    text: String,
    fill: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(SnRadius.radiusFull)
    Box(
        modifier
            .heightIn(min = 50.dp)
            .snClickable(shape, onClick = onClick)
            .clip(shape)
            .background(SnColors.fgMuted),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.matchParentSize().drawWithContent {
                clipRect(right = size.width * fill) { drawRect(SnColors.fg) }
            },
        )
        Row(
            Modifier.padding(horizontal = SnSpacing.space6),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space2),
        ) {
            Image(
                SnIcons.Play,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                colorFilter = ColorFilter.tint(SnColors.bg),
            )
            BasicText(text, style = SnTypography.buttonLg.copy(color = SnColors.bg))
        }
    }
}

/** A pill with a thin light border over a dark see-through fill, for a prompt's second choice. */
@Composable
private fun OutlineButton(
    text: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(SnRadius.radiusFull)
    Box(
        Modifier
            .heightIn(min = 50.dp)
            .snClickable(shape, onClick = onClick)
            .background(SnColors.scrimSoft, shape)
            .border(1.dp, SnColors.fg.copy(alpha = 0.45f), shape)
            .padding(horizontal = SnSpacing.space6),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = SnTypography.buttonLg.copy(color = SnColors.fg))
    }
}

/** How long the HDR prompt stays before it counts as "not now". */
internal const val HDR_PROMPT_TIMEOUT_MS = 10_000

/**
 * Asks whether to turn on HDR for this title ([onTurnOn]) or for every HDR video ([onAlways]),
 * in the shape of the next episode's card: the question in the card, a close button in its
 * corner, the two choices below and the time left in words. Playback goes on meanwhile.
 * Closing it, or letting the time run out ([onTimeout]), means "not now", which changes
 * nothing; the countdown is text only, since running out does not turn HDR on. It never takes
 * the focus, so the player's keys keep working.
 */
@Composable
internal fun HdrPrompt(
    onTurnOn: () -> Unit,
    onAlways: () -> Unit,
    onTimeout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(Res.string.player_hdr_title)
    var remaining by remember { mutableIntStateOf(HDR_PROMPT_TIMEOUT_MS / 1_000) }
    LaunchedEffect(Unit) {
        while (remaining > 0) {
            delay(1_000)
            remaining--
        }
        onTimeout()
    }
    val card = RoundedCornerShape(SnRadius.radiusLg)
    Column(
        modifier
            .width(PROMPT_WIDTH)
            .semantics {
                paneTitle = title
                liveRegion = LiveRegionMode.Polite
            },
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space3),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .shadow(18.dp, card)
                .background(SnColors.surfaceRaised, card)
                .border(1.dp, SnColors.border, card),
        ) {
            Column(
                Modifier.padding(
                    start = SnSpacing.space4,
                    top = SnSpacing.space4,
                    bottom = SnSpacing.space4,
                    end = SnSpacing.space12,
                ),
                verticalArrangement = Arrangement.spacedBy(SnSpacing.space1),
            ) {
                BasicText(title, style = SnTypography.headingMd.copy(color = SnColors.fg))
                BasicText(
                    stringResource(Res.string.player_hdr_body),
                    style = SnTypography.body.copy(color = SnColors.fgSecondary),
                )
            }
            SnIconButton(
                SnIcons.Close,
                stringResource(Res.string.player_hdr_not_now),
                onTimeout,
                modifier = Modifier.align(Alignment.TopEnd).padding(SnSpacing.space1),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3)) {
            SnButton(stringResource(Res.string.player_hdr_turn_on), onTurnOn)
            OutlineButton(stringResource(Res.string.player_hdr_always), onAlways)
        }
        BasicText(
            stringResource(Res.string.player_closes_in, remaining),
            style = SnTypography.caption.copy(color = SnColors.fgSecondary),
        )
    }
}

/** How long the incomplete video question stays before it counts as "don't save". */
internal const val INCOMPLETE_PROMPT_TIMEOUT_S = 15

/**
 * Asks about a video much shorter than expected ([durationSeconds] long), which may be a
 * provider's notice rather than the episode: whether to save its progress. In the shape of the
 * HDR question. "Don't save" is the default: its button fills up as the countdown runs and
 * answers when full ([onDiscard]); "Save progress" ([onSave]) is for when the video is right.
 * It never takes the focus, so the player's keys keep working.
 */
@Composable
internal fun IncompleteVideoPrompt(
    durationSeconds: Double,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(Res.string.player_incomplete_title)
    var remaining by remember { mutableIntStateOf(INCOMPLETE_PROMPT_TIMEOUT_S) }
    LaunchedEffect(Unit) {
        while (remaining > 0) {
            delay(1_000)
            remaining--
        }
        onDiscard()
    }
    val fill by animateFloatAsState(
        targetValue = 1f - remaining.toFloat() / INCOMPLETE_PROMPT_TIMEOUT_S,
        animationSpec = tween(1_000, easing = LinearEasing),
        label = "incomplete-fill",
    )
    val card = RoundedCornerShape(SnRadius.radiusLg)
    Column(
        modifier
            .width(PROMPT_WIDTH)
            .semantics {
                paneTitle = title
                liveRegion = LiveRegionMode.Polite
            },
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space3),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .shadow(18.dp, card)
                .background(SnColors.surfaceRaised, card)
                .border(1.dp, SnColors.border, card)
                .padding(SnSpacing.space4),
            verticalArrangement = Arrangement.spacedBy(SnSpacing.space1),
        ) {
            BasicText(title, style = SnTypography.headingMd.copy(color = SnColors.fg))
            BasicText(
                stringResource(Res.string.player_incomplete_body, clockText(durationSeconds)),
                style = SnTypography.body.copy(color = SnColors.fgSecondary),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FillingButton(
                text = stringResource(Res.string.player_incomplete_discard),
                fill = fill,
                onClick = onDiscard,
                modifier = Modifier.weight(1f),
            )
            OutlineButton(stringResource(Res.string.player_incomplete_save), onSave)
        }
        BasicText(
            stringResource(Res.string.player_incomplete_default_in, remaining),
            style = SnTypography.caption.copy(color = SnColors.fgSecondary),
        )
    }
}

/**
 * The provider (a debrid service) is downloading the source: the title's logo with what is
 * happening, and a way to pick another source meanwhile. The video starts by itself once ready.
 */
@Composable
internal fun ProviderDownloading(
    logo: String?,
    title: String,
    onChangeSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.padding(SnSpacing.space8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space6),
    ) {
        SnLogoLoader(
            logo = logo,
            title = title,
            progress = null,
            status = stringResource(Res.string.player_provider_downloading),
            label = stringResource(Res.string.player_provider_downloading),
        )
        BasicText(
            stringResource(Res.string.player_provider_downloading_body),
            style = SnTypography.body.copy(color = SnColors.fgSecondary, textAlign = TextAlign.Center),
        )
        SnButton(
            stringResource(Res.string.player_choose_other_source),
            onChangeSource,
            style = SnButtonStyle.Secondary,
        )
    }
}

/** Width of the corner prompts: the next episode's card and the HDR question. */
private val PROMPT_WIDTH = 400.dp
