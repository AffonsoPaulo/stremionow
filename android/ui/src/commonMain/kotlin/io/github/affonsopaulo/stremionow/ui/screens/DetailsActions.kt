// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import io.github.affonsopaulo.stremionow.core.TitleRating
import io.github.affonsopaulo.stremionow.ui.components.SnButton
import io.github.affonsopaulo.stremionow.ui.components.SnButtonStyle
import io.github.affonsopaulo.stremionow.ui.components.SnDialogActions
import io.github.affonsopaulo.stremionow.ui.components.SnDialogFrame
import io.github.affonsopaulo.stremionow.ui.components.snClickable
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.platform.LocalPlatformContext
import io.github.affonsopaulo.stremionow.ui.platform.canShareText
import io.github.affonsopaulo.stremionow.ui.platform.copyToClipboard
import io.github.affonsopaulo.stremionow.ui.platform.shareText
import io.github.affonsopaulo.stremionow.ui.theme.LocalIsTv
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography

/** What the action row shows and does; null actions are left out. */
internal class DetailActions(
    /** Null on movies too, whose second hero button already plays the trailer (see [rowTrailer]). */
    val onTrailer: (() -> Unit)?,
    val inLibrary: Boolean,
    val onToggleLibrary: () -> Unit,
    /** A movie watched, so the action marks it unwatched; series open a choice instead. */
    val watched: Boolean,
    val onWatched: () -> Unit,
    val rating: TitleRating,
    val onRate: (TitleRating) -> Unit,
    val onShare: (() -> Unit)?,
)

/** The tiles of the action row. */
internal enum class DetailAction { Trailer, MyList, Watched, Like, Love, Share }

/** The tiles [actions] shows, in order. */
internal fun shownActions(actions: DetailActions): List<DetailAction> =
    buildList {
        if (actions.onTrailer != null) add(DetailAction.Trailer)
        add(DetailAction.MyList)
        add(DetailAction.Watched)
        add(DetailAction.Like)
        add(DetailAction.Love)
        if (actions.onShare != null) add(DetailAction.Share)
    }

/**
 * The row's trailer: only on a series. A movie's second hero button is already "Trailer", and
 * a series' is "Start over" (or nothing), so only there does the row need its own.
 */
internal fun <T : Any> rowTrailer(
    isSeries: Boolean,
    trailer: T?,
): T? = trailer?.takeIf { isSeries }

/** The rating after choosing [chosen]: choosing the current one again clears it. */
internal fun ratingAfter(
    current: TitleRating,
    chosen: TitleRating,
): TitleRating = if (current == chosen) TitleRating.NONE else chosen

/**
 * The actions of the official app under a title's buttons: trailer, My list, mark as watched,
 * like, love and share. Each is a tile of the same size, an icon over a one-line name, with no
 * fill of its own; what is on (in My list, watched, liked, loved) shows in its icon and color.
 * The first tile starts where the hero's buttons do. A row that does not fit, as on phones,
 * scrolls sideways out to the screen's edges rather than wrapping.
 */
