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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography
import kotlin.math.roundToInt

/**
 * Playback position bar: played, buffered and remaining parts with a thumb. Tap or drag to
 * seek (the position follows the finger and is applied on release); on a remote, focus it and
 * press left or right to step by [keyStepSeconds]. Screen readers can set the position too.
 *
 * [highlights] mark stretches of the video, such as the intro, in the warning colour. With
 * [showsHoverTime], for pointers: the hand cursor shows over the bar and the time at the point
 * under it shows above it.
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
    highlights: List<ClosedFloatingPointRange<Double>> = emptyList(),
    showsHoverTime: Boolean = false,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var hoverX by remember { mutableStateOf<Float?>(null) }
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
                .then(if (showsHoverTime) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
                .pointerInput(showsHoverTime) {
                    if (!showsHoverTime) return@pointerInput
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            hoverX =
                                when (event.type) {
                                    PointerEventType.Exit -> {
                                        null
                                    }

                                    else -> {
                                        event.changes
                                            .firstOrNull()
                                            ?.position
                                            ?.x ?: hoverX
                                    }
                                }
                        }
                    }
                }.pointerInput(duration) {
                    // The bar's width at the time of the gesture: it changes with the window and
                    // with the width of the time labels beside it.
                    val at = { x: Float -> (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f) }
                    detectTapGestures { offset -> if (duration > 0) onSeek(at(offset.x) * duration) }
                }.pointerInput(duration) {
                    val at = { x: Float -> (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f) }
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragFraction = at(offset.x)
                            onScrub(at(offset.x) * duration)
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
                            dragFraction = at(change.position.x)
                            onScrub(at(change.position.x) * duration)
                        },
                    )
                },
        )
        val track = RoundedCornerShape(2.dp)
        val thick = if (focused || dragFraction != null) 6.dp else 4.dp
        Box(Modifier.fillMaxWidth().height(thick).background(SnColors.progressTrack, track))
        Box(Modifier.fillMaxWidth(buffered).height(thick).background(SnColors.progressBuffer, track))
        if (duration > 0) {
            for (range in highlights) {
                val start = (range.start / duration).toFloat().coerceIn(0f, 1f)
                val end = (range.endInclusive / duration).toFloat().coerceIn(0f, 1f)
                if (end > start) {
                    Box(
                        Modifier
                            .offset(x = maxWidth * start)
                            .width(maxWidth * (end - start))
                            .height(thick)
                            .background(SnColors.warning),
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth(played).height(thick).background(SnColors.accent, track))
        val thumb = if (focused || dragFraction != null) 18.dp else 14.dp
        Box(
            Modifier
                .offset(x = maxWidth * played - thumb / 2)
                .size(thumb)
                .background(SnColors.fg, CircleShape),
        )
        val hovered = hoverX
        if (hovered != null && duration > 0 && dragFraction == null) {
            val gap = with(LocalDensity.current) { SnSpacing.space2.roundToPx() }
            Popup(popupPositionProvider = remember(hovered, gap) { AboveAt(hovered.roundToInt(), gap) }) {
                BasicText(
                    formatPlaybackTime(fractionAt(hovered) * duration),
                    modifier =
                        Modifier
                            .background(SnColors.surfaceRaised, RoundedCornerShape(SnRadius.radiusSm))
                            .padding(horizontal = SnSpacing.space2, vertical = SnSpacing.space1),
                    style = SnTypography.label.copy(color = SnColors.fg),
                )
            }
        }
    }
}

/** Places a popup centred over [x] (from the anchor's left edge), [gap] above the anchor, inside the window. */
private class AboveAt(
    private val x: Int,
    private val gap: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxLeft = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val left = (anchorBounds.left + x - popupContentSize.width / 2).coerceIn(0, maxLeft)
        return IntOffset(left, anchorBounds.top - popupContentSize.height - gap)
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
