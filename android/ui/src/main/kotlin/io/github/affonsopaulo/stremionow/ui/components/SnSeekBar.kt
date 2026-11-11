// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import io.github.affonsopaulo.stremionow.ui.theme.SnColors

/**
 * Playback position bar: played, buffered and remaining parts with a thumb. Tap or drag to
 * seek (the position follows the finger and is applied on release); on a remote, focus it and
 * press left or right to step by [keyStepSeconds]. Screen readers can set the position too.
 */
@Composable
fun SnSeekBar(
    positionSeconds: Double,
    durationSeconds: Double,
    bufferedSeconds: Double,
    label: String,
    onSeek: (seconds: Double) -> Unit,
    modifier: Modifier = Modifier,
    onScrub: (seconds: Double?) -> Unit = {},
    keyStepSeconds: Double = 10.0,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val duration = durationSeconds.coerceAtLeast(0.0)
    val played = dragFraction ?: if (duration > 0) (positionSeconds / duration).toFloat().coerceIn(0f, 1f) else 0f
    val buffered = if (duration > 0) ((positionSeconds + bufferedSeconds) / duration).toFloat().coerceIn(0f, 1f) else 0f

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .height(32.dp)
                .semantics {
                    contentDescription = label
                    progressBarRangeInfo = ProgressBarRangeInfo(played, 0f..1f)
                    setProgress { target ->
                        onSeek(target.coerceIn(0f, 1f) * duration)
                        true
                    }
                }.onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || duration <= 0) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> onSeek((positionSeconds - keyStepSeconds).coerceAtLeast(0.0))
                        Key.DirectionRight -> onSeek((positionSeconds + keyStepSeconds).coerceAtMost(duration))
                        else -> return@onKeyEvent false
                    }
                    true
                }.focusable(interactionSource = interaction),
        contentAlignment = Alignment.CenterStart,
    ) {
        val width = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val fractionAt = { x: Float -> (x / width).coerceIn(0f, 1f) }
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(duration) {
                    detectTapGestures { offset -> if (duration > 0) onSeek(fractionAt(offset.x) * duration) }
                }.pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragFraction = fractionAt(offset.x)
                            onScrub(fractionAt(offset.x) * duration)
                        },
                        onDragEnd = {
                            dragFraction?.let { onSeek(it * duration) }
                            dragFraction = null
                            onScrub(null)
                        },
                        onDragCancel = {
                            dragFraction = null
                            onScrub(null)
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            dragFraction = fractionAt(change.position.x)
                            onScrub(fractionAt(change.position.x) * duration)
                        },
                    )
                },
        )
        val track = RoundedCornerShape(2.dp)
        val thick = if (focused || dragFraction != null) 6.dp else 4.dp
        Box(Modifier.fillMaxWidth().height(thick).background(SnColors.progressTrack, track))
        Box(Modifier.fillMaxWidth(buffered).height(thick).background(SnColors.progressBuffer, track))
        Box(Modifier.fillMaxWidth(played).height(thick).background(SnColors.accent, track))
        val thumb = if (focused || dragFraction != null) 18.dp else 14.dp
        Box(
            Modifier
                .offset(x = maxWidth * played - thumb / 2)
                .size(thumb)
                .background(SnColors.fg, CircleShape),
        )
    }
}

/** Formats seconds as `m:ss` or `h:mm:ss`. */
fun formatPlaybackTime(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toLong()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%d:%02d".format(minutes, secs)
}
