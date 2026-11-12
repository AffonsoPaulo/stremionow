// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.affonsopaulo.stremionow.core.AddonInfo
import io.github.affonsopaulo.stremionow.core.CatalogAddon
import io.github.affonsopaulo.stremionow.ui.components.SnButton
import io.github.affonsopaulo.stremionow.ui.components.SnButtonStyle
import io.github.affonsopaulo.stremionow.ui.components.SnDialog
import io.github.affonsopaulo.stremionow.ui.components.SnIconButton
import io.github.affonsopaulo.stremionow.ui.components.SnSwitch
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography

internal fun AddonType.label() =
    when (this) {
        AddonType.Movies -> Res.string.kind_movies
        AddonType.Series -> Res.string.kind_series
        AddonType.Anime -> Res.string.kind_anime
        AddonType.Channels -> Res.string.kind_channels
        AddonType.Tv -> Res.string.kind_tv
        AddonType.Other -> Res.string.kind_other
    }

/** "Movies, Series & Anime": the kinds of content an addon declares, with Other last. */
@Composable
internal fun typesText(addon: AddonInfo): String? {
    val kinds = addonTypes(addon.types).map { stringResource(it.label()) }
    return naturalList(kinds)
}

/** "Catalogs, Information & Sources": what an addon provides. */
@Composable
internal fun providesText(addon: AddonInfo): String? =
    naturalList(
        listOfNotNull(
            stringResource(Res.string.addons_kind_catalogs).takeIf { addon.providesCatalogs },
            stringResource(Res.string.addons_kind_metadata).takeIf { addon.providesMetadata },
            stringResource(Res.string.addons_kind_streams).takeIf { addon.providesStreams },
            stringResource(Res.string.addons_kind_subtitles).takeIf { addon.providesSubtitles },
        ),
    )

/** Joins [items] as a sentence would: "A", "A & B", "A, B & C"; null when there are none. */
@Composable
private fun naturalList(items: List<String>): String? =
    when (items.size) {
        0 -> null
        1 -> items[0]
        else -> stringResource(Res.string.list_pair, items.dropLast(1).joinToString(", "), items.last())
    }

/**
 * An addon's logo, or its initial on a neutral tile while the logo loads, when it has none or
 * when it fails. Only HTTPS logos reach here (the core drops others).
 */
@Composable
internal fun AddonLogo(
    addon: AddonInfo,
    size: Dp,
) {
    val shape = RoundedCornerShape(SnRadius.radiusMd)
    var loaded by remember(addon.logo) { mutableStateOf(false) }
    val description = stringResource(Res.string.addons_logo, addon.name)
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(SnColors.surfaceMuted)
            .border(1.dp, SnColors.border, shape)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            val initial = addon.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "·"
            BasicText(initial, style = SnTypography.sectionTitle.copy(color = SnColors.fgSecondary))
        }
        if (addon.logo != null) {
            AsyncImage(
                model = addon.logo,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(size / 8),
                contentScale = ContentScale.Fit,
                onSuccess = { loaded = true },
                onError = { loaded = false },
            )
        }
    }
}

