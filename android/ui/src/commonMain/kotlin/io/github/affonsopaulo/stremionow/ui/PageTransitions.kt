// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut

/*
 * How one page of the app replaces another. Set once on the NavHost, so every page gets them; the title art that flies
 * from a card into a title's page is in components/SnTransitions.kt.
 */

/** What a route is, as far as the transitions go. */
internal enum class PageKind {
    /** A top-level tab: Home, Series, Movies, Search, My list. */
    Tab,

    /** Any other page: a title, a genre, Settings. */
    Page,

    /** The player. */
    Player,
}

/** How one page gives way to the next. */
internal enum class PageMove {
    /** A page opens over another: the old one fades out quickly, the new one fades in growing from 98%. */
    Open,

    /** Back to the page underneath: the reverse, the leaving page shrinks a little as it fades out. */
    Back,

    /** Between tabs, which are peers: a short crossfade, no scale. */
    Tab,

    /** In or out of the player: the page fades out to the dark background, then the next fades in. */
    Player,

    /** No animation, when the player is a window of its own and the app's window steps aside. */
    Cut,
}

/**
 * The move from a page of kind [from] to one of kind [to]; [back] when the navigation pops the
 * page being left. Going back from one tab to another (Movies to Home, the tab under it) is
 * switching tabs too.
 */
internal fun pageMove(
    from: PageKind,
    to: PageKind,
    back: Boolean,
    playerHasOwnWindow: Boolean,
): PageMove =
    when {
        from == PageKind.Player || to == PageKind.Player -> if (playerHasOwnWindow) PageMove.Cut else PageMove.Player
        to == PageKind.Tab && (!back || from == PageKind.Tab) -> PageMove.Tab
        back -> PageMove.Back
        else -> PageMove.Open
    }

/** How the page arriving with this move comes in. With [reducedMotion], it only fades. */
internal fun PageMove.enter(reducedMotion: Boolean): EnterTransition =
    when (this) {
        PageMove.Open -> {
            if (reducedMotion) {
                fadeIn(tween(PAGE_MS))
            } else {
                fadeIn(tween(PAGE_MS)) + scaleIn(tween(PAGE_MS), initialScale = PAGE_SCALE)
            }
        }

        PageMove.Back -> {
            fadeIn(tween(PAGE_MS))
        }

        PageMove.Tab -> {
            fadeIn(tween(TAB_MS))
        }

        // After the page leaving has gone, so the two never show at once.
        PageMove.Player -> {
            fadeIn(tween(PLAYER_FADE_MS, delayMillis = PLAYER_FADE_MS))
        }

        PageMove.Cut -> {
            EnterTransition.None
        }
    }

/** How the page leaving with this move goes. With [reducedMotion], it only fades. */
internal fun PageMove.exit(reducedMotion: Boolean): ExitTransition =
    when (this) {
        PageMove.Open -> {
            fadeOut(tween(PAGE_FADE_OUT_MS))
        }

        PageMove.Back -> {
            if (reducedMotion) {
                fadeOut(tween(BACK_FADE_OUT_MS))
            } else {
                fadeOut(tween(BACK_FADE_OUT_MS)) + scaleOut(tween(PAGE_MS), targetScale = PAGE_SCALE)
            }
        }

        PageMove.Tab -> {
            fadeOut(tween(TAB_MS))
        }

        PageMove.Player -> {
            fadeOut(tween(PLAYER_FADE_MS))
        }

        PageMove.Cut -> {
            ExitTransition.None
        }
    }

/** How long a page takes to open or come back. */
internal const val PAGE_MS = 250

/** The scale a page grows from as it opens, and shrinks to as it leaves going back. */
private const val PAGE_SCALE = 0.98f

/** The page under one that opens fades out quickly, so the two do not mix. */
private const val PAGE_FADE_OUT_MS = 100

private const val BACK_FADE_OUT_MS = 200

private const val TAB_MS = 150

/** Each half of the fade through the dark background into or out of the player. */
private const val PLAYER_FADE_MS = 200
