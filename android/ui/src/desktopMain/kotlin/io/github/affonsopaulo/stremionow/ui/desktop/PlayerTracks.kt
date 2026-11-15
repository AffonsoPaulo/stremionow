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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.affonsopaulo.stremionow.player.PlaybackState
import io.github.affonsopaulo.stremionow.player.SubtitleStyle
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
private const val POSITION_STEP = SubtitleStyle.POSITION_STEP

/** Step of the subtitle size buttons, and the sizes allowed, in percent of the normal size. */
private const val SCALE_STEP_PERCENT = SubtitleStyle.SIZE_STEP
private val MIN_SCALE_PERCENT = SubtitleStyle.SIZE_RANGE.first
private val MAX_SCALE_PERCENT = SubtitleStyle.SIZE_RANGE.last

/** A subtitle scale as a whole percentage, on the buttons' 10 % steps. */
internal fun subtitleScalePercent(scale: Double): Int =
    ((scale * 100 / SCALE_STEP_PERCENT).roundToInt() * SCALE_STEP_PERCENT).coerceIn(
        MIN_SCALE_PERCENT,
        MAX_SCALE_PERCENT,
    )

/**
 * Subtitle size and position chosen with the player's buttons in this run of the app, so the
 * next video starts with them rather than with Settings'. Changing them in Settings afterwards
 * wins: the next video starts with the new settings. Their delay belongs to each file and is
 * not carried over.
 */
internal object SessionSubtitles {
    private var scale: Double? = null
    private var position: Int? = null

    /** The settings' size and position when the last player started. */
    private var settings: Pair<Int, Int>? = null

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
        val position = percent.coerceIn(SubtitleStyle.POSITION_RANGE)
        this.position = position
        player.setSubtitlePosition(position)
    }

    /**
     * Gives a new player the size and position chosen in the previous one, unless [style]
     * (the settings it started with) changed since; it already has the settings' own.
     */
    fun applyTo(
        player: VideoPlayer,
        style: SubtitleStyle,
    ) {
        val current = style.sizePercent to style.position
        if (current != settings) {
            settings = current
            scale = null
            position = null
        }
        scale?.let(player::setSubtitleScale)
        position?.let(player::setSubtitlePosition)
    }

    /** The highest subtitles go: a third of the way down the picture. */
    val MIN_POSITION = SubtitleStyle.POSITION_RANGE.first
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
 * choices, then the subtitles' sync, size and position. Choices go through [onSelectAudio] and
 * [onSelectSubtitle], so the player's model can remember the viewer's picks for the title.
 */
@Composable
internal fun TracksPanel(
    player: VideoPlayer,
    playback: PlaybackState,
    modifier: Modifier = Modifier,
    onSelectAudio: (Int?) -> Unit = player::selectAudio,
    onSelectSubtitle: (Int?) -> Unit = player::selectSubtitle,
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
        Row(
            Modifier.fillMaxWidth().padding(SnSpacing.space4),
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space4),
        ) {
            TrackColumn(
                heading = stringResource(Res.string.player_audio),
                modifier = Modifier.weight(1f),
            ) {
                items(audio, key = { "a${it.id}" }) { track ->
                    TrackChoice(
                        trackLabel(track),
                        audioDetail(track),
                        track.isSelected,
                    ) { onSelectAudio(track.id) }
                }
            }
            // A line between the columns, drawn along the Subtitles column's start: measuring
            // the row's height for a divider would ask lazy lists for intrinsic sizes.
            val line = SnColors.border
            TrackColumn(
                heading = stringResource(Res.string.player_subtitles),
                modifier =
                    Modifier
                        .weight(1f)
                        .drawBehind {
                            drawLine(line, Offset.Zero, Offset(0f, size.height), strokeWidth = 1.dp.toPx())
                        }.padding(start = SnSpacing.space4),
            ) {
                item(key = "off") {
                    TrackChoice(
                        stringResource(Res.string.player_subtitles_off),
                        null,
                        subtitles.none { it.isSelected },
                    ) {
                        onSelectSubtitle(null)
                    }
                }
                items(subtitles, key = { "s${it.id}" }) { track ->
                    TrackChoice(trackLabel(track), subtitleDetail(track), track.isSelected) {
                        onSelectSubtitle(track.id)
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
        SubtitleOptions(player, playback, Modifier.padding(horizontal = SnSpacing.space6, vertical = SnSpacing.space4))
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
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space8),
    ) {
        OptionGroup(stringResource(Res.string.player_sub_sync)) {
            val delay = playback.subtitleDelaySeconds
            Stepper(
                value = formatSubtitleDelay(delay),
                lessLabel = stringResource(Res.string.player_sub_earlier),
                moreLabel = stringResource(Res.string.player_sub_later),
                onLess = { player.setSubtitleDelay(delay - SYNC_STEP_SECONDS) },
                onMore = { player.setSubtitleDelay(delay + SYNC_STEP_SECONDS) },
            )
        }
        OptionGroup(stringResource(Res.string.player_sub_size)) {
            val percent = subtitleScalePercent(playback.subtitleScale)
            Stepper(
                value = "$percent%",
                lessLabel = stringResource(Res.string.player_sub_smaller),
                moreLabel = stringResource(Res.string.player_sub_larger),
                onLess = { SessionSubtitles.setScale(player, (percent - SCALE_STEP_PERCENT) / 100.0) },
                onMore = { SessionSubtitles.setScale(player, (percent + SCALE_STEP_PERCENT) / 100.0) },
                lessEnabled = percent > MIN_SCALE_PERCENT,
                moreEnabled = percent < MAX_SCALE_PERCENT,
            )
        }
        OptionGroup(stringResource(Res.string.player_sub_position)) {
            val position = playback.subtitlePosition
            // Shown as the height above the bottom, as Stremio does: 0% where subtitles normally
            // are, more as they go up.
            Stepper(
                value = "${100 - position}%",
                lessLabel = stringResource(Res.string.player_sub_down),
                moreLabel = stringResource(Res.string.player_sub_up),
                onLess = { SessionSubtitles.setPosition(player, position + POSITION_STEP) },
                onMore = { SessionSubtitles.setPosition(player, position - POSITION_STEP) },
                lessEnabled = position < 100,
                moreEnabled = position > SessionSubtitles.MIN_POSITION,
            )
        }
    }
}

/** A value between a minus and a plus button, in one pill. */
@Composable
private fun Stepper(
    value: String,
    lessLabel: String,
    moreLabel: String,
    onLess: () -> Unit,
    onMore: () -> Unit,
    lessEnabled: Boolean = true,
    moreEnabled: Boolean = true,
) {
    Row(
        Modifier
            .background(SnColors.surfaceMuted, RoundedCornerShape(SnRadius.radiusFull))
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SnIconButton(SnIcons.Minus, lessLabel, onLess, enabled = lessEnabled)
        BasicText(
            value,
            modifier = Modifier.widthIn(min = 64.dp),
            style =
                SnTypography.label.copy(
                    color = SnColors.fg,
                    fontFeatureSettings = "tnum",
                    textAlign = TextAlign.Center,
                ),
        )
        SnIconButton(SnIcons.Plus, moreLabel, onMore, enabled = moreEnabled)
    }
}

@Composable
private fun OptionGroup(
    label: String,
    content: @Composable () -> Unit,
) {
    // The label above its controls, so each group reads as one and the groups stay apart.
    Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space2)) {
        BasicText(
            label.uppercase(),
            modifier = Modifier.semantics { heading() },
            style = SnTypography.overline.copy(color = SnColors.fgMuted),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space2),
        ) {
            content()
        }
    }
}