@Composable
internal fun ActionRow(
    actions: DetailActions,
    modifier: Modifier = Modifier,
) {
    val edge = SnLayout.gutter
    Row(
        modifier =
            modifier
                // The scroll clips at its bounds: they reach out to the screen's edges and leave
                // room for the focus ring above and below, while the tiles stay in place.
                .bleed(horizontal = edge, vertical = FocusRingSpace)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = edge, vertical = FocusRingSpace),
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space1),
    ) {
        val liked = actions.rating == TitleRating.LIKED
        val loved = actions.rating == TitleRating.LOVED
        for (action in shownActions(actions)) {
            when (action) {
                DetailAction.Trailer -> {
                    ActionTile(
                        icon = SnIcons.Film,
                        label = stringResource(Res.string.action_trailer),
                        description = stringResource(Res.string.action_trailer_description),
                        onClick = { actions.onTrailer?.invoke() },
                    )
                }

                DetailAction.MyList -> {
                    ActionTile(
                        icon = if (actions.inLibrary) SnIcons.Check else SnIcons.Plus,
                        label =
                            stringResource(
                                if (actions.inLibrary) Res.string.action_in_list else Res.string.action_add_to_list,
                            ),
                        description =
                            stringResource(
                                if (actions.inLibrary) {
                                    Res.string.action_remove_from_list_description
                                } else {
                                    Res.string.action_add_to_list_description
                                },
                            ),
                        active = actions.inLibrary,
                        onClick = actions.onToggleLibrary,
                    )
                }

                DetailAction.Watched -> {
                    val description =
                        if (actions.watched) Res.string.action_mark_unwatched else Res.string.action_mark_watched
                    ActionTile(
                        icon = if (actions.watched) SnIcons.CheckCircleFilled else SnIcons.Eye,
                        label = stringResource(Res.string.action_watched),
                        description = stringResource(description),
                        active = actions.watched,
                        onClick = actions.onWatched,
                    )
                }

                DetailAction.Like -> {
                    ActionTile(
                        icon = if (liked) SnIcons.ThumbsUpFilled else SnIcons.ThumbsUp,
                        label = stringResource(Res.string.action_like),
                        active = liked,
                        onClick = { actions.onRate(ratingAfter(actions.rating, TitleRating.LIKED)) },
                    )
                }

                DetailAction.Love -> {
                    ActionTile(
                        icon = if (loved) SnIcons.HeartFilled else SnIcons.Heart,
                        label = stringResource(Res.string.action_love),
                        active = loved,
                        onClick = { actions.onRate(ratingAfter(actions.rating, TitleRating.LOVED)) },
                    )
                }

                DetailAction.Share -> {
                    ActionTile(
                        icon = SnIcons.Share,
                        label = stringResource(Res.string.action_share),
                        onClick = { actions.onShare?.invoke() },
                    )
                }
            }
        }
    }
}

/**
 * One action: an icon over its short name, in a tile of fixed size, so every tile lines up
 * whatever its name. Transparent at rest; the pointer and a press show the shared highlight,
 * the keyboard and remote the focus ring. A name too long for the tile ends in an ellipsis;
 * [description], the full meaning ("Mark as watched"), is what screen readers say.
 */
