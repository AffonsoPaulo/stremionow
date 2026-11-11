// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography

/** Destinations shown in the phone tab bar, in order. The profile lives in the top bar. */
val PhoneTabs =
    listOf(
        TopDestination.Home,
        TopDestination.Series,
        TopDestination.Movies,
        TopDestination.Search,
        TopDestination.MyList,
    )

/**
 * Bottom navigation of the phone layout: always-visible labels under the icons, so every
 * destination reads at a glance, and a full-width hit area per tab for one-handed use.
 */
@Composable
fun SnTabBar(
    current: TopDestination?,
    onNavigate: (TopDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().background(SnColors.bg)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(SnColors.border))
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                    .padding(horizontal = SnSpacing.space1),
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space1),
        ) {
            for (destination in PhoneTabs) {
                Tab(
                    icon = destination.icon(),
                    label = stringResource(destination.label()),
                    selected = destination == current,
                    onClick = { onNavigate(destination) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Tab(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (selected) SnColors.fg else SnColors.fgMuted
    Column(
        modifier =
            modifier
                .heightIn(min = 64.dp)
                .semantics(mergeDescendants = true) { this.selected = selected }
                .snClickable(RoundedCornerShape(SnRadius.radiusMd), role = Role.Tab, grow = false, onClick = onClick)
                .padding(vertical = SnSpacing.space2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space1, Alignment.CenterVertically),
    ) {
        Box(
            modifier =
                Modifier
                    .background(
                        if (selected) SnColors.surfaceSelected else androidx.compose.ui.graphics.Color.Transparent,
                        RoundedCornerShape(SnRadius.radiusFull),
                    ).padding(horizontal = SnSpacing.space4, vertical = SnSpacing.space1),
        ) {
            Image(
                icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                colorFilter = ColorFilter.tint(color),
            )
        }
        BasicText(
            text = label,
            style = SnTypography.caption.copy(color = color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun TopDestination.icon(): ImageVector =
    when (this) {
        TopDestination.Search -> SnIcons.Search
        TopDestination.Home -> SnIcons.Home
        TopDestination.Series -> SnIcons.Tv
        TopDestination.Movies -> SnIcons.Film
        TopDestination.MyList -> SnIcons.Bookmark
        TopDestination.Profile -> SnIcons.User
    }
