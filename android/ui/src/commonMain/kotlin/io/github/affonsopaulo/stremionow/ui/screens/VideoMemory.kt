// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.affonsopaulo.stremionow.core.AppSettings
import io.github.affonsopaulo.stremionow.core.StreamMemoryChoice
import io.github.affonsopaulo.stremionow.player.StreamMemory
import io.github.affonsopaulo.stremionow.player.StreamMemorySizes
import io.github.affonsopaulo.stremionow.ui.components.SnButton
import io.github.affonsopaulo.stremionow.ui.components.SnButtonStyle
import io.github.affonsopaulo.stremionow.ui.components.SnTextField
import io.github.affonsopaulo.stremionow.ui.components.snClickable
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.StringResource
import io.github.affonsopaulo.stremionow.ui.l10n.pluralStringResource
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.theme.FormFactor
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.roundToInt

/** A 4K film streams at about this many bits per second. */
internal const val UHD_BITS_PER_SECOND = 25_000_000L

/** A 1080p film streams at about this many bits per second. */
internal const val HD_BITS_PER_SECOND = 5_000_000L

/** The level a choice names, or null for automatic and custom. */
internal fun StreamMemoryChoice.level(): StreamMemory? =
    when (this) {
        StreamMemoryChoice.LIGHT -> StreamMemory.LIGHT
        StreamMemoryChoice.BALANCED -> StreamMemory.BALANCED
        StreamMemoryChoice.SMOOTH -> StreamMemory.SMOOTH
        StreamMemoryChoice.AUTOMATIC, StreamMemoryChoice.CUSTOM -> null
    }

internal fun StreamMemory.choice(): StreamMemoryChoice =
    when (this) {
        StreamMemory.LIGHT -> StreamMemoryChoice.LIGHT
        StreamMemory.BALANCED -> StreamMemoryChoice.BALANCED
        StreamMemory.SMOOTH -> StreamMemoryChoice.SMOOTH
    }

/** The saved custom sizes, brought into the player's ranges (the core keeps them there too). */
internal fun AppSettings.customStreamMemory(): StreamMemorySizes =
    StreamMemorySizes(
        aheadMiB = streamMemoryAheadMib.toInt().coerceIn(StreamMemorySizes.AHEAD_RANGE),
        behindMiB = streamMemoryBehindMib.toInt().coerceIn(StreamMemorySizes.BEHIND_RANGE),
    )

/**
 * What the player is told for the user's choice: a level, exact sizes, or neither, when the
 * player picks from the device's memory.
 */
internal fun AppSettings?.streamMemoryOptions(): Pair<StreamMemory?, StreamMemorySizes?> =
    when (this?.streamMemory) {
        null, StreamMemoryChoice.AUTOMATIC -> null to null
        StreamMemoryChoice.CUSTOM -> null to customStreamMemory()
        else -> streamMemory.level() to null
    }

/** The sizes a choice stands for, with [automatic] the device's own level. */
internal fun AppSettings.effectiveStreamMemory(automatic: StreamMemory): StreamMemorySizes =
    when (streamMemory) {
        StreamMemoryChoice.CUSTOM -> customStreamMemory()
        StreamMemoryChoice.AUTOMATIC -> automatic.sizes
        else -> (streamMemory.level() ?: automatic).sizes
    }

/** The settings with exact sizes; sizes that match a level are saved as that level. */
internal fun AppSettings.withStreamMemory(sizes: StreamMemorySizes): AppSettings =
    copy(
        streamMemory = sizes.level?.choice() ?: StreamMemoryChoice.CUSTOM,
        streamMemoryAheadMib = sizes.aheadMiB.toUInt(),
        streamMemoryBehindMib = sizes.behindMiB.toUInt(),
    )

/** A duration shown roughly: a few seconds, tens of seconds, or whole minutes. */
internal sealed interface RoughDuration {
    data class Seconds(
        val value: Int,
    ) : RoughDuration

    data class Minutes(
        val value: Int,
    ) : RoughDuration
}

/**
 * [seconds] the way people say it: whole seconds under 10, then down to a multiple of 5 (43 s
 * is "about 40 s"), and whole minutes from one minute on.
 */
internal fun roughDuration(seconds: Double): RoughDuration =
    when {
        seconds < 10 -> RoughDuration.Seconds(seconds.roundToInt().coerceAtLeast(1))
        seconds < 60 -> RoughDuration.Seconds((floor(seconds / 5) * 5).toInt())
        else -> RoughDuration.Minutes((seconds / 60).roundToInt().coerceAtLeast(1))
    }

