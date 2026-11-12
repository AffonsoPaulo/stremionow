// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.affonsopaulo.stremionow.ui.theme.SnColors

/*
 * Pages with title art (the home hero, a title's details) draw it from the top of the window,
 * under the app header. The header is clear while that art sits at the top and turns solid
 * as it scrolls away; every other page starts below a solid header, as before.
 */

/**
 * Height of the app header over the page, status bar included. Pages that run under the
 * header use it to keep their content clear of it; zero where no header shows.
 */
val LocalHeaderInset = compositionLocalOf { 0.dp }

/**
 * How opaque the app header's background is over each page. A page with title art at its top
 * registers how far that art has scrolled away; pages that register nothing get a solid
 * header.
 */
@Stable
class HeaderOverlay {
    private val pages = mutableStateMapOf<Any, State<Float>>()

    /** Background opacity of the header over [page], from 0 (clear) to 1 (solid). */
    fun opacityOver(page: Any?): Float = page?.let { pages[it] }?.value ?: 1f

    internal fun show(
        page: Any,
        opacity: State<Float>,
    ) {
        pages[page] = opacity
    }

    internal fun release(
        page: Any,
        opacity: State<Float>,
    ) {
        if (pages[page] === opacity) pages.remove(page)
    }
}

/** The app's [HeaderOverlay]. */
internal val LocalHeaderOverlay = staticCompositionLocalOf { HeaderOverlay() }

/** Identifies the page being composed (its back stack entry) to [HeaderOverlay]. */
internal val LocalHeaderPage = staticCompositionLocalOf<Any?> { null }

/** Starts the element below the app header, for pages (or states of a page) without title art. */
@Composable
fun Modifier.belowHeader(): Modifier = padding(top = LocalHeaderInset.current)

/**
 * Clears the header over this page while the first item of [listState], its title art, is
 * at the top. The background comes back in step with the scroll and is solid once the page
 * has moved by the header's height, before the hero's title and buttons can reach it.
 */
@Composable
fun ClearHeaderOverHero(listState: LazyListState) {
    val overlay = LocalHeaderOverlay.current
    val page = LocalHeaderPage.current ?: return
    val span by rememberUpdatedState(with(LocalDensity.current) { LocalHeaderInset.current.toPx() })
    val opacity =
        remember(listState) {
            derivedStateOf {
                when {
                    listState.firstVisibleItemIndex > 0 || span <= 0f -> 1f
                    else -> (listState.firstVisibleItemScrollOffset / span).coerceIn(0f, 1f)
                }
            }
        }
    DisposableEffect(overlay, page, opacity) {
        overlay.show(page, opacity)
        onDispose { overlay.release(page, opacity) }
    }
}

/**
 * Darkens the top of title art where the clear header sits, so the header's light text keeps
 * at least 4.5:1 contrast over the brightest image: over pure white, the secondary text color
 * needs the page color at 0.69 opacity or more. Dark through the header's items, then eased
 * out over about as much again, so the edge never shows.
 */
@Composable
fun Modifier.headerScrim(): Modifier {
    val inset = LocalHeaderInset.current
    if (inset <= 0.dp) return this
    return drawWithCache {
        val span = inset.toPx() * SCRIM_SPAN
        val brush =
            Brush.verticalGradient(
                0f to SnColors.bg.copy(alpha = 0.85f),
                0.85f / SCRIM_SPAN to SnColors.bg.copy(alpha = 0.72f),
                1.25f / SCRIM_SPAN to SnColors.bg.copy(alpha = 0.42f),
                1.7f / SCRIM_SPAN to SnColors.bg.copy(alpha = 0.12f),
                1f to SnColors.bg.copy(alpha = 0f),
                startY = 0f,
                endY = span,
            )
        onDrawWithContent {
            drawContent()
            drawRect(brush, size = Size(size.width, minOf(span, size.height)))
        }
    }
}

/** Length of [headerScrim], in header heights. */
private const val SCRIM_SPAN = 2.1f

/**
 * Darkens art from the side where text starts (the left, or the right in right-to-left
 * languages), fading along the given stops.
 */
fun Modifier.startScrim(vararg stops: Pair<Float, Color>): Modifier =
    drawWithCache {
        val rtl = layoutDirection == LayoutDirection.Rtl
        val brush =
            Brush.horizontalGradient(
                *stops,
                startX = if (rtl) size.width else 0f,
                endX = if (rtl) 0f else size.width,
            )
        onDrawBehind { drawRect(brush) }
    }
