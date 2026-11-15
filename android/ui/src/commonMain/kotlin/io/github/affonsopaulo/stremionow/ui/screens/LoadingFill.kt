// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * Where the loading logo's fill heads while a video opens. Nothing reports how far opening has
 * got (a torrent looking for peers, mpv reading the file's header), so the fill moves by stage:
 * it eases towards each stage's [target] over about [durationMs], slowing down so it never
 * reaches the end early, and only ever moves forward.
 */
internal enum class OpeningStage(
    val target: Float,
    val durationMs: Int,
) {
    /** The source gets ready: a web source's address, a torrent's peers and file list. */
    PREPARING(target = 0.35f, durationMs = 6_000),

    /** The player opens the file and decodes the first frame. */
    OPENING(target = 0.85f, durationMs = 6_000),

    /** The first frame is showing. */
    DONE(target = 1f, durationMs = 200),
    ;

    companion object {
        fun of(
            preparing: Boolean,
            loaded: Boolean,
        ): OpeningStage =
            when {
                loaded -> DONE
                preparing -> PREPARING
                else -> OPENING
            }
    }
}

/** The opening fill for [stage], from 0 to 1, moving only forward. */
@Composable
internal fun rememberOpeningFill(stage: OpeningStage): Float {
    val fill = remember { Animatable(0f) }
    LaunchedEffect(stage) {
        val target = maxOf(stage.target, fill.value)
        fill.animateTo(target, tween(stage.durationMs, easing = LinearOutSlowInEasing))
    }
    return fill.value
}

/**
 * True once [value] has been true for [delayMs] without a break, false as soon as it is false:
 * waits shorter than that show nothing, so quick seeks do not flash a loading screen.
 */
@Composable
internal fun rememberLasting(
    value: Boolean,
    delayMs: Long,
): Boolean {
    var lasting by remember { mutableStateOf(false) }
    LaunchedEffect(value) {
        if (value) {
            delay(delayMs)
            lasting = true
        } else {
            lasting = false
        }
    }
    return value && lasting
}