/** How long [mib] of video lasts at [bitsPerSecond]. */
internal fun playingSeconds(
    mib: Int,
    bitsPerSecond: Long,
): Double = mib * 1024.0 * 1024.0 * 8 / bitsPerSecond

/**
 * What a level keeps of what just played, as the line under it says: for a 4K film and for a
 * 1080p one. What is kept behind is what going back a few seconds needs, the case that used to
 * open the source again (see [StreamMemory]).
 */
internal fun StreamMemorySizes.keepsAbout(): Pair<RoughDuration, RoughDuration> =
    roughDuration(playingSeconds(behindMiB, UHD_BITS_PER_SECOND)) to
        roughDuration(playingSeconds(behindMiB, HD_BITS_PER_SECOND))

/** [text] as a size in MiB within [range], or null when it is not one. */
internal fun parseMiB(
    text: String,
    range: IntRange,
): Int? =
    text
        .trim()
        .takeIf { it.length in 1..5 && it.all(Char::isDigit) }
        ?.toInt()
        ?.takeIf { it in range }

@Composable
private fun RoughDuration.text(): String =
    when (this) {
        is RoughDuration.Seconds -> pluralStringResource(Res.plurals.settings_duration_seconds, value, value)
        is RoughDuration.Minutes -> pluralStringResource(Res.plurals.settings_duration_minutes, value, value)
    }

@Composable
private fun keepsLine(sizes: StreamMemorySizes): String {
    val (uhd, hd) = sizes.keepsAbout()
    return stringResource(Res.string.settings_memory_keeps, uhd.text(), hd.text())
}

private fun StreamMemory.title(): StringResource =
    when (this) {
        StreamMemory.LIGHT -> Res.string.settings_memory_light
        StreamMemory.BALANCED -> Res.string.settings_memory_balanced
        StreamMemory.SMOOTH -> Res.string.settings_memory_smooth
    }

private fun StreamMemory.body(): StringResource =
    when (this) {
        StreamMemory.LIGHT -> Res.string.settings_memory_light_body
        StreamMemory.BALANCED -> Res.string.settings_memory_balanced_body
        StreamMemory.SMOOTH -> Res.string.settings_memory_smooth_body
    }

/**
 * "Memory for the video": three cards that say what each level does, and the exact sizes under
 * "Advanced". [automatic] is the level the player picks for this device when the user has not
 * chosen; its card says so.
 */
@Composable
internal fun VideoMemorySetting(
    settings: AppSettings,
    automatic: StreamMemory,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
) {
    val chosen = settings.streamMemory
    val selected = if (chosen == StreamMemoryChoice.AUTOMATIC) automatic else chosen.level()
    Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space3)) {
        BasicText(stringResource(Res.string.settings_memory), style = SnTypography.bodyLg.copy(color = SnColors.fg))
        BasicText(
            stringResource(Res.string.settings_memory_body),
            style = SnTypography.body.copy(color = SnColors.fgMuted),
        )
        val cards = @Composable { modifier: Modifier ->
            for (level in StreamMemory.entries) {
                MemoryCard(
                    level = level,
                    selected = level == selected,
                    suggested = level == automatic,
                    onClick = { onChange { it.copy(streamMemory = level.choice()) } },
                    modifier = modifier,
                )
            }
        }
        if (SnLayout.formFactor == FormFactor.Phone) {
            Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space2)) { cards(Modifier.fillMaxWidth()) }
        } else {
            // One height for the three, so their lines start level.
            Row(
                Modifier.height(IntrinsicSize.Max).widthIn(max = 960.dp),
                horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
            ) { cards(Modifier.weight(1f).fillMaxHeight()) }
        }
        AdvancedSizes(settings, settings.effectiveStreamMemory(automatic), onChange)
    }
}

@Composable
private fun MemoryCard(
    level: StreamMemory,
    selected: Boolean,
    suggested: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(SnRadius.radiusLg)
    Column(
        modifier =
            modifier
                .semantics { this.selected = selected }
                .snClickable(shape, role = Role.RadioButton, onClick = onClick)
                .background(if (selected) SnColors.surfaceSelected else SnColors.surface, shape)
                .border(if (selected) 2.dp else 1.dp, if (selected) SnColors.accentBorder else SnColors.border, shape)
                .padding(SnSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space2),
    ) {
        BasicText(stringResource(level.title()), style = SnTypography.headingMd.copy(color = SnColors.fg))
        if (suggested) {
            BasicText(
                stringResource(Res.string.settings_memory_suggested),
                style = SnTypography.caption.copy(color = SnColors.accentText),
            )
        }
        BasicText(stringResource(level.body()), style = SnTypography.body.copy(color = SnColors.fgSecondary))
        BasicText(keepsLine(level.sizes), style = SnTypography.caption.copy(color = SnColors.fgMuted))
    }
}

