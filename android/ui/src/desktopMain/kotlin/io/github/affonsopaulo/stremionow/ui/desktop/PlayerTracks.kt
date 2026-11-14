// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.affonsopaulo.stremionow.player.PlaybackState
import io.github.affonsopaulo.stremionow.player.Track
import io.github.affonsopaulo.stremionow.player.VideoPlayer
import io.github.affonsopaulo.stremionow.ui.components.SnIconButton
import io.github.affonsopaulo.stremionow.ui.components.collectSnInteraction
import io.github.affonsopaulo.stremionow.ui.components.snClickable
import io.github.affonsopaulo.stremionow.ui.components.snStateBackground
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.screens.trackLabel
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Step of the subtitle sync buttons, in seconds. */
private const val SYNC_STEP_SECONDS = 0.1

/** Step of the subtitle position buttons, in percent of the picture's height. */
private const val POSITION_STEP = 5

/** Subtitle sizes offered: small, medium (normal) and large. */
internal enum class SubtitleSize(
    val scale: Double,
) {
    Small(0.8),
    Medium(1.0),
    Large(1.25),
    ;

    companion object {
        /** The offered size nearest to [scale]. */
        fun nearest(scale: Double): SubtitleSize = entries.minBy { abs(it.scale - scale) }
    }
}

/**
 * Subtitle size and position chosen in this run of the app, so the next video starts with
 * them. Their delay belongs to each file and is not carried over.
 */
internal object SessionSubtitles {
    var scale = SubtitleSize.Medium.scale
        private set
    var position = 100
        private set

    fun setScale(
        player: VideoPlayer,
        scale: Double,
    ) {
        this.scale = scale
        player.setSubtitleScale(scale)
    }

    fun setPosition(
        player: VideoPlayer,
        percent: Int,
    ) {
        position = percent.coerceIn(MIN_POSITION, 100)
        player.setSubtitlePosition(position)
    }

    /** Gives a new player the size and position of the previous one. */
    fun applyTo(player: VideoPlayer) {
        player.setSubtitleScale(scale)
        player.setSubtitlePosition(position)
    }

    /** The highest subtitles go: a third of the way down the picture. */
    const val MIN_POSITION = 30
}

/** Formats a subtitle delay as "+0.5 s", "−1.2 s" or "0.0 s", in the device's number format. */
internal fun formatSubtitleDelay(seconds: Double): String {
    val tenths = (seconds * 10).roundToInt()
    val magnitude = String.format(Locale.getDefault(), "%.1f", abs(tenths) / 10.0)
    return when {
        tenths > 0 -> "+$magnitude s"
        tenths < 0 -> "−$magnitude s"
        else -> "$magnitude s"
    }
}

/**
 * The subtitles and audio panel, above the controls: an Audio column and a Subtitles column of
 * choices, then the subtitles' sync, size and position.
 */
@Composable
internal fun TracksPanel(
    player: VideoPlayer,
    playback: PlaybackState,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(SnRadius.radiusLg)
    val audio = playback.tracks.filter { it.type == Track.Type.Audio }
    val subtitles = playback.tracks.filter { it.type == Track.Type.Subtitle }
    val title = stringResource(Res.string.player_tracks)
    Column(
        modifier
            .widthIn(max = 600.dp)
            .semantics { contentDescription = title }
            .shadow(24.dp, shape)
            .background(SnColors.surfaceRaised, shape)
            .border(1.dp, SnColors.border, shape),
    ) {
        Row(Modifier.fillMaxWidth().padding(SnSpacing.space4)) {
            TrackColumn(
                heading = stringResource(Res.string.player_audio),
                modifier = Modifier.weight(1f),
            ) {
                items(audio, key = { "a${it.id}" }) { track ->
                    TrackChoice(
                        trackLabel(track),
                        audioDetail(track),
                        track.isSelected,
                    ) { player.selectAudio(track.id) }
                }
            }
            TrackColumn(
                heading = stringResource(Res.string.player_subtitles),
                modifier = Modifier.weight(1f),
            ) {
                item(key = "off") {
                    TrackChoice(
                        stringResource(Res.string.player_subtitles_off),
                        null,
                        subtitles.none { it.isSelected },
                    ) {
                        player.selectSubtitle(null)
                    }
                }
                items(subtitles, key = { "s${it.id}" }) { track ->
                    TrackChoice(trackLabel(track), subtitleDetail(track), track.isSelected) {
                        player.selectSubtitle(track.id)
                    }
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = SnSpacing.space4,
                ).background(SnColors.border)
                .heightIn(min = 1.dp, max = 1.dp),
        )
        SubtitleOptions(player, playback, Modifier.padding(SnSpacing.space4))
    }
}

@Composable
private fun TrackColumn(
    heading: String,
    modifier: Modifier = Modifier,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(SnSpacing.space1)) {
        BasicText(
            heading.uppercase(),
            modifier =
                Modifier
                    .padding(
                        horizontal = SnSpacing.space3,
                        vertical = SnSpacing.space1,
                    ).semantics { heading() },
            style = SnTypography.overline.copy(color = SnColors.fgMuted),
        )
        LazyColumn(
            Modifier.heightIn(max = 280.dp),
            verticalArrangement = Arrangement.spacedBy(SnSpacing.space1),
            content = content,
        )
    }
}

