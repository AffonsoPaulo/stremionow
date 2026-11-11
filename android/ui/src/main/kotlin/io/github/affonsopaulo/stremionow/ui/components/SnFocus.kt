// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.affonsopaulo.stremionow.ui.theme.SnColors

/**
 * Makes an element clickable with the design system's focus treatment: a 3 dp ring in the
 * focus color and, when [grow] is set, a 5% scale. Keyboard, D-pad and touch all work.
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
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (grow && focused) 1.05f else 1f,
        animationSpec = tween(durationMillis = 160),
        label = "focus-scale",
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }.then(if (focused) Modifier.border(3.dp, SnColors.focus, shape) else Modifier)
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onClick = onClick,
        )
}