/** Name, version and what the addon covers: the top of both kinds of card. */
@Composable
private fun AddonHeading(
    addon: AddonInfo,
    dimmed: Boolean,
    modifier: Modifier = Modifier,
    badge: (@Composable () -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(SnSpacing.space1)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space2),
        ) {
            BasicText(
                addon.name,
                modifier = Modifier.weight(1f, fill = false),
                style = SnTypography.sectionTitle.copy(color = if (dimmed) SnColors.fgMuted else SnColors.fg),
                // Two lines before it is cut: names are what people look for in the list.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            badge?.invoke()
        }
        val version = addon.version.takeIf { it.isNotBlank() }?.let { stringResource(Res.string.addons_version, it) }
        val line = listOfNotNull(version, typesText(addon), providesText(addon)).joinToString(" · ")
        if (line.isNotEmpty()) {
            BasicText(
                line,
                style = SnTypography.caption.copy(color = SnColors.fgSecondary),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun InstalledAddonCard(
    addon: AddonInfo,
    removeFocus: FocusRequester,
    position: Int,
    canReorder: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onlyTitleMetadata: Boolean,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onShare: () -> Unit,
    onConfigure: () -> Unit,
) {
    val shape = RoundedCornerShape(SnRadius.radiusLg)
    // Removing is confirmed in a dialog, so a slip of the finger cannot drop an addon; so is
    // turning off the only addon that lets titles open.
    var confirmingRemoval by rememberSaveable(addon.manifestUrl) { mutableStateOf(false) }
    var confirmingDisable by rememberSaveable(addon.manifestUrl) { mutableStateOf(false) }
    // A moved card keeps the focus but can land outside the list's view (the list holds on to
    // the card that was at its top); bring it back so the focus never goes unseen.
    val bringIntoView = remember { BringIntoViewRequester() }
    var holdsFocus by remember { mutableStateOf(false) }
    LaunchedEffect(position) { if (holdsFocus) bringIntoView.bringIntoView() }

    // Stacked layout: logo, name, details and switch first, then the less frequent actions in
    // their own row, so nothing is squeezed on a phone and the order still reads well on a TV.
    Column(
        Modifier
            .bringIntoViewRequester(bringIntoView)
            .onFocusChanged { holdsFocus = it.hasFocus }
            .fillMaxWidth()
            .background(SnColors.surface, shape)
            .border(1.dp, SnColors.border, shape)
            .padding(start = SnSpacing.space4, end = SnSpacing.space4, top = SnSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space3),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3)) {
            AddonLogo(addon, size = 48.dp)
            AddonHeading(addon, dimmed = !addon.enabled, modifier = Modifier.weight(1f))
            SnSwitch(
                checked = addon.enabled,
                label = stringResource(Res.string.addons_toggle, addon.name),
                onText = stringResource(Res.string.state_on),
                offText = stringResource(Res.string.state_off),
                onToggle = { if (onlyTitleMetadata && addon.enabled) confirmingDisable = true else onToggle() },
            )
        }
        if (addon.description.isNotBlank()) {
            BasicText(
                addon.description,
                style = SnTypography.body.copy(color = SnColors.fgMuted),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(SnColors.border))
        // Where the labeled actions would not fit (phones, narrow windows, long translations),
        // all but Remove show only their icon.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < CompactActionsWidth
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = SnSpacing.space2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SnSpacing.space1),
            ) {
                if (canReorder) {
                    SnIconButton(
                        SnIcons.ChevronUp,
                        stringResource(Res.string.addons_move_up, addon.name),
                        onMoveUp,
                        tint = SnColors.fgSecondary,
                        enabled = canMoveUp,
                    )
                    SnIconButton(
                        SnIcons.ChevronDown,
                        stringResource(Res.string.addons_move_down, addon.name),
                        onMoveDown,
                        tint = SnColors.fgSecondary,
                        enabled = canMoveDown,
                    )
                }
                Box(Modifier.weight(1f))
                if (addon.configureUrl != null) {
                    CardAction(
                        compact,
                        SnIcons.Settings,
                        stringResource(Res.string.addons_configure),
                        stringResource(Res.string.addons_configure_named, addon.name),
                        onConfigure,
                    )
                }
                CardAction(
                    compact,
                    SnIcons.Share,
                    stringResource(Res.string.addons_share),
                    stringResource(Res.string.addons_share_named, addon.name),
                    onShare,
                )
                CardAction(
                    compact,
                    SnIcons.Trash,
                    stringResource(Res.string.addons_remove_action),
                    stringResource(Res.string.addons_remove, addon.name),
                    { confirmingRemoval = true },
                    // Remove keeps its word: it is the one that cannot be undone.
                    alwaysLabeled = true,
                    modifier = Modifier.focusRequester(removeFocus),
                )
            }
        }
    }
    if (confirmingRemoval) {
        SnDialog(
            title = stringResource(Res.string.addons_remove_title, addon.name),
            body =
                stringResource(
                    if (onlyTitleMetadata) Res.string.addons_remove_meta_body else Res.string.addons_remove_body,
                ),
            confirmText = stringResource(Res.string.addons_remove_action),
            dismissText = stringResource(Res.string.action_cancel),
            onConfirm = {
                confirmingRemoval = false
                onRemove()
            },
            onDismiss = { confirmingRemoval = false },
            destructive = true,
        )
    }
    if (confirmingDisable) {
        SnDialog(
            title = stringResource(Res.string.addons_disable_title, addon.name),
            body = stringResource(Res.string.addons_meta_warning),
            confirmText = stringResource(Res.string.addons_turn_off),
            dismissText = stringResource(Res.string.action_cancel),
            onConfirm = {
                confirmingDisable = false
                onToggle()
            },
            onDismiss = { confirmingDisable = false },
            destructive = true,
        )
    }
}

