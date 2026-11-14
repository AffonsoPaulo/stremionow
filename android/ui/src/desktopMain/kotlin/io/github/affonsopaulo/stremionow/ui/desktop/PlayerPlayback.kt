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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.affonsopaulo.stremionow.player.PlaybackState
import io.github.affonsopaulo.stremionow.player.VideoPlayer
import io.github.affonsopaulo.stremionow.player.VideoScale
import io.github.affonsopaulo.stremionow.ui.components.collectSnInteraction
import io.github.affonsopaulo.stremionow.ui.components.snClickable
import io.github.affonsopaulo.stremionow.ui.components.snStateBackground
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography
import java.util.Locale
import kotlin.math.abs

/** Playback rates offered, normal included. */
internal val PLAYBACK_SPEEDS = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)

/** Formats a rate as "1×", "0.75×" or "1.5×", in the device's number format. */
internal fun formatSpeed(speed: Double): String {
    val text = String.format(Locale.getDefault(), "%.2f", speed).trimEnd('0').trimEnd('.', ',')
    return "$text×"
}

/**
 * How the picture fills the screen, chosen in this run of the app, so the next video starts
 * the same. The speed is not carried over: each title starts at normal speed.
 */
internal object SessionPicture {
    var scale = VideoScale.Fit
        private set

    fun set(
        player: VideoPlayer,
        scale: VideoScale,
    ) {
        this.scale = scale
        player.setVideoScale(scale)
    }

    fun applyTo(player: VideoPlayer) {
        if (scale != VideoScale.Fit) player.setVideoScale(scale)
    }
}

/** The playback panel, above the controls: the speed, and how the picture fills the screen. */
@Composable
internal fun PlaybackPanel(
    player: VideoPlayer,
    playback: PlaybackState,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(SnRadius.radiusLg)
    val title = stringResource(Res.string.player_playback)
    Column(
        modifier
            .width(400.dp)
            .semantics { contentDescription = title }
            .shadow(24.dp, shape)
            .background(SnColors.surfaceRaised, shape)
            .border(1.dp, SnColors.border, shape)
            .padding(SnSpacing.space6),
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space6),
    ) {
        Section(stringResource(Res.string.player_speed)) {
            Row(
                Modifier
                    .background(SnColors.surfaceMuted, RoundedCornerShape(SnRadius.radiusMd))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(SnSpacing.space1),
            ) {
                for (speed in PLAYBACK_SPEEDS) {
                    SpeedChoice(speed, selected = abs(playback.speed - speed) < 0.01) { player.setSpeed(speed) }
                }
            }
        }
        Section(stringResource(Res.string.player_picture)) {
            Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space1)) {
                ScaleChoice(
                    SnIcons.PictureFit,
                    stringResource(Res.string.player_picture_fit),
                    stringResource(Res.string.player_picture_fit_body),
                    selected = playback.videoScale == VideoScale.Fit,
                ) { SessionPicture.set(player, VideoScale.Fit) }
                ScaleChoice(
                    SnIcons.PictureFill,
                    stringResource(Res.string.player_picture_fill),
                    stringResource(Res.string.player_picture_fill_body),
                    selected = playback.videoScale == VideoScale.Fill,
                ) { SessionPicture.set(player, VideoScale.Fill) }
                ScaleChoice(
                    SnIcons.PictureStretch,
                    stringResource(Res.string.player_picture_stretch),
                    stringResource(Res.string.player_picture_stretch_body),
                    selected = playback.videoScale == VideoScale.Stretch,
                ) { SessionPicture.set(player, VideoScale.Stretch) }
            }
        }
    }
}

@Composable
private fun Section(
    label: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space3)) {
        BasicText(
            label.uppercase(),
            modifier = Modifier.semantics { heading() },
            style = SnTypography.overline.copy(color = SnColors.fgMuted),
        )
        content()
    }
}

@Composable
private fun SpeedChoice(
    speed: Double,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(SnRadius.radiusSm)
    val interaction = remember { MutableInteractionSource() }
    val state = interaction.collectSnInteraction()
    Box(
        Modifier
            .heightIn(min = 36.dp)
            .widthIn(min = 52.dp)
            .semantics { this.selected = selected }
            .snClickable(
                shape,
                role = Role.RadioButton,
                highlight = false,
                interactionSource = interaction,
                onClick = onClick,
            ).snStateBackground(if (selected) SnColors.fg else Color.Transparent, shape, state)
            .padding(horizontal = SnSpacing.space2),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            formatSpeed(speed),
            style =
                SnTypography.label.copy(
                    color = if (selected) SnColors.bg else SnColors.fgSecondary,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    fontFeatureSettings = "tnum",
                ),
        )
    }
}

@Composable
private fun ScaleChoice(
    icon: ImageVector,
    label: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(SnRadius.radiusMd)
    val interaction = remember { MutableInteractionSource() }
    val state = interaction.collectSnInteraction()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
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
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
    ) {
        Image(
            icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            colorFilter = ColorFilter.tint(if (selected) SnColors.accentText else SnColors.fgSecondary),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(
                label,
                style =
                    SnTypography.body.copy(
                        color = SnColors.fg,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    ),
            )
            BasicText(detail, style = SnTypography.caption.copy(color = SnColors.fgMuted))
        }
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
    }
}
