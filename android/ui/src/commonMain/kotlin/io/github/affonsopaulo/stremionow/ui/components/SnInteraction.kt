// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.affonsopaulo.stremionow.ui.theme.LocalIsTv
import io.github.affonsopaulo.stremionow.ui.theme.SnColors

/** Length of every hover and press transition, within the 120 to 180 ms the design asks for. */
internal const val FEEDBACK_MS = 160

/**
 * What an element shows for its interaction right now: the focus ring, the hover highlight
 * and the pressed shade. Built by [collectSnInteraction], which applies [LocalSnFeedback] and
 * the TV rules, so components only decide how each one looks.
 */
@Immutable
data class SnInteractionState(
    val ring: Boolean = false,
    val hover: Boolean = false,
    val press: Boolean = false,
)

/**
 * Decides what an element shows. The ring follows [SnFeedback.showFocus]. Hover and press
 * need a pointer or a touch screen, so a TV shows neither: its D-pad focus ring is the only
 * feedback, as before. A disabled element shows neither either, only its ring.
 */
internal fun snInteractionState(
    feedback: SnFeedback,
    isTv: Boolean,
    enabled: Boolean,
    focused: Boolean,
    hovered: Boolean,
    pressed: Boolean,
): SnInteractionState {
    val reacts = enabled && !isTv
    return SnInteractionState(
        ring = focused && feedback.showFocus,
        hover = reacts && feedback.showHover && hovered,
        press = reacts && feedback.showPress && pressed,
    )
}

/** Whether a clickable shows the hand cursor under the mouse. */
internal fun snShowsHandCursor(
    feedback: SnFeedback,
    isTv: Boolean,
    enabled: Boolean,
): Boolean = enabled && !isTv && feedback.showHover

/** The [SnInteractionState] of an element that reports to this interaction source. */
@Composable
fun MutableInteractionSource.collectSnInteraction(enabled: Boolean = true): SnInteractionState {
    val focused by collectIsFocusedAsState()
    val hovered by collectIsHoveredAsState()
    val pressed by collectIsPressedAsState()
    return snInteractionState(LocalSnFeedback.current, LocalIsTv.current, enabled, focused, hovered, pressed)
}

/**
 * The background an element shows in [state]: lighter under the pointer, darker when pressed.
 * A light fill cannot get lighter, so a white button or selected pill keeps its look on
 * hover. On a translucent or transparent fill, such as an icon button's, hover adds a
 * translucent circle and a press darkens that circle rather than the page behind it, so a
 * press shows on touch screens too.
 */
internal fun snStateColor(
    base: Color,
    state: SnInteractionState,
): Color =
    when {
        state.press && base.alpha < 1f -> SnColors.statePress.compositeOver(SnColors.stateHover.compositeOver(base))
        state.press -> SnColors.statePress.compositeOver(base)
        state.hover -> SnColors.stateHover.compositeOver(base)
        else -> base
    }

/**
 * A card's border in [state]: lighter under the pointer. While the focus ring shows, the ring
 * is the outline and the border keeps its resting color, so there is never a double outline.
 */
internal fun snStateBorderColor(
    base: Color,
    state: SnInteractionState,
): Color = if (state.hover && !state.ring) SnColors.stateHoverBorder.compositeOver(base) else base

/**
 * Fills the element with [color], lightened on hover and darkened on press as [snStateColor]
 * says, with a short transition. Pair it with [snClickable] given the same interaction source
 * and `highlight = false`.
 */
@Composable
fun Modifier.snStateBackground(
    color: Color,
    shape: Shape,
    state: SnInteractionState,
): Modifier {
    val shown by animateColorAsState(snStateColor(color, state), tween(FEEDBACK_MS), label = "state-background")
    return background(shown, shape)
}

/** A card's border, lighter on hover as [snStateBorderColor] says, with a short transition. */
@Composable
fun Modifier.snStateBorder(
    color: Color,
    shape: Shape,
    state: SnInteractionState,
    width: Dp = 1.dp,
): Modifier {
    val shown by animateColorAsState(snStateBorderColor(color, state), tween(FEEDBACK_MS), label = "state-border")
    return border(width, shown, shape)
}

/** How a card responds: whether it grows, and whether it lifts (see [snCardMotion]). */
@Immutable
internal data class SnCardMotion(
    val grow: Boolean,
    val lift: Boolean,
)

/**
 * Decides how a card responds. Lifting is the shadow and, for a poster, the picture's zoom.
 * Focus grows a card that [growOnFocus], as on TV, with no shadow; the pointer grows and lifts
 * a card that [liftOnHover]; a press settles it back. Reduced motion keeps only color changes,
 * so the pointer neither grows nor lifts anything.
 */
internal fun snCardMotion(
    state: SnInteractionState,
    growOnFocus: Boolean,
    liftOnHover: Boolean,
    reducedMotion: Boolean,
): SnCardMotion {
    val lift = liftOnHover && state.hover && !state.press && !reducedMotion
    return SnCardMotion(grow = lift || (growOnFocus && state.ring && !state.press), lift = lift)
}

/**
 * How much a card grows, as a fraction of its size: 5% for posters and episodes (the TV's
 * focus growth); when [capped], for wide cards such as sources and addons, no more than
 * [maxGrowthPx] across, so they do not spill over their neighbors.
 */
internal fun snGrowFraction(
    widthPx: Float,
    maxGrowthPx: Float,
    capped: Boolean,
): Float =
    when {
        !capped -> GROW_FRACTION
        widthPx <= 0f -> 0f
        else -> minOf(GROW_FRACTION, maxGrowthPx / widthPx)
    }

internal const val GROW_FRACTION = 0.05f
