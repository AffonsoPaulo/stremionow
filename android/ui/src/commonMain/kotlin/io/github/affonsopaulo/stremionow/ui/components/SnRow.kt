// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.platform.rememberReducedMotion
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
import io.github.affonsopaulo.stremionow.ui.theme.SnSize
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * A row of cards that scrolls sideways, aligned to the page gutter. Every horizontal row of
 * cards in the app uses it.
 *
 * Touch screens swipe it and remotes and keyboards move through it card by card, as with
 * any list. A mouse can do neither, so where the pointer is the main input (the desktop):
 * while it is over the row, buttons at its edges page through it by its visible width, on
 * the sides where more cards are hidden; dragging moves it, with a fling; and the mouse wheel
 * with Shift, or a touchpad's sideways swipe, scrolls it. The buttons stay out of the focus
 * order, since keyboard focus already scrolls the row.
 *
 * [controlsCenter] is how far below the row's top its buttons are centered, usually the
 * middle of the cards' artwork; null centers them on the row. The buttons sit in the page
 * gutter; a row without one, such as a row of chips, sets [overContent], and its buttons
 * cover its ends over a fade of the page color instead.
 */
@Composable
fun SnRow(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(horizontal = SnLayout.gutter, vertical = SnSpacing.space2),
    spacing: Dp = SnSpacing.space4,
    controlsCenter: Dp? = null,
    overContent: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    if (!LocalSnFeedback.current.mouseFirst) {
        LazyRow(
            modifier = modifier,
            state = state,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
        return
    }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val scope = rememberCoroutineScope()
    val fling = ScrollableDefaults.flingBehavior()
    Box(modifier.hoverable(hover)) {
        LazyRow(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .mouseScrolling(state, LocalLayoutDirection.current == LayoutDirection.Rtl) { velocity ->
                        scope.launch { state.scroll { with(fling) { performFling(velocity) } } }
                    },
            state = state,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
        val align = { start: Boolean ->
            when {
                controlsCenter == null && start -> Alignment.CenterStart
                controlsCenter == null -> Alignment.CenterEnd
                start -> Alignment.TopStart
                else -> Alignment.TopEnd
            }
        }
        val offset = controlsCenter?.let { it - SnSize.controlMd / 2 } ?: 0.dp
        PageButton(
            visible = hovered && state.canScrollBackward,
            forward = false,
            fade = overContent,
            modifier = Modifier.align(align(true)).offset(y = offset),
            onClick = { scope.launch { state.page(forward = false) } },
        )
        PageButton(
            visible = hovered && state.canScrollForward,
            forward = true,
            fade = overContent,
            modifier = Modifier.align(align(false)).offset(y = offset),
            onClick = { scope.launch { state.page(forward = true) } },
        )
    }
}

/** Where a row of [SnEpisodeCard]s centers its buttons: the middle of the 16:9 thumbnails. */
val EpisodeRowControlsCenter: Dp = SnSpacing.space2 + EpisodeCardWidth * (9f / 16f) / 2

/**
 * Lets a mouse move the row: dragging it like a finger would, with a fling on release, and
 * the wheel with Shift held. Seen before the cards, so past the drag slop the card under the
 * pointer is not clicked. Touch keeps the list's own scrolling.
 */
private fun Modifier.mouseScrolling(
    state: LazyListState,
    rtl: Boolean,
    onFling: (velocity: Float) -> Unit,
): Modifier =
    pointerInput(state, rtl) {
        val direction = if (rtl) -1f else 1f
        val wheelStep = WheelStep.toPx()
        awaitPointerEventScope {
            var start: Offset? = null
            var dragging = false
            val tracker = VelocityTracker()
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                when {
                    event.type == PointerEventType.Scroll && event.keyboardModifiers.isShiftPressed -> {
                        // Shift turns the wheel sideways; a touchpad's sideways swipe already is.
                        val delta = change.scrollDelta
                        val notches = if (delta.x != 0f) delta.x else delta.y
                        state.dispatchRawDelta(notches * wheelStep * direction)
                        change.consume()
                    }

                    change.type != PointerType.Mouse -> {
                        continue
                    }

                    event.type == PointerEventType.Press && event.buttons.isPrimaryPressed -> {
                        start = change.position
                        dragging = false
                        tracker.resetTracking()
                        tracker.addPosition(change.uptimeMillis, change.position)
                    }

                    event.type == PointerEventType.Move && start != null -> {
                        val origin = start ?: continue
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!dragging &&
                            abs(change.position.x - origin.x) > viewConfiguration.touchSlop
                        ) {
                            dragging = true
                        }
                        if (dragging) {
                            state.dispatchRawDelta(-(change.position.x - change.previousPosition.x) * direction)
                            change.consume()
                        }
                    }

                    event.type == PointerEventType.Release && start != null -> {
                        if (dragging) {
                            change.consume()
                            onFling(-tracker.calculateVelocity().x * direction)
                        }
                        start = null
                        dragging = false
                    }
                }
            }
        }
    }