@Composable
private fun ActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    description: String = label,
    active: Boolean = false,
) {
    Column(
        modifier =
            Modifier
                .size(ActionTileWidth, ActionTileHeight)
                .semantics {
                    contentDescription = description
                    selected = active
                }.snClickable(RoundedCornerShape(SnRadius.radiusMd), role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space1, Alignment.CenterVertically),
    ) {
        Image(
            icon,
            contentDescription = null,
            modifier = Modifier.size(ActionIconSize),
            colorFilter = ColorFilter.tint(if (active) SnColors.accent else SnColors.fg),
        )
        BasicText(
            label,
            // The tile's description already says it.
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
            style =
                SnTypography.label.copy(
                    fontSize = SnTypography.overline.fontSize,
                    lineHeight = SnTypography.overline.lineHeight,
                    color = if (active) SnColors.fg else SnColors.fgSecondary,
                    textAlign = TextAlign.Center,
                ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Lets an element reach [horizontal] and [vertical] past each side of its place without
 * moving what is around it, for a scroll that should clip further out than its content.
 */
private fun Modifier.bleed(
    horizontal: Dp,
    vertical: Dp,
): Modifier =
    layout { measurable, constraints ->
        val x = horizontal.roundToPx()
        val y = vertical.roundToPx()
        val placeable = measurable.measure(constraints.offset(horizontal = x * 2, vertical = y * 2))
        layout(
            (placeable.width - x * 2).coerceAtLeast(0),
            (placeable.height - y * 2).coerceAtLeast(0),
        ) { placeable.place(-x, -y) }
    }

/** Wide enough for the longest name in one line ("Compartilhar"), six in the hero's text width. */
private val ActionTileWidth = 92.dp
private val ActionTileHeight = 64.dp
private val ActionIconSize = 22.dp

/** Room the focus ring takes outside an element: its gap plus its width. */
private val FocusRingSpace = 6.dp

/** One choice of the series' "Mark as watched": its label, and whether it marks or unmarks. */
internal class WatchedChoice(
    val label: String,
    val onClick: () -> Unit,
)

/**
 * "Mark as watched" on a series: the episode, the selected season or the whole series, each
 * worded as what choosing it does (mark, or unmark once everything it covers is watched).
 */
@Composable
internal fun WatchedDialog(
    choices: List<WatchedChoice>,
    onDismiss: () -> Unit,
) {
    SnDialogFrame(title = stringResource(Res.string.watched_title), onDismiss = onDismiss) {
        val firstFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
        BasicText(
            stringResource(Res.string.watched_body),
            style = SnTypography.body.copy(color = SnColors.fgSecondary),
        )
        Column(
            Modifier.fillMaxWidth().padding(top = SnSpacing.space2),
            verticalArrangement = Arrangement.spacedBy(SnSpacing.space2),
        ) {
            choices.forEachIndexed { index, choice ->
                SnButton(
                    text = choice.label,
                    onClick = {
                        choice.onClick()
                        onDismiss()
                    },
                    style = SnButtonStyle.Tonal,
                    modifier =
                        Modifier.fillMaxWidth().then(
                            if (index ==
                                0
                            ) {
                                Modifier.focusRequester(firstFocus)
                            } else {
                                Modifier
                            },
                        ),
                )
            }
        }
        SnDialogActions {
            SnButton(stringResource(Res.string.action_cancel), onDismiss, style = SnButtonStyle.Ghost)
        }
    }
}

/**
 * Shares a title's public page, as the addons screen shares an addon: copy or the share
 * sheet where there is one, a QR code on TV. [url] is Stremio's share link or the IMDb page,
 * never anything personal, so there is no warning to give.
 */
@Composable
internal fun ShareTitleDialog(
    name: String,
    url: String,
    onDismiss: () -> Unit,
) {
    val context = LocalPlatformContext.current
    val isTv = LocalIsTv.current
    var copied by remember { mutableStateOf(false) }
    val clipLabel = stringResource(Res.string.share_title_clip_label)
    SnDialogFrame(
        title = stringResource(Res.string.share_title_heading, name),
        onDismiss = onDismiss,
        maxWidth = if (isTv) CodeDialogWidth else 440.dp,
    ) {
        val mainFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { mainFocus.requestFocus() } }
        val text: @Composable () -> Unit = {
            BasicText(
                stringResource(if (isTv) Res.string.share_title_tv_body else Res.string.share_title_body),
                style = SnTypography.body.copy(color = SnColors.fgSecondary),
            )
            LinkText(url)
        }
        if (isTv) TextBesideCode(url, stringResource(Res.string.share_title_qr_description), text) else text()
        SnDialogActions {
            val canShare = !isTv && canShareText(context)
            SnButton(
                text = stringResource(Res.string.action_close),
                onClick = onDismiss,
                style = if (isTv) SnButtonStyle.Primary else SnButtonStyle.Ghost,
                modifier = if (isTv) Modifier.focusRequester(mainFocus) else Modifier,
            )
            if (!isTv) {
                SnButton(
                    text = stringResource(if (copied) Res.string.addons_copied else Res.string.addons_copy_link),
                    onClick = {
                        copyToClipboard(context, clipLabel, url)
                        copied = true
                    },
                    style = if (canShare) SnButtonStyle.Tonal else SnButtonStyle.Primary,
                    modifier = if (canShare) Modifier else Modifier.focusRequester(mainFocus),
                )
            }
            if (canShare) {
                SnButton(
                    text = stringResource(Res.string.action_share),
                    onClick = { shareText(context, name, url) },
                    icon = SnIcons.Share,
                    modifier = Modifier.focusRequester(mainFocus),
                )
            }
        }
    }
}
