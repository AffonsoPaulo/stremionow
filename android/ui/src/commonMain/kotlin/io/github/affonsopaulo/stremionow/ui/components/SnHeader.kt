// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
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
enum class TopDestination { Search, Home, Series, Movies, MyList, Profile }

/** Destinations shown as pills in the large-screen header, between search and the avatar. */
private val HeaderPills =
    listOf(TopDestination.Home, TopDestination.Series, TopDestination.Movies, TopDestination.MyList)

/** Main navigation on large screens and TV: wordmark, search, destination pills and the avatar. */
@Composable
fun SnHeader(
    current: TopDestination?,
    onNavigate: (TopDestination) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val pill = RoundedCornerShape(SnRadius.radiusFull)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = SnLayout.gutter, vertical = SnSpacing.space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space4),
    ) {
        // Inner pages on a mouse-and-keyboard screen get a visible way back; a TV has its
        // remote's Back button and a phone its own top bar.
        if (onBack != null) {
            SnIconButton(SnIcons.ChevronLeft, stringResource(Res.string.nav_back), onClick = onBack)
        }
        SnWordmark()
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space1, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item {
                SnIconButton(
                    icon = SnIcons.Search,
                    label = stringResource(Res.string.nav_search),
                    onClick = { onNavigate(TopDestination.Search) },
                    tint = if (current == TopDestination.Search) SnColors.onInverse else SnColors.fgSecondary,
                    background =
                        if (current ==
                            TopDestination.Search
                        ) {
                            SnColors.fg
                        } else {
                            androidx.compose.ui.graphics.Color.Transparent
                        },
                )
            }
            items(HeaderPills) { destination ->
                val selected = destination == current
                Box(
                    modifier =
                        Modifier
                            .heightIn(min = SnSize.controlMd)
                            .semantics { this.selected = selected }
                            .snClickable(pill, role = Role.Tab) { onNavigate(destination) }
                            .background(
                                if (selected) SnColors.fg else androidx.compose.ui.graphics.Color.Transparent,
                                pill,
                            ).padding(horizontal = SnSpacing.space4),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = stringResource(destination.label()),
                        style =
                            SnTypography.label.copy(
                                color = if (selected) SnColors.onInverse else SnColors.fgSecondary,
                            ),
                        maxLines = 1,
                    )
                }
            }
        }
        SnAvatarButton(selected = current == TopDestination.Profile, onClick = { onNavigate(TopDestination.Profile) })
    }
}

internal fun TopDestination.label(): StringResource =
    when (this) {
        TopDestination.Search -> Res.string.nav_search
        TopDestination.Home -> Res.string.nav_home
        TopDestination.Series -> Res.string.nav_series
        TopDestination.Movies -> Res.string.nav_movies
        TopDestination.MyList -> Res.string.nav_my_list
        TopDestination.Profile -> Res.string.profile_title
    }

/**
 * Top bar of the phone layout, where the tab bar holds the main destinations. It carries the
 * page title, so pages do not repeat it below: the wordmark on Home, the page name elsewhere,
 * with a back button before it on inner pages, and the avatar for the profile and settings.
 */
@Composable
fun SnPhoneHeader(
    title: String?,
    onBack: (() -> Unit)?,
    profileSelected: Boolean,
    onOpenProfile: () -> Unit,
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
        SnAvatarButton(selected = profileSelected, onClick = onOpenProfile)
    }
}

/**
 * The profile avatar. Without a Stremio account there is no picture, so it shows a person
 * glyph; the light fill marks it as the current destination, like the pills.
 */
@Composable
fun SnAvatarButton(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val circle = RoundedCornerShape(SnRadius.radiusFull)
    val label = stringResource(Res.string.nav_profile)
    Box(
        modifier =
            modifier
                .size(SnSize.controlMd)
                .semantics { this.selected = selected }
                .snClickable(circle, onClick = onClick)
                .background(if (selected) SnColors.fg else SnColors.surfaceRaised, circle)
                .border(1.dp, if (selected) SnColors.fg else SnColors.border, circle),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            SnIcons.User,
            contentDescription = label,
            modifier = Modifier.size(20.dp),
            colorFilter = ColorFilter.tint(if (selected) SnColors.onInverse else SnColors.fgSecondary),
        )
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
