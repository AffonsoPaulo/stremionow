// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester
import io.github.affonsopaulo.stremionow.ui.theme.LocalIsTv
import kotlinx.coroutines.flow.first

/*
 * What pages and cards need to take part in the page transitions, which the app sets once on
 * its NavHost (docs/plans/interaction-and-details.md, "Screen transitions"). Each page learns
 * when it has finished arriving, so the remote's focus moves only then: to the card the user
 * opened, when they come back to it, or to the page's first element.
 */

/**
 * One page's part in the transitions: its own visibility, as the navigation animates it, and
 * the card on it that opened the page above, kept with the page's state so that coming back
 * finds it again.
 */
@Stable
class PageMotion internal constructor(
    internal val visibility: AnimatedVisibilityScope,
    opened: MutableState<String?>,
) {
    /** The slot of the card that opened the page above, until the user is back on this one. */
    internal var opened by opened

    /** Whether the page has finished arriving: its transition, and any art flying in, are over. */
    internal var settled by mutableStateOf(false)
        private set

    /** The card the user opened the page above from, on TV, to focus again on the way back. */
    internal var returnTo: FocusRequester? = null

    /** Whether the focus went back to [returnTo] as the page settled, so nothing else takes it. */
    internal var focusReturned = false
        private set

    /** Waits for the page to arrive, then gives the focus back to the card the user left, on TV. */
    internal suspend fun arrive(isTv: Boolean) {
        snapshotFlow {
            val transition = visibility.transition
            transition.currentState == EnterExitState.Visible && transition.targetState == EnterExitState.Visible
        }.first { it }
        if (isTv) focusReturned = returnTo?.let { runCatching { it.requestFocus() }.isSuccess } == true
        settled = true
        // Back on this page: the card no longer leads anywhere.
        opened = null
    }

    internal suspend fun awaitSettled() {
        snapshotFlow { settled }.first { it }
    }
}

/** The [PageMotion] of the page being composed; null outside the app's pages. */
internal val LocalPageMotion = staticCompositionLocalOf<PageMotion?> { null }

/** Gives [content], one destination of the app's NavHost, its part in the page transitions. */
@Composable
internal fun ProvidePageMotion(
    visibility: AnimatedVisibilityScope,
    content: @Composable () -> Unit,
) {
    val opened = rememberSaveable { mutableStateOf<String?>(null) }
    val motion = remember(visibility) { PageMotion(visibility, opened) }
    val isTv = LocalIsTv.current
    LaunchedEffect(motion) { motion.arrive(isTv) }
    CompositionLocalProvider(LocalPageMotion provides motion, content = content)
}
