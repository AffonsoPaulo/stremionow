// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.affonsopaulo.stremionow.core.MetaItem
import io.github.affonsopaulo.stremionow.ui.theme.LocalIsTv
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import kotlinx.coroutines.flow.first

/*
 * What pages and cards need to take part in the page transitions, which the app sets once on
 * its NavHost (docs/plans/interaction-and-details.md, "Screen transitions"). Each page learns
 * when it has finished arriving, so the remote's focus moves only then: to the card the user
 * opened, when they come back to it, or to the page's first element.
 *
 * A title's art also flies from the poster that opened it into the hero of the title's page,
 * and back on Back: a shared element, matched by title. Only the card that was clicked takes
 * part, so a title shown in two rows flies from the one the user chose; on the way back it
 * lands where that card is now, and when the card has scrolled out of view (a lazy list no
 * longer composes it) nothing matches and the pages use the plain transition. Pages opened
 * any other way (a link, Continue watching, a card without a poster) find nothing to claim.
 */

/** The art that flies between a card and a title's page, by title. */
internal data class TitleArtKey(
    val kind: String,
    val id: String,
)

/**
 * The shared transition every page runs in, inside the header, so flying art passes under it.
 * A card that opens a title leaves its art here for the title's page to claim; with reduced
 * motion nothing flies.
 */
@Stable
class PageTransitions internal constructor(
    internal val scope: SharedTransitionScope,
    internal val flights: Boolean,
) {
    internal val board = FlightBoard(flights)
}

/**
 * Hands a card's art to the title page it opens. The card [launch]es it as it is clicked, and
 * the page that opens next [claim]s it once; a page of another title, or one opened without a
 * card, finds nothing, and nothing is left over for a later page. Nothing flies when not [enabled].
 */
internal class FlightBoard(
    private val enabled: Boolean,
) {
    private var pending: Pair<TitleArtKey, String>? = null

    /** A card opens [key]: its [art], when it has a picture, flies into the page. */
    fun launch(
        key: TitleArtKey,
        art: String?,
    ) {
        pending = if (enabled && art != null) key to art else null
    }

    /** The art flying into the page of [key]; null when that page opened another way. */
    fun claim(key: TitleArtKey): String? = pending?.takeIf { it.first == key }?.second.also { pending = null }
}

internal val LocalPageTransitions = staticCompositionLocalOf<PageTransitions?> { null }

/** Lays out the app's pages, [content], in a shared transition, so title art can fly between them. */
@Composable
internal fun SnPageTransitions(
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    SharedTransitionLayout(modifier) {
        val transitions = remember(this, reducedMotion) { PageTransitions(this, flights = !reducedMotion) }
        CompositionLocalProvider(LocalPageTransitions provides transitions, content = content)
    }
}

/**
 * A card that opens a title: where it is on its page ([slot], unique on the page, as a row's
 * key and the title), the title, and the [art] the title's page shows while it loads, null
 * when the card has no picture to fly.
 */
@Immutable
data class TitleOrigin(
    val slot: String,
    val kind: String,
    val id: String,
    val art: String?,
)

/**
 * The origin of a card for [item] in the row or grid [slot]. Its art is the backdrop, as on the
 * title's page, or else the poster; a card without a poster shows only text, and does not fly.
 */
fun titleOrigin(
    slot: String,
    item: MetaItem,
): TitleOrigin =
    TitleOrigin(
        "$slot/${item.kind}/${item.id}",
        item.kind,
        item.id,
        item.poster?.let {
            item.background
                ?: it
        },
    )

/** What a card that opens a title applies, from [titleCard]. */
@Stable
class TitleCard internal constructor(
    /** On the card's picture, which flies into the title's page. */
    val art: Modifier,
    /** Before the card's clickable, which takes the remote's focus back when the user returns. */
    val focus: Modifier,
    /** To call as the card is clicked, before navigating. */
    val onOpen: () -> Unit,
)

private val PlainCard = TitleCard(Modifier, Modifier, onOpen = {})

