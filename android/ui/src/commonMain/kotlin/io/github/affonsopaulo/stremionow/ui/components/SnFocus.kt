// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.affonsopaulo.stremionow.ui.platform.rememberReducedMotion
import io.github.affonsopaulo.stremionow.ui.theme.LocalIsTv
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import kotlin.math.abs

/**
 * How focus and pointer feedback behave where the interface runs. The defaults are Android's:
 * touch and remotes never move focus by accident, so a focused element always shows its ring.
 * The desktop shows rings only after keyboard input, like the web's `:focus-visible` (a click
 * focuses what it hits). Hover and press feedback never show on TV, whatever these say: the
 * D-pad focus ring is the feedback there.
 */
@Immutable
data class SnFeedback(
    /** Focused elements draw the focus ring (and grow, where they do). */
    val showFocus: Boolean = true,
    /**
     * Elements under a mouse pointer are highlighted and show the hand cursor. Touch never
     * hovers, so on phones and tablets this only shows with a mouse, touchpad or stylus.
     */
    val showHover: Boolean = true,
    /** Pressed elements darken, and cards settle back. */
    val showPress: Boolean = true,
    /**
     * The mouse is the main input, as on the desktop: rows of cards add paging buttons and
     * mouse dragging.
     */
    val mouseFirst: Boolean = false,
)

/** The [SnFeedback] in effect. */
val LocalSnFeedback = compositionLocalOf { SnFeedback() }

/**
 * Makes an element clickable with the design system's feedback, the same on every screen.
 * Keyboard, D-pad, touch and mouse all work; [LocalSnFeedback] decides what shows.
 *
 * - Focus: a 3 dp ring in the focus color drawn just outside the element, and, when [grow]
 *   is set, a 5% scale.
 * - Hover (mouse) and press (mouse, touch, Enter): with [highlight], a translucent fill behind
 *   the element's content, lighter on hover and darker when pressed, which is all an element
 *   without a fill of its own needs (icon-only boxes, text). An element with a fill of its own
 *   turns [highlight] off, passes an [interactionSource] and tints its fill with
 *   [snStateBackground] instead. On the desktop, the hand cursor.
 * - Cards: [lift] (on by default with [grow]) grows the card a little under the pointer and
 *   gives it a shadow; a press settles it back. Wide cards that do not [grow] on focus grow by
 *   a few dp at most. With reduced motion only colors change.
 *
 * A disabled element ignores clicks, shows no hover or press, and is announced as disabled,
 * but stays focusable, with its ring. Otherwise a button that disables itself while it works
 * (Install, Move up on the first row) would drop the focus somewhere unseen, and a remote
 * would lose its place.
 */