/** Narrowest action row that holds every action with its label. */
private val CompactActionsWidth = 600.dp

/** A secondary action of a card: a labeled ghost button, or only its icon where room is short. */
@Composable
private fun CardAction(
    compact: Boolean,
    icon: ImageVector,
    text: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    alwaysLabeled: Boolean = false,
) {
    if (compact && !alwaysLabeled) {
        SnIconButton(icon, label, onClick, modifier, tint = SnColors.fgSecondary)
    } else {
        SnButton(
            text = text,
            onClick = onClick,
            style = SnButtonStyle.Ghost,
            icon = icon,
            modifier = modifier.semantics { contentDescription = label },
        )
    }
}

@Composable
internal fun DiscoverAddonCard(
    entry: CatalogAddon,
    installed: Boolean,
    checking: Boolean,
    failed: Boolean,
    onInstall: () -> Unit,
    onConfigure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val addon = entry.addon
    val shape = RoundedCornerShape(SnRadius.radiusLg)
    Column(
        modifier
            .fillMaxWidth()
            .background(SnColors.surface, shape)
            .border(1.dp, SnColors.border, shape)
            .padding(SnSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space3),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3)) {
            AddonLogo(addon, size = 48.dp)
            AddonHeading(
                addon,
                dimmed = false,
                modifier = Modifier.weight(1f),
                badge = if (entry.official) ({ Badge(stringResource(Res.string.addons_official)) }) else null,
            )
        }
        if (addon.description.isNotBlank()) {
            BasicText(
                addon.description,
                style = SnTypography.body.copy(color = SnColors.fgMuted),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The row's cards share a height; the actions sit at the bottom so they line up.
        Spacer(Modifier.weight(1f))
        if (failed) {
            BasicText(
                stringResource(Res.string.addons_install_failed),
                style = SnTypography.caption.copy(color = SnColors.danger),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space2),
        ) {
            // One button changes with the state instead of being swapped for another, so on TV
            // the focus stays on it when an install finishes.
            val needsSetup = addon.configurationRequired && !installed
            val mainLabel =
                when {
                    installed -> stringResource(Res.string.addons_installed)
                    needsSetup -> stringResource(Res.string.addons_configure_named, addon.name)
                    else -> stringResource(Res.string.addons_install_named, addon.name)
                }
            SnButton(
                text =
                    stringResource(
                        when {
                            installed -> Res.string.addons_installed
                            needsSetup -> Res.string.addons_configure
                            checking -> Res.string.addons_checking
                            else -> Res.string.addons_install_action
                        },
                    ),
                onClick = if (needsSetup) onConfigure else onInstall,
                style = if (installed) SnButtonStyle.Ghost else SnButtonStyle.Tonal,
                icon =
                    when {
                        installed -> SnIcons.Check
                        needsSetup -> SnIcons.Settings
                        else -> SnIcons.Plus
                    },
                enabled = !installed && !checking,
                modifier = Modifier.semantics { contentDescription = mainLabel },
            )
            if (!installed && !needsSetup && addon.configurable) {
                val configureLabel = stringResource(Res.string.addons_configure_named, addon.name)
                SnButton(
                    text = stringResource(Res.string.addons_configure),
                    onClick = onConfigure,
                    style = SnButtonStyle.Ghost,
                    icon = SnIcons.Settings,
                    modifier = Modifier.semantics { contentDescription = configureLabel },
                )
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    BasicText(
        text,
        modifier =
            Modifier
                .background(SnColors.accentSurface, RoundedCornerShape(SnRadius.radiusSm))
                .border(1.dp, SnColors.accentBorder, RoundedCornerShape(SnRadius.radiusSm))
                .padding(horizontal = SnSpacing.space2, vertical = 2.dp),
        style = SnTypography.badge.copy(color = SnColors.accentText),
        maxLines = 1,
    )
}
