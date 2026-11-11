// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.affonsopaulo.stremionow.ui.theme.LocalIsTv
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import kotlin.math.abs

/**
 * How focus and pointer feedback behave where the interface runs. The defaults are Android's:
 * touch and remotes never move focus by accident, so a focused element always shows its ring,
 * and there is no hover. The desktop shows rings only after keyboard input, like the web's
 * `:focus-visible` (a click focuses what it hits), and highlights what the mouse points at.
 */
@Immutable
data class SnFeedback(
    /** Focused elements draw the focus ring (and grow, where they do). */
    val showFocus: Boolean = true,
    /** Elements under the mouse pointer grow, where they do, and show the hand cursor. */
    val showHover: Boolean = false,
)

/** The [SnFeedback] in effect. */
val LocalSnFeedback = compositionLocalOf { SnFeedback() }

/**
 * Makes an element clickable with the design system's focus treatment: a 3 dp ring in the
 * focus color drawn just outside the element, and, when [grow] is set, a 5% scale. Keyboard,
 * D-pad, touch and mouse all work; [LocalSnFeedback] decides when the ring and hover show.
 */
@Composable
fun Modifier.snClickable(
    shape: Shape,
    onClickLabel: String? = null,
    role: Role = Role.Button,
    grow: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val feedback = LocalSnFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val showFocus = focused && feedback.showFocus
    val scale by animateFloatAsState(
        targetValue = if (grow && (showFocus || (hovered && feedback.showHover))) 1.05f else 1f,
        animationSpec = tween(durationMillis = 160),
        label = "focus-scale",
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }.then(if (showFocus) Modifier.focusRing(shape) else Modifier)
        .then(if (feedback.showHover && enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onClick = onClick,
        )
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
 */
@Composable
fun Modifier.initialFocus(): Modifier {
    if (!LocalIsTv.current) return this
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
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
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SnMinimalScroll(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalBringIntoView, content = content)
}

@OptIn(ExperimentalFoundationApi::class)
private object MinimalBringIntoView : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float {
        val end = offset + size
        return when {
            offset >= 0 && end <= containerSize -> 0f
            offset < 0 && end > containerSize -> 0f
            abs(offset) < abs(end - containerSize) -> offset
            else -> end - containerSize
        }
    }
}
