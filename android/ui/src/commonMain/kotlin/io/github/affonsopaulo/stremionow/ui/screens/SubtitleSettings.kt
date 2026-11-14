// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.affonsopaulo.stremionow.core.AppSettings
import io.github.affonsopaulo.stremionow.player.SubtitleStyle
import io.github.affonsopaulo.stremionow.ui.components.SnChipRow
import io.github.affonsopaulo.stremionow.ui.components.SnIconButton
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.StringResource
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography

/** The subtitle look saved in Settings, as the player takes it; null settings give the defaults. */
internal fun AppSettings?.subtitleStyle(): SubtitleStyle {
    if (this == null) return SubtitleStyle()
    return SubtitleStyle(
        sizePercent = subtitleSizePercent.toInt().coerceIn(SubtitleStyle.SIZE_RANGE),
        position = subtitlePosition.toInt().coerceIn(SubtitleStyle.POSITION_RANGE),
        textColor = subtitleTextColor.toInt(),
        backgroundColor = subtitleBackgroundColor.toInt(),
        outlineColor = subtitleOutlineColor.toInt(),
        useFileStyling = subtitleFileStyling,
    )
}

/** A colour offered for subtitles, by name: no free picker, which a TV remote would make tedious. */
internal data class SubtitleColor(
    val argb: Int,
    val name: StringResource,
)

/** Text colours: white (Stremio's), then the yellow, cyan and green that read well over film. */
internal val SubtitleTextColors =
    listOf(
        SubtitleColor(SubtitleStyle.WHITE, Res.string.settings_color_white),
        SubtitleColor(0xFFFFE600.toInt(), Res.string.settings_color_yellow),
        SubtitleColor(0xFF4DD9FF.toInt(), Res.string.settings_color_cyan),
        SubtitleColor(0xFF7CFC7C.toInt(), Res.string.settings_color_green),
    )

/** The box behind the text: none (Stremio's), translucent or solid black. */
internal val SubtitleBackgroundColors =
    listOf(
        SubtitleColor(SubtitleStyle.TRANSPARENT, Res.string.settings_color_none),
        SubtitleColor(0x99000000.toInt(), Res.string.settings_color_translucent_black),
        SubtitleColor(SubtitleStyle.BLACK, Res.string.settings_color_black),
    )

/** The outline: black (Stremio's) or none. */
internal val SubtitleOutlineColors =
    listOf(
        SubtitleColor(SubtitleStyle.BLACK, Res.string.settings_color_black),
        SubtitleColor(SubtitleStyle.TRANSPARENT, Res.string.settings_color_none),
    )

/** mpv draws subtitles at this size, and this outline, for a picture this many pixels high. */
private const val MPV_FONT_SIZE = 55f
private const val MPV_OUTLINE = 3f
private const val MPV_MARGIN = 22f
private const val MPV_REFERENCE_HEIGHT = 720f

/**
 * The subtitle look: a preview over a still picture, then size, position, the three colours and
 * whether styled subtitles keep their own look. [onChange] saves a change at once.
 */
@Composable
internal fun SubtitleStyleSetting(
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
) {
    val style = settings.subtitleStyle()
    Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space4)) {
        Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space1)) {
            BasicText(
                stringResource(Res.string.settings_sub_style),
                style = SnTypography.bodyLg.copy(color = SnColors.fg),
            )
            BasicText(
                stringResource(Res.string.settings_sub_style_body),
                style = SnTypography.body.copy(color = SnColors.fgMuted),
            )
        }
        SubtitlePreview(style, Modifier.widthIn(max = 560.dp).fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(SnSpacing.space8)) {
            Labelled(stringResource(Res.string.player_sub_size)) {
                val size = style.sizePercent
                Stepper(
                    value = "$size%",
                    lessLabel = stringResource(Res.string.player_sub_smaller),
                    moreLabel = stringResource(Res.string.player_sub_larger),
                    onLess = { onChange { it.copy(subtitleSizePercent = (size - SubtitleStyle.SIZE_STEP).toUInt()) } },
                    onMore = { onChange { it.copy(subtitleSizePercent = (size + SubtitleStyle.SIZE_STEP).toUInt()) } },
                    lessEnabled = size > SubtitleStyle.SIZE_RANGE.first,
                    moreEnabled = size < SubtitleStyle.SIZE_RANGE.last,
                )
            }
            Labelled(stringResource(Res.string.player_sub_position)) {
                val position = style.position
                // As the player shows it: the height above where subtitles normally are.
                Stepper(
                    value = "${100 - position}%",
                    lessLabel = stringResource(Res.string.player_sub_down),
                    moreLabel = stringResource(Res.string.player_sub_up),
                    onLess = {
                        onChange { it.copy(subtitlePosition = (position + SubtitleStyle.POSITION_STEP).toUInt()) }
                    },
                    onMore = {
                        onChange { it.copy(subtitlePosition = (position - SubtitleStyle.POSITION_STEP).toUInt()) }
                    },
                    lessEnabled = position < SubtitleStyle.POSITION_RANGE.last,
                    moreEnabled = position > SubtitleStyle.POSITION_RANGE.first,
                )
            }
        }
        ColorChoice(stringResource(Res.string.settings_sub_text_color), SubtitleTextColors, style.textColor) { argb ->
            onChange { it.copy(subtitleTextColor = argb.toUInt()) }
        }
        ColorChoice(
            stringResource(Res.string.settings_sub_background_color),
            SubtitleBackgroundColors,
            style.backgroundColor,
        ) { argb -> onChange { it.copy(subtitleBackgroundColor = argb.toUInt()) } }
        ColorChoice(
            stringResource(Res.string.settings_sub_outline_color),
            SubtitleOutlineColors,
            style.outlineColor,
        ) { argb -> onChange { it.copy(subtitleOutlineColor = argb.toUInt()) } }
        SettingsToggle(
            title = stringResource(Res.string.settings_sub_file_styling),
            body = stringResource(Res.string.settings_sub_file_styling_body),
            checked = settings.subtitleFileStyling,
            onToggle = { onChange { it.copy(subtitleFileStyling = !it.subtitleFileStyling) } },
        )
    }
}