/** How far one notch of the wheel moves a row with Shift held. */
private val WheelStep = 120.dp

/** A row's Previous or Next button, centered in the page gutter. */
@Composable
private fun PageButton(
    visible: Boolean,
    forward: Boolean,
    fade: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(if (forward) Res.string.action_row_next else Res.string.action_row_previous)
    val interaction = remember { MutableInteractionSource() }
    val state = interaction.collectSnInteraction()
    val reducedMotion = rememberReducedMotion()
    // Over the cards the button needs a solid circle to show; the pointer makes it lighter.
    val circle by animateColorAsState(
        when {
            state.press -> SnColors.statePress.compositeOver(SnColors.surfaceSelected)
            state.hover -> SnColors.surfaceSelected
            else -> SnColors.scrimSoft
        },
        tween(FEEDBACK_MS),
        label = "page-button",
    )
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.width(if (fade) EdgeFadeWidth else SnLayout.gutter.coerceAtLeast(SnSize.controlMd)),
        enter = fadeIn(tween(PAGE_BUTTON_FADE_MS)),
        exit = fadeOut(tween(PAGE_BUTTON_FADE_MS)),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (fade) Modifier.edgeFade(forward) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(SnSize.controlMd)
                    .focusProperties { canFocus = false }
                    .semantics { contentDescription = label }
                    .snClickable(CircleShape, highlight = false, interactionSource = interaction, onClick = onClick)
                    .background(circle, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                // Points the way the row moves: to the left for Next in right-to-left languages.
                val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                Image(
                    if (forward != rtl) SnIcons.ChevronRight else SnIcons.ChevronLeft,
                    contentDescription = null,
                    modifier = Modifier.size(if (state.hover && !reducedMotion) 28.dp else 24.dp),
                    colorFilter = ColorFilter.tint(SnColors.fg),
                )
            }
        }
    }
}

private const val PAGE_BUTTON_FADE_MS = 150

/** Width of the fade behind a button that covers the end of a row. */
private val EdgeFadeWidth = 72.dp

/** The page color under the button, fading toward the row's middle. */
private fun Modifier.edgeFade(forward: Boolean): Modifier =
    drawWithCache {
        val solid = SnColors.bg
        val clear = SnColors.bg.copy(alpha = 0f)
        // The button's side of the slot is solid; in right-to-left the row's end is the left.
        val towardEnd = forward == (layoutDirection == LayoutDirection.Ltr)
        val brush =
            Brush.horizontalGradient(
                0f to (if (towardEnd) clear else solid),
                0.45f to solid.copy(alpha = 0.92f),
                1f to (if (towardEnd) solid else clear),
            )
        onDrawBehind { drawRect(brush) }
    }

/**
 * Scrolls by what the row shows. Forward, the first card cut off at the end becomes the
 * first one shown; backward, the cards before the first one fully shown fill the row, so
 * paging back and forth returns to the same place. The row's cards share one width.
 */
internal suspend fun LazyListState.page(forward: Boolean) {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return
    // Item offsets start after the content padding, at the page gutter.
    val end = info.viewportEndOffset - info.afterContentPadding
    val shown = visible.filter { it.offset >= 0 && it.offset + it.size <= end }
    val target =
        if (forward) {
            visible.firstOrNull { it.offset + it.size > end }?.index ?: return
        } else {
            val first = shown.firstOrNull()?.index ?: visible.first().index
            (first - shown.size.coerceAtLeast(1)).coerceAtLeast(0)
        }
    animateScrollToItem(target)
}