/** The exact sizes in MiB. Changing them to sizes no level has makes the choice "Custom". */
@Composable
private fun AdvancedSizes(
    settings: AppSettings,
    effective: StreamMemorySizes,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
) {
    val custom = settings.streamMemory == StreamMemoryChoice.CUSTOM
    var open by rememberSaveable { mutableStateOf(custom) }
    Row(horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3)) {
        SnButton(
            stringResource(Res.string.settings_memory_advanced),
            { open = !open },
            style = SnButtonStyle.Ghost,
            icon = if (open) SnIcons.ChevronUp else SnIcons.ChevronDown,
        )
    }
    if (!open) return
    // The fields show the sizes in effect, and follow a card when one is chosen.
    var ahead by remember(effective.aheadMiB) { mutableStateOf(effective.aheadMiB.toString()) }
    var behind by remember(effective.behindMiB) { mutableStateOf(effective.behindMiB.toString()) }
    val aheadMiB = parseMiB(ahead, StreamMemorySizes.AHEAD_RANGE)
    val behindMiB = parseMiB(behind, StreamMemorySizes.BEHIND_RANGE)
    // Saved once typing pauses, not per keystroke: "1024" would otherwise save 102 first.
    val pending = pendingSizes(aheadMiB, behindMiB, effective)
    val latestPending by rememberUpdatedState(pending)
    val latestOnChange by rememberUpdatedState(onChange)
    LaunchedEffect(pending) {
        val sizes = pending ?: return@LaunchedEffect
        delay(SIZE_SAVE_DELAY_MS)
        onChange { it.withStreamMemory(sizes) }
    }
    // Closing the section or leaving the page within the pause still keeps what was typed.
    DisposableEffect(Unit) {
        onDispose { latestPending?.let { sizes -> latestOnChange { it.withStreamMemory(sizes) } } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space3)) {
        if (custom) {
            BasicText(
                stringResource(Res.string.settings_memory_custom),
                style = SnTypography.label.copy(color = SnColors.accentText),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(SnSpacing.space4)) {
            SizeField(
                label = stringResource(Res.string.settings_memory_ahead),
                value = ahead,
                range = StreamMemorySizes.AHEAD_RANGE,
                invalid = aheadMiB == null,
                onValueChange = { ahead = it },
                modifier = Modifier.weight(1f, fill = false).widthIn(max = 240.dp),
            )
            SizeField(
                label = stringResource(Res.string.settings_memory_behind),
                value = behind,
                range = StreamMemorySizes.BEHIND_RANGE,
                invalid = behindMiB == null,
                onValueChange = { behind = it },
                modifier = Modifier.weight(1f, fill = false).widthIn(max = 240.dp),
            )
        }
        if (aheadMiB != null && behindMiB != null) {
            BasicText(
                keepsLine(StreamMemorySizes(aheadMiB, behindMiB)),
                style = SnTypography.caption.copy(color = SnColors.fgMuted),
            )
        }
    }
}

/** How long typing in a size field pauses before the sizes are saved. */
private const val SIZE_SAVE_DELAY_MS = 600L

/** The sizes typed, when both are valid and differ from those in effect; otherwise null. */
internal fun pendingSizes(
    aheadMiB: Int?,
    behindMiB: Int?,
    effective: StreamMemorySizes,
): StreamMemorySizes? {
    if (aheadMiB == null || behindMiB == null) return null
    return StreamMemorySizes(aheadMiB, behindMiB).takeIf { it != effective }
}

@Composable
private fun SizeField(
    label: String,
    value: String,
    range: IntRange,
    invalid: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(SnSpacing.space2)) {
        BasicText(label, style = SnTypography.label.copy(color = SnColors.fgSecondary))
        SnTextField(
            value = value,
            onValueChange = { onValueChange(it.take(5)) },
            label = label,
            modifier = Modifier.fillMaxWidth(),
            isError = invalid,
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        )
        BasicText(
            stringResource(Res.string.settings_memory_range, range.first, range.last),
            style = SnTypography.caption.copy(color = if (invalid) SnColors.danger else SnColors.fgMuted),
        )
    }
}