/**
 * A line of subtitle over a still picture, drawn the way mpv draws it: sized to the picture's
 * height, raised by the position, with the outline and the box. Only a dark gradient, so the
 * page needs nothing from the network.
 */
@Composable
internal fun SubtitlePreview(
    style: SubtitleStyle,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(SnRadius.radiusLg)
    BoxWithConstraints(
        modifier
            .aspectRatio(16f / 9f)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    0f to Color(0xFF3B4A6B),
                    0.55f to Color(0xFF1C2333),
                    1f to Color(0xFF0B0E15),
                ),
            ).clearAndSetSemantics {},
    ) {
        val density = LocalDensity.current
        val heightPx = with(density) { maxHeight.toPx() }
        val unit = heightPx / MPV_REFERENCE_HEIGHT
        val fontSize = with(density) { (MPV_FONT_SIZE * unit * style.scale.toFloat()).toSp() }
        val outlineWidth = MPV_OUTLINE * unit * style.scale.toFloat() * 2
        val bottomMarginPx = MPV_MARGIN * unit
        val text = stringResource(Res.string.settings_sub_preview)
        val textStyle =
            TextStyle(
                fontSize = fontSize,
                lineHeight = fontSize * 1.2f,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        // A soft horizon, so it reads as a picture rather than a plain panel.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        listOf(Color(0x40E0A060), Color.Transparent),
                        center = Offset(heightPx * 1.2f, heightPx * 0.55f),
                        radius = heightPx * 0.6f,
                    ),
                ),
        )
        // The text's bottom at the position's share of the height, less mpv's margin.
        val raise = with(density) { (heightPx * (100 - style.position) / 100f + bottomMarginPx).toDp() }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = SnSpacing.space4, end = SnSpacing.space4, bottom = raise)
                .background(Color(style.backgroundColor))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            if ((style.outlineColor ushr 24) != 0) {
                BasicText(
                    text,
                    style =
                        textStyle.copy(
                            color = Color(style.outlineColor),
                            drawStyle = Stroke(width = outlineWidth, join = StrokeJoin.Round),
                        ),
                )
            }
            BasicText(text, style = textStyle.copy(color = Color(style.textColor)))
        }
    }
}

@Composable
private fun ColorChoice(
    label: String,
    colors: List<SubtitleColor>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Labelled(label) {
        SnChipRow(
            options = colors.map { it.argb },
            selected = selected,
            label = { argb -> colors.firstOrNull { it.argb == argb }?.let { stringResource(it.name) }.orEmpty() },
            onSelect = onSelect,
        )
    }
}

@Composable
private fun Labelled(
    label: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space2)) {
        BasicText(label, style = SnTypography.label.copy(color = SnColors.fgSecondary))
        content()
    }
}

/** A value between a minus and a plus button, in one pill, as in the player's subtitle options. */
@Composable
private fun Stepper(
    value: String,
    lessLabel: String,
    moreLabel: String,
    onLess: () -> Unit,
    onMore: () -> Unit,
    lessEnabled: Boolean,
    moreEnabled: Boolean,
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