@Composable
fun Modifier.snClickable(
    shape: Shape,
    onClickLabel: String? = null,
    role: Role = Role.Button,
    grow: Boolean = false,
    enabled: Boolean = true,
    lift: Boolean = grow,
    highlight: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier {
    val feedback = LocalSnFeedback.current
    val isTv = LocalIsTv.current
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val state = interaction.collectSnInteraction(enabled)
    val reducedMotion = if (lift) rememberReducedMotion() else false
    val motion = snCardMotion(state, growOnFocus = grow, liftOnHover = lift, reducedMotion = reducedMotion)
    val growth by animateFloatAsState(
        targetValue = if (motion.grow) 1f else 0f,
        animationSpec = tween(durationMillis = FEEDBACK_MS),
        label = "card-grow",
    )
    val shadow by animateFloatAsState(
        targetValue = if (motion.lift) 1f else 0f,
        animationSpec = tween(durationMillis = FEEDBACK_MS),
        label = "card-shadow",
    )
    val fill by animateColorAsState(
        targetValue = if (highlight) snStateColor(Color.Transparent, state) else Color.Transparent,
        animationSpec = tween(durationMillis = FEEDBACK_MS),
        label = "highlight",
    )
    val maxGrowth = with(LocalDensity.current) { LiftMaxGrowth.toPx() }
    return this
        .then(
            if (grow || lift) {
                Modifier.graphicsLayer {
                    val scale = 1f + snGrowFraction(size.width, maxGrowth, capped = !grow) * growth
                    scaleX = scale
                    scaleY = scale
                    if (shadow > 0f) {
                        shadowElevation = LiftShadow.toPx() * shadow
                        this.shape = shape
                    }
                }
            } else {
                Modifier
            },
        ).then(if (state.ring) Modifier.focusRing(shape) else Modifier)
        .then(if (highlight) Modifier.drawStateFill(shape) { fill } else Modifier)
        .then(
            if (snShowsHandCursor(feedback, isTv, enabled)) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier,
        ).then(if (enabled) Modifier else Modifier.semantics { disabled() })
        .clickable(
            interactionSource = interaction,
            indication = null,
            onClickLabel = onClickLabel,
            role = role,
            onClick = { if (enabled) onClick() },
        )
}

/** The most a wide card grows across under the pointer. */
private val LiftMaxGrowth = 12.dp

/** Elevation of a card's shadow under the pointer. */
private val LiftShadow = 16.dp

/** Draws [color] in [shape] behind the element's content; read when drawing, so it animates cheaply. */
private fun Modifier.drawStateFill(
    shape: Shape,
    color: () -> Color,
): Modifier =
    drawWithContent {
        val fill = color()
        if (fill.alpha > 0f) drawOutline(shape.createOutline(size, layoutDirection, this), fill)
        drawContent()
    }

/**
 * Draws the focus ring around a container while [interaction] reports focus inside it, such
 * as a text field's box, whose focusable part is the field within.
 */
@Composable
internal fun Modifier.snFocusRingFor(
    interaction: MutableInteractionSource,
    shape: Shape,
): Modifier {
    val focused by interaction.collectIsFocusedAsState()
    return if (focused && LocalSnFeedback.current.showFocus) focusRing(shape) else this
}

/**
 * Makes an element that is not a button reachable by keyboard and D-pad, with the same focus
 * ring as [snClickable], such as a block of text that scrolls with the arrow keys.
 */
@Composable
fun Modifier.snFocusable(shape: Shape): Modifier {
    val feedback = LocalSnFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    return this
        .then(if (focused && feedback.showFocus) Modifier.focusRing(shape) else Modifier)
        .focusable(interactionSource = interaction)
}

/** Gap between an element and its focus ring, so the ring shows on light elements too. */
private val RingGap = 3.dp
private val RingWidth = 3.dp

/** Draws the focus ring around the element, outside its bounds, without changing layout. */
private fun Modifier.focusRing(shape: Shape): Modifier =
    drawWithContent {
        drawContent()
        val inset = (RingGap + RingWidth / 2).toPx()
        val ringSize = Size(size.width + inset * 2, size.height + inset * 2)
        val outline = shape.createOutline(ringSize, layoutDirection, this)
        translate(-inset, -inset) {
            drawOutline(outline, SnColors.focus, style = Stroke(RingWidth.toPx()))
        }
    }

/**
 * On TV, gives this element focus when it first appears, so a remote can act right away.
 * Use it on the main action of a screen. Touch screens are unaffected.
 *
 * On a page still arriving, it waits for the page's transition to end, so the focus does not
 * land while the page moves; and coming back to a page, the card the user had opened from it
 * takes the focus instead (see [PageMotion]).
 */
@Composable
fun Modifier.initialFocus(): Modifier {
    if (!LocalIsTv.current) return this
    val requester = remember { FocusRequester() }
    val page = LocalPageMotion.current
    LaunchedEffect(page) {
        if (page != null && !page.settled) {
            page.awaitSettled()
            if (page.focusReturned) return@LaunchedEffect
        }
        runCatching { requester.requestFocus() }
    }
    return focusRequester(requester)
}

/**
 * Lets a remote's up and down keys leave a text field. Single-line fields keep those keys
 * otherwise, which traps D-pad users inside them.
 */
@Composable
fun Modifier.dpadExits(): Modifier {
    val focusManager = LocalFocusManager.current
    return onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
            Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
            else -> false
        }
    }
}

/**
 * Scrolls a list only as far as needed to show the focused element. Compose's default on TV
 * keeps focus at a fixed height instead, which would push a page's header, such as the home
 * hero's title, off screen as soon as anything inside it takes focus.
 *
 * A vertical list that runs under the app header passes the header's height as [topInset],
 * so a focused element is scrolled clear of the header rather than just onto the screen. The
 * inset applies to every list inside [content] alike, so rows nested in such a list go in a
 * plain [SnMinimalScroll] of their own.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SnMinimalScroll(
    topInset: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val inset = with(LocalDensity.current) { topInset.toPx() }
    val spec = remember(inset) { MinimalBringIntoView(inset) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** Brings an element into view with the least scrolling, below the first [start] pixels. */
@OptIn(ExperimentalFoundationApi::class)
private class MinimalBringIntoView(
    private val start: Float,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float {
        val top = offset - start
        val end = top + size
        val visible = containerSize - start
        return when {
            top >= 0 && end <= visible -> 0f
            top < 0 && end > visible -> 0f
            abs(top) < abs(end - visible) -> top
            else -> end - visible
        }
    }
}
