// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import io.github.affonsopaulo.stremionow.player.PlaybackState
import io.github.affonsopaulo.stremionow.player.VideoPlayer
import io.github.affonsopaulo.stremionow.ui.components.SnIconButton
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography
import kotlinx.coroutines.delay

/** Step of the arrow keys and of one notch of the mouse wheel. */
internal const val VOLUME_STEP = 5

/**
 * The volume chosen in this run of the app, so the next video starts at it. Not saved: the app
 * starts at full volume, as mpv does.
 */
internal object SessionVolume {
    var percent = 100
        private set
    var isMuted = false
        private set

    /** Sets [player]'s volume and remembers it. */
    fun set(
        player: VideoPlayer,
        percent: Int,
    ) {
        this.percent = percent.coerceIn(0, 100)
        player.setVolume(this.percent)
        // Turning the volume up from silence means hearing it.
        if (isMuted && this.percent > 0) setMuted(player, false)
    }

    /** Mutes or unmutes [player] and remembers it. */
    fun setMuted(
        player: VideoPlayer,
        muted: Boolean,
    ) {
        isMuted = muted
        player.setMuted(muted)
    }

    /** Gives a new player the volume of the previous one. */
    fun applyTo(player: VideoPlayer) {
        player.setVolume(percent)
        player.setMuted(isMuted)
    }
}

/**
 * The volume control, in place in the controls row: the speaker button mutes and unmutes, and a
 * slider with the percentage slides out beside it while the pointer is over the control or the
 * keyboard focus is in it, and for a moment after the volume changes (arrow keys, M, the mouse
 * wheel), so a change made without looking shows. The mouse wheel over the control changes the
 * volume.
 */
@Composable
internal fun VolumeButton(
    player: VideoPlayer,
    playback: PlaybackState,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    var recentChange by remember { mutableStateOf(false) }
    var lingering by remember { mutableStateOf(false) }
    // Shows the slider for a moment when the volume changes, but not for the first value.
    var seen by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    LaunchedEffect(playback.volume, playback.isMuted) {
        val now = playback.volume to playback.isMuted
        val before = seen
        seen = now
        if (before != null && before != now) {
            recentChange = true
            delay(REVEAL_AFTER_CHANGE_MS)
            recentChange = false
        }
    }
    // Stays out briefly after the pointer leaves, so moving towards the slider never collapses it.
    LaunchedEffect(hovered) {
        if (hovered) {
            lingering = true
        } else {
            delay(COLLAPSE_DELAY_MS)
            lingering = false
        }
    }
    val expanded = hovered || lingering || focused || recentChange
    val silent = playback.isMuted || playback.volume == 0
    val shown = if (playback.isMuted) 0 else playback.volume
    val label = stringResource(Res.string.player_volume)
    val muteLabel = stringResource(if (playback.isMuted) Res.string.player_unmute else Res.string.player_mute)
    Row(
        Modifier
            .hoverable(interaction)
            .onFocusChanged { focused = it.hasFocus }
            .pointerInput(player) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            val notches =
                                event.changes
                                    .firstOrNull()
                                    ?.scrollDelta
                                    ?.y ?: 0f
                            if (notches != 0f) {
                                val step = if (notches < 0) VOLUME_STEP else -VOLUME_STEP
                                SessionVolume.set(player, player.state.value.volume + step)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WithTooltip(muteLabel, stringResource(Res.string.player_key_mute)) {
            SnIconButton(
                if (silent) SnIcons.VolumeMuted else SnIcons.Volume,
                muteLabel,
                { SessionVolume.setMuted(player, !playback.isMuted) },
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
            exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
        ) {
            Row(
                Modifier.padding(start = SnSpacing.space1, end = SnSpacing.space2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
            ) {
                VolumeSlider(volume = shown, label = label, onChange = { SessionVolume.set(player, it) })
                BasicText(
                    "$shown%",
                    modifier = Modifier.widthIn(min = 40.dp),
                    style = SnTypography.label.copy(color = SnColors.fg, fontFeatureSettings = "tnum"),
                )
            }
        }
    }
}

/** A horizontal slider from 0 to 100: click or drag to set, Left and Right to step when focused. */
@Composable
private fun VolumeSlider(
    volume: Int,
    label: String,
    onChange: (Int) -> Unit,
) {
    val fraction = volume.coerceIn(0, 100) / 100f
    val thumb = 14.dp
    BoxWithConstraints(
        Modifier
            .width(SLIDER_WIDTH)
            .height(28.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                setProgress { target ->
                    onChange((target.coerceIn(0f, 1f) * 100).toInt())
                    true
                }
            }.onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> onChange(volume - VOLUME_STEP)
                    Key.DirectionRight -> onChange(volume + VOLUME_STEP)
                    else -> return@onKeyEvent false
                }
                true
            }.focusable(),
        contentAlignment = Alignment.CenterStart,
    ) {
        // The thumb stays inside the slider: its centre runs from half a thumb in from one end
        // to half a thumb in from the other.
        val thumbPx = with(LocalDensity.current) { thumb.toPx() }
        val travel = (constraints.maxWidth.toFloat() - thumbPx).coerceAtLeast(1f)
        val at = { x: Float -> (((x - thumbPx / 2) / travel).coerceIn(0f, 1f) * 100).toInt() }
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(Unit) { detectTapGestures { onChange(at(it.x)) } }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        onChange(at(change.position.x))
                    }
                },
        )
        val track = RoundedCornerShape(2.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = thumb / 2)
                .height(4.dp)
                .background(SnColors.progressTrack, track),
        )
        Box(
            Modifier
                .padding(start = thumb / 2)
                .width((maxWidth - thumb) * fraction)
                .height(4.dp)
                .background(SnColors.accent, track),
        )
        Box(
            Modifier
                .offset(x = (maxWidth - thumb) * fraction)
                .size(thumb)
                .background(SnColors.fg, CircleShape),
        )
    }
}

/** How long the slider stays out after the volume changes without the pointer on it. */
private const val REVEAL_AFTER_CHANGE_MS = 1_500L

/** How long the slider stays out after the pointer leaves the control. */
private const val COLLAPSE_DELAY_MS = 600L

private val SLIDER_WIDTH = 120.dp
