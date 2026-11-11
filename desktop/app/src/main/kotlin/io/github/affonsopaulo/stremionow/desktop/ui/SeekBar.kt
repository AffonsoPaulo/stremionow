// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp

/**
 * Playback position bar: played, buffered and remaining parts with a thumb. Click to seek, or
 * drag (the thumb follows the pointer and the seek happens on release). Desktop port of the
 * Android `SnSeekBar`; keyboard seeking is handled by the window (Left and Right).
 */
@Composable
internal fun SeekBar(
    positionSeconds: Double,
    durationSeconds: Double,
    onSeek: (seconds: Double) -> Unit,
    modifier: Modifier = Modifier,
    onScrub: (seconds: Double?) -> Unit = {},
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val duration = durationSeconds.coerceAtLeast(0.0)
    val played = dragFraction ?: if (duration > 0) (positionSeconds / duration).toFloat().coerceIn(0f, 1f) else 0f
    val active = hovered || dragFraction != null

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .height(32.dp)
                .hoverable(interaction)
                .pointerHoverIcon(PointerIcon.Hand)
                .semantics {
                    contentDescription = "Playback position"
                    progressBarRangeInfo = ProgressBarRangeInfo(played, 0f..1f)
                    setProgress { target ->
                        onSeek(target.coerceIn(0f, 1f) * duration)
                        true
                    }
                },
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
        val thickness = if (active) 6.dp else 4.dp
        Box(Modifier.fillMaxWidth().height(thickness).background(Colors.progressTrack, track))
        Box(Modifier.fillMaxWidth(played).height(thickness).background(Colors.accent, track))
        val thumb = if (active) 16.dp else 12.dp
        Box(
            Modifier
                .offset(x = maxWidth * played - thumb / 2)
                .size(thumb)
                .background(Colors.fg, CircleShape),
        )
    }
}

/** Formats seconds as `m:ss` or `h:mm:ss`. */
internal fun formatPlaybackTime(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toLong()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%d:%02d".format(minutes, secs)
}
