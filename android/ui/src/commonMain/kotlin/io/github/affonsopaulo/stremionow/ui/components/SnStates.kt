// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography

/** Full-area loading indicator: a softly pulsing dot, announced to screen readers. */
@Composable
fun SnLoading(modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "loading").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "loading-alpha",
    )
    val label = stringResource(Res.string.state_loading)
    Box(modifier.fillMaxSize().semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(14.dp)
                .alpha(pulse)
                .background(SnColors.accent, CircleShape),
        )
    }
}

/**
 * Empty or error state: what happened, what still works and one action that helps.
 */
@Composable
fun SnMessage(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize().padding(SnSpacing.space8), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SnSpacing.space3),
        ) {
            BasicText(title, style = SnTypography.sectionTitle.copy(color = SnColors.fg, textAlign = TextAlign.Center))
            BasicText(body, style = SnTypography.body.copy(color = SnColors.fgSecondary, textAlign = TextAlign.Center))
            if (actionText != null && onAction != null) {
                SnButton(text = actionText, onClick = onAction, modifier = Modifier.padding(top = SnSpacing.space2))
            }
        }
    }
}

/** Standard error state for a failed load, with a retry action. */
@Composable
fun SnLoadError(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SnMessage(
        title = stringResource(Res.string.error_load_title),
        body = stringResource(Res.string.error_load_body),
        actionText = stringResource(Res.string.action_retry),
        onAction = onRetry,
        modifier = modifier,
    )
}