/** One choice: a check when selected, the label and, below it, where the track comes from. */
@Composable
private fun TrackChoice(
    label: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(SnRadius.radiusMd)
    val interaction = remember { MutableInteractionSource() }
    val state = interaction.collectSnInteraction()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .semantics { this.selected = selected }
            .snClickable(
                shape,
                role = Role.RadioButton,
                highlight = false,
                interactionSource = interaction,
                onClick = onClick,
            ).snStateBackground(if (selected) SnColors.surfaceMuted else Color.Transparent, shape, state)
            .padding(horizontal = SnSpacing.space3, vertical = SnSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space2),
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                Image(
                    SnIcons.Check,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    colorFilter = ColorFilter.tint(SnColors.accentText),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(
                label,
                style =
                    SnTypography.body.copy(
                        color = SnColors.fg,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!detail.isNullOrBlank()) {
                BasicText(
                    detail,
                    style = SnTypography.caption.copy(color = SnColors.fgMuted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** An audio track's title (often "Original" or "Dubbed"), else its codec. */
private fun audioDetail(track: Track): String? =
    track.title?.takeIf { it.isNotBlank() && it != track.language } ?: track.codec?.uppercase()

/** Where a subtitle comes from: an addon's name for external ones, the file for embedded ones. */
@Composable
private fun subtitleDetail(track: Track): String =
    if (track.isExternal) {
        track.title?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.player_track_external)
    } else {
        stringResource(Res.string.player_track_embedded)
    }

/** Sync (−/+ 0.1 s), size (S/M/L) and vertical position (up/down) of the subtitles. */
@Composable
private fun SubtitleOptions(
    player: VideoPlayer,
    playback: PlaybackState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        OptionGroup(stringResource(Res.string.player_sub_sync)) {
            val delay = playback.subtitleDelaySeconds
            SnIconButton(
                SnIcons.Minus,
                stringResource(Res.string.player_sub_earlier),
                { player.setSubtitleDelay(delay - SYNC_STEP_SECONDS) },
                background = SnColors.surfaceMuted,
            )
            BasicText(
                formatSubtitleDelay(delay),
                modifier = Modifier.widthIn(min = 56.dp),
                style =
                    SnTypography.label.copy(
                        color = SnColors.fg,
                        fontFeatureSettings = "tnum",
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    ),
            )
            SnIconButton(
                SnIcons.Plus,
                stringResource(Res.string.player_sub_later),
                { player.setSubtitleDelay(delay + SYNC_STEP_SECONDS) },
                background = SnColors.surfaceMuted,
            )
        }
        OptionGroup(stringResource(Res.string.player_sub_size)) {
            val current = SubtitleSize.nearest(playback.subtitleScale)
            Row(
                Modifier
                    .background(SnColors.surfaceMuted, RoundedCornerShape(SnRadius.radiusMd))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(SnSpacing.space1),
            ) {
                for (size in SubtitleSize.entries) {
                    SizeChoice(size, selected = size == current) { SessionSubtitles.setScale(player, size.scale) }
                }
            }
        }
        OptionGroup(stringResource(Res.string.player_sub_position)) {
            val position = playback.subtitlePosition
            SnIconButton(
                SnIcons.ChevronUp,
                stringResource(Res.string.player_sub_up),
                { SessionSubtitles.setPosition(player, position - POSITION_STEP) },
                background = SnColors.surfaceMuted,
                enabled = position > SessionSubtitles.MIN_POSITION,
            )
            SnIconButton(
                SnIcons.ChevronDown,
                stringResource(Res.string.player_sub_down),
                { SessionSubtitles.setPosition(player, position + POSITION_STEP) },
                background = SnColors.surfaceMuted,
                enabled = position < 100,
            )
        }
    }
}

@Composable
private fun OptionGroup(
    label: String,
    content: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space2),
    ) {
        BasicText(label, style = SnTypography.label.copy(color = SnColors.fgSecondary))
        content()
    }
}

@Composable
private fun SizeChoice(
    size: SubtitleSize,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(SnRadius.radiusSm)
    val interaction = remember { MutableInteractionSource() }
    val state = interaction.collectSnInteraction()
    val (letter, name) =
        when (size) {
            SubtitleSize.Small -> {
                stringResource(Res.string.player_sub_size_small_short) to
                    stringResource(Res.string.player_sub_size_small)
            }

            SubtitleSize.Medium -> {
                stringResource(Res.string.player_sub_size_medium_short) to
                    stringResource(Res.string.player_sub_size_medium)
            }

            SubtitleSize.Large -> {
                stringResource(Res.string.player_sub_size_large_short) to
                    stringResource(Res.string.player_sub_size_large)
            }
        }
    Box(
        Modifier
            .heightIn(min = 36.dp)
            .widthIn(min = 36.dp)
            .semantics {
                contentDescription = name
                this.selected = selected
            }.snClickable(
                shape,
                role = Role.RadioButton,
                highlight = false,
                interactionSource = interaction,
                onClick = onClick,
            ).snStateBackground(if (selected) SnColors.fg else Color.Transparent, shape, state)
            .padding(horizontal = SnSpacing.space3),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            letter,
            style =
                SnTypography.label.copy(
                    color = if (selected) SnColors.bg else SnColors.fgSecondary,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                ),
        )
    }
}