/**
 * The part of a card from [origin] in the page transitions: its picture flies into the title's
 * page and back, and on TV it takes the focus again when the user comes back. [overlay] draws
 * the flying picture over the pages, as a poster above its row should be; a picture behind
 * other content, like the home hero's, sets it to false and flies in its own place.
 */
@Composable
fun titleCard(
    origin: TitleOrigin?,
    overlay: Boolean = true,
): TitleCard {
    val transitions = LocalPageTransitions.current
    val page = LocalPageMotion.current
    if (origin == null || page == null) return PlainCard
    val key = TitleArtKey(origin.kind, origin.id)
    val opened = page.opened == origin.slot
    val requester = remember { FocusRequester() }
    if (opened) {
        DisposableEffect(page, requester) {
            page.returnTo = requester
            onDispose { if (page.returnTo === requester) page.returnTo = null }
        }
    }
    val flying = transitions?.takeIf { opened && it.flights && origin.art != null }
    return TitleCard(
        art = if (flying != null) Modifier.flyingArt(flying, page, key, overlay) else Modifier,
        focus = Modifier.focusRequester(requester),
        onOpen = {
            page.opened = origin.slot
            transitions?.board?.launch(key, origin.art)
        },
    )
}

/**
 * How a title's page arrived: from a card whose [art] flies in (shown while the page loads), or
 * any other way, when [art] is null. Kept with the page, so Back flies the art home again.
 */
@Immutable
class TitleArrival internal constructor(
    internal val key: TitleArtKey,
    val art: String?,
) {
    val flies: Boolean get() = art != null
}

/** How the page of the title [kind]/[id] arrived; see [TitleArrival]. */
@Composable
fun rememberTitleArrival(
    kind: String,
    id: String,
): TitleArrival {
    val transitions = LocalPageTransitions.current
    val key = TitleArtKey(kind, id)
    val art = rememberSaveable(kind, id) { mutableStateOf(transitions?.board?.claim(key)) }.value
    return remember(key, art) { TitleArrival(key, art) }
}

/** On a title page's hero art: the card's picture flies into it, and back, when [arrival] flies. */
@Composable
fun Modifier.titleHeroArt(arrival: TitleArrival): Modifier {
    val transitions = LocalPageTransitions.current
    val page = LocalPageMotion.current
    if (!arrival.flies || transitions == null || !transitions.flights || page == null) return this
    // In its own place, under the hero's title and buttons, rather than over the whole page.
    return then(Modifier.flyingArt(transitions, page, arrival.key, overlay = false))
}

@Composable
private fun Modifier.flyingArt(
    transitions: PageTransitions,
    page: PageMotion,
    key: TitleArtKey,
    overlay: Boolean,
): Modifier =
    with(transitions.scope) {
        this@flyingArt.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = page.visibility,
            enter = fadeIn(tween(FLIGHT_MS)),
            exit = fadeOut(tween(FLIGHT_MS)),
            boundsTransform = FlightBounds,
            renderInOverlayDuringTransition = overlay,
            clipInOverlayDuringTransition = FlightClip,
        )
    }

/** How long art takes to fly between a card and a title's page. */
private const val FLIGHT_MS = 300

private val FlightBounds = BoundsTransform { _, _ -> tween(FLIGHT_MS) }

/**
 * Rounds the flying art's corners as a card's, less as it grows, so a poster's corners fade into
 * the square edges of a hero instead of snapping square at the end.
 */
private object FlightClip : SharedTransitionScope.OverlayClip {
    override fun getClipPath(
        sharedContentState: SharedTransitionScope.SharedContentState,
        bounds: Rect,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Path {
        val full = with(density) { SnRadius.radiusMd.toPx() }
        val card = with(density) { PosterCardWidth.toPx() }
        val radius = (full * card / bounds.width.coerceAtLeast(1f)).coerceAtMost(full)
        return Path().apply { addRoundRect(RoundRect(bounds, CornerRadius(radius))) }
    }
}

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
