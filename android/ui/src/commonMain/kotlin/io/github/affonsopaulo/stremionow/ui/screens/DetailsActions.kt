// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSize
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography

/** What the action row shows and does; null actions are left out. */
internal class DetailActions(
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

/**
 * The actions of the official app under a title's buttons, as icons with their names:
 * trailer, My list, mark as watched, like, love and share. A row that wraps on narrow screens.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActionRow(
    actions: DetailActions,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space2),
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space2),
    ) {
        actions.onTrailer?.let { ActionButton(SnIcons.Film, stringResource(Res.string.action_trailer), onClick = it) }
        ActionButton(
            icon = if (actions.inLibrary) SnIcons.Check else SnIcons.Plus,
            label = stringResource(if (actions.inLibrary) Res.string.action_in_list else Res.string.action_add_to_list),
            selected = actions.inLibrary,
            onClick = actions.onToggleLibrary,
        )
        ActionButton(
            icon = if (actions.watched) SnIcons.CheckCircle else SnIcons.Eye,
            label = stringResource(if (actions.watched) Res.string.action_watched else Res.string.action_mark_watched),
            selected = actions.watched,
            onClick = actions.onWatched,
        )
        val liked = actions.rating == TitleRating.LIKED
        val loved = actions.rating == TitleRating.LOVED
        ActionButton(
            icon = if (liked) SnIcons.ThumbsUpFilled else SnIcons.ThumbsUp,
            label = stringResource(Res.string.action_like),
            selected = liked,
            onClick = { actions.onRate(if (liked) TitleRating.NONE else TitleRating.LIKED) },
        )
        ActionButton(
            icon = if (loved) SnIcons.HeartFilled else SnIcons.Heart,
            label = stringResource(Res.string.action_love),
            selected = loved,
            onClick = { actions.onRate(if (loved) TitleRating.NONE else TitleRating.LOVED) },
        )
        actions.onShare?.let { ActionButton(SnIcons.Share, stringResource(Res.string.action_share), onClick = it) }
    }
}

/** An icon in a round tile with its name under it; the whole is one target. */
@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(SnRadius.radiusMd)
    Column(
        modifier =
            Modifier
                .width(ActionWidth)
                .semantics { this.selected = selected }
                .snClickable(shape, role = Role.Button, onClick = onClick)
                .padding(vertical = SnSpacing.space1),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space1),
    ) {
        Box(
            Modifier
                .size(SnSize.controlMd)
                .background(
                    if (selected) SnColors.fg else SnColors.overlayLight,
                    RoundedCornerShape(SnRadius.radiusFull),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                colorFilter = ColorFilter.tint(if (selected) SnColors.onInverse else SnColors.fg),
            )
        }
        BasicText(
            label,
            style = SnTypography.caption.copy(color = SnColors.fgSecondary, textAlign = TextAlign.Center),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Width of each action, so labels of two lines line up. */
private val ActionWidth = 84.dp

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
