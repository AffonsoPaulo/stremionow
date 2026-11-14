// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.StringResource
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.theme.FormFactor
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSize
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography

/** Destinations of the main navigation. */
enum class TopDestination { Search, Home, Series, Movies, MyList }

/** Destinations shown as pills in the large-screen header, between search and the avatar. */
private val HeaderPills =
    listOf(TopDestination.Home, TopDestination.Series, TopDestination.Movies, TopDestination.MyList)

/**
 * Main navigation on large screens and TV: wordmark, destination pills centred on the window
 * and the avatar.
 *
 * Where a pointer and keyboard drive the screen, [search] is given: the search field that
 * sits just before the avatar and expands in place. On TV it is null, and search is the first
 * stop of the navigation row, where the remote reaches it like any other destination.
 *
 * The avatar calls [onAvatar]: it opens the account menu, composed in [avatarMenu] so it can
 * anchor to the avatar, or Settings on TV. [avatarSelected] marks it while Settings shows.
 */
@Composable
fun SnHeader(
    current: TopDestination?,
    onNavigate: (TopDestination) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    search: (@Composable () -> Unit)? = null,
    avatarSelected: Boolean = false,
    onAvatar: () -> Unit = {},
    avatarMenu: @Composable () -> Unit = {},
) {
    val spacing = SnSpacing.space4
    val trailingGap = SnSpacing.space3
    Layout(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = SnLayout.gutter, vertical = SnSpacing.space4),
        content = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                // Inner pages on a mouse-and-keyboard screen get a visible way back; a TV has its
                // remote's Back button and a phone its own top bar.
                if (onBack != null) {
                    SnIconButton(SnIcons.ChevronLeft, stringResource(Res.string.nav_back), onClick = onBack)
                }
                SnWordmark()
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(SnSpacing.space1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (search == null) {
                    SnIconButton(
                        icon = SnIcons.Search,
                        label = stringResource(Res.string.nav_search),
                        onClick = { onNavigate(TopDestination.Search) },
                        tint = if (current == TopDestination.Search) SnColors.onInverse else SnColors.fgSecondary,
                        background = if (current == TopDestination.Search) SnColors.fg else Color.Transparent,
                    )
                }
                HeaderPills.forEach { destination ->
                    HeaderPill(destination, selected = destination == current) { onNavigate(destination) }
                }
            }
            // Before the avatar, so keyboard focus meets them in the order they show.
            search?.invoke()
            SnAvatarButton(selected = avatarSelected, onClick = onAvatar, menu = avatarMenu)
        },
    ) { measurables, constraints ->
        // The navigation stays centred on the window whatever sits at its sides (the back
        // button comes and goes, the search field grows), and only moves aside when it would
        // touch its neighbours.
        val gap = spacing.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val width = constraints.maxWidth
        val leading = measurables[0].measure(loose)
        val avatar = measurables.last().measure(loose)
        // The search field takes what the pills leave, but never less than its minimum.
        val searchPlaceable =
            measurables.getOrNull(2)?.takeIf { measurables.size == 4 }?.let { field ->
                val navigationWidth = measurables[1].maxIntrinsicWidth(avatar.height)
                val left = width - leading.width - navigationWidth - avatar.width - 2 * gap - trailingGap.roundToPx()
                val room = left.coerceAtLeast(HeaderSearchMinWidth.roundToPx()).coerceAtMost(width)
                field.measure(loose.copy(maxWidth = room))
            }
        val trailingWidth = avatar.width + (searchPlaceable?.let { it.width + trailingGap.roundToPx() } ?: 0)
        val room = (width - leading.width - trailingWidth - 2 * gap).coerceAtLeast(0)
        val navigation = measurables[1].measure(loose.copy(maxWidth = room))
        val height = maxOf(leading.height, navigation.height, avatar.height, searchPlaceable?.height ?: 0)
        layout(width, height) {
            val centred = (width - navigation.width) / 2
            val navigationX =
                centred.coerceIn(
                    leading.width + gap,
                    (width - trailingWidth - gap - navigation.width).coerceAtLeast(leading.width + gap),
                )
            leading.placeRelative(0, (height - leading.height) / 2)
            navigation.placeRelative(navigationX, (height - navigation.height) / 2)
            avatar.placeRelative(width - avatar.width, (height - avatar.height) / 2)
            searchPlaceable?.placeRelative(
                width - trailingWidth,
                (height - searchPlaceable.height) / 2,
            )
        }
    }
}

/**
 * A destination pill. The selected one is filled white and keeps that look under the pointer;
 * the others get a translucent fill.
 */
@Composable
private fun HeaderPill(
    destination: TopDestination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val pill = RoundedCornerShape(SnRadius.radiusFull)
    val interaction = remember { MutableInteractionSource() }
    val state = interaction.collectSnInteraction()
    Box(
        modifier =
            Modifier
                .heightIn(min = SnSize.controlMd)
                .semantics { this.selected = selected }
                .snClickable(
                    pill,
                    role = Role.Tab,
                    highlight = false,
                    interactionSource = interaction,
                    onClick = onClick,
                ).snStateBackground(if (selected) SnColors.fg else Color.Transparent, pill, state)
                .padding(horizontal = SnSpacing.space4),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = stringResource(destination.label()),
            style = SnTypography.label.copy(color = if (selected) SnColors.onInverse else SnColors.fgSecondary),
            maxLines = 1,
        )
    }
}

internal fun TopDestination.label(): StringResource =
    when (this) {
        TopDestination.Search -> Res.string.nav_search
        TopDestination.Home -> Res.string.nav_home
        TopDestination.Series -> Res.string.nav_series
        TopDestination.Movies -> Res.string.nav_movies
        TopDestination.MyList -> Res.string.nav_my_list
    }

/**
 * Top bar of the phone layout, where the tab bar holds the main destinations. It carries the
 * page title, so pages do not repeat it below: the wordmark on Home, the page name elsewhere,
 * with a back button before it on inner pages, and the avatar, which opens the account sheet.
 */
@Composable
fun SnPhoneHeader(
    title: String?,
    onBack: (() -> Unit)?,
    accountSelected: Boolean,
    onOpenAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = SnLayout.gutter, vertical = SnSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space2),
    ) {
        if (onBack != null) {
            SnIconButton(SnIcons.ChevronLeft, stringResource(Res.string.nav_back), onClick = onBack)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            when {
                title == null -> {
                    SnWordmark()
                }

                title.isNotEmpty() -> {
                    BasicText(
                        title,
                        modifier = Modifier.semantics { heading() },
                        style = SnTypography.headingLg.copy(color = SnColors.fg),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // Long titles (and long translations) shrink before they get cut.
                        autoSize =
                            TextAutoSize.StepBased(
                                minFontSize = 20.sp,
                                maxFontSize = SnTypography.headingLg.fontSize,
                            ),
                    )
                }
            }
        }
        SnAvatarButton(selected = accountSelected, onClick = onOpenAccount)
    }
}

/**
 * The account avatar. Without a Stremio account there is no picture, so it shows a person
 * glyph; the light fill marks Settings as the current page, like the pills. [menu] is composed
 * inside it, so a popup there anchors to the avatar.
 */
@Composable
fun SnAvatarButton(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    menu: @Composable () -> Unit = {},
) {
    val circle = RoundedCornerShape(SnRadius.radiusFull)
    val label = stringResource(Res.string.nav_profile)
    val interaction = remember { MutableInteractionSource() }
    val state = interaction.collectSnInteraction()
    Box(
        modifier =
            modifier
                .size(SnSize.controlMd)
                .semantics { this.selected = selected }
                .snClickable(circle, highlight = false, interactionSource = interaction, onClick = onClick)
                .snStateBackground(if (selected) SnColors.fg else SnColors.surfaceRaised, circle, state)
                .border(1.dp, if (selected) SnColors.fg else SnColors.border, circle),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            SnIcons.User,
            contentDescription = label,
            modifier = Modifier.size(20.dp),
            colorFilter = ColorFilter.tint(if (selected) SnColors.onInverse else SnColors.fgSecondary),
        )
        menu()
    }
}

/**
 * Title at the top of a page on large screens and TV. On phones the top bar already shows
 * it, so this draws nothing there.
 */
@Composable
fun SnPageTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    if (SnLayout.formFactor == FormFactor.Phone) return
    BasicText(
        text,
        modifier = modifier.semantics { heading() },
        style = SnTypography.pageTitle.copy(color = SnColors.fg),
    )
}
