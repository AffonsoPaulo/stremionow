// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.affonsopaulo.stremionow.core.StreamSource
import io.github.affonsopaulo.stremionow.ui.components.SnIconButton
import io.github.affonsopaulo.stremionow.ui.components.SnTextField
import io.github.affonsopaulo.stremionow.ui.components.SnTextLink
import io.github.affonsopaulo.stremionow.ui.components.collectSnInteraction
import io.github.affonsopaulo.stremionow.ui.components.snClickable
import io.github.affonsopaulo.stremionow.ui.components.snStateBackground
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.pluralStringResource
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSize
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography
import java.util.Locale

/**
 * Over the sources: the search, a chip for each filter that has something to choose between,
 * "Clear filters" once any is set, and the order. Large screens and TV wrap them over as many
 * lines as they need with the order at the end; a phone stacks the search over a row of chips
 * that scrolls sideways.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SourcesToolbar(
    sources: List<StreamSource>,
    filters: SourceFilters,
    onFilters: (SourceFilters) -> Unit,
    phone: Boolean,
) {
    val chips: @Composable () -> Unit = {
        SourceFacet.entries.forEach { facet ->
            val options = facetOptions(sources, filters, facet)
            // A filter with one option has nothing to choose between.
            if (options.size > 1 || filters.selected(facet).isNotEmpty()) {
                FacetChip(facet, options, onToggle = { onFilters(filters.toggle(facet, it)) })
            }
        }
        if (filters.anySelected) {
            SnTextLink(
                stringResource(Res.string.sources_clear_filters),
                onClick = { onFilters(filters.cleared()) },
                modifier = Modifier.padding(horizontal = SnSpacing.space2, vertical = SnSpacing.space2),
                style = SnTypography.label,
                color = SnColors.fg,
            )
        }
    }
    val sort: @Composable () -> Unit = { SortMenu(filters.sort, onSort = { onFilters(filters.copy(sort = it)) }) }
    if (phone) {
        Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space3)) {
            SourcesSearch(filters, onFilters, Modifier.padding(horizontal = SnLayout.gutter).fillMaxWidth())
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = SnLayout.gutter),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SnSpacing.space2),
            ) {
                sort()
                chips()
            }
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = SnLayout.gutter),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space4),
    ) {
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space2),
            verticalArrangement = Arrangement.spacedBy(SnSpacing.space2),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            SourcesSearch(filters, onFilters, Modifier.width(SearchWidth))
            chips()
        }
        Box(Modifier.heightIn(min = SnSize.controlLg), contentAlignment = Alignment.Center) { sort() }
    }
}

private val SearchWidth = 280.dp

/**
 * The search over the sources: it narrows them as the user types. The clear button and Esc
 * empty it; Esc on an empty search goes back as anywhere else.
 */
@Composable
private fun SourcesSearch(
    filters: SourceFilters,
    onFilters: (SourceFilters) -> Unit,
    modifier: Modifier,
) {
    val label = stringResource(Res.string.sources_search)
    val focus = remember { FocusRequester() }
    // The key's release after Esc cleared the search is the same press, not a Back.
    var clearing by remember { mutableStateOf(false) }
    val clear = { onFilters(filters.copy(query = "")) }
    SnTextField(
        value = filters.query,
        onValueChange = { onFilters(filters.copy(query = it.take(MAX_QUERY_LENGTH))) },
        label = label,
        hint = label,
        leadingIcon = SnIcons.Search,
        imeAction = ImeAction.Search,
        focusRequester = focus,
        modifier =
            modifier.onPreviewKeyEvent { event ->
                if (event.key != Key.Escape) return@onPreviewKeyEvent false
                when {
                    event.type == KeyEventType.KeyDown && filters.query.isNotEmpty() -> {
                        clearing = true
                        clear()
                        true
                    }

                    event.type == KeyEventType.KeyUp && clearing -> {
                        clearing = false
                        true
                    }

                    else -> {
                        false
                    }
                }
            },
        trailing =
            if (filters.query.isEmpty()) {
                null
            } else {
                {
                    SnIconButton(SnIcons.Close, stringResource(Res.string.sources_search_clear), {
                        clear()
                        // The button goes away with the text; the field keeps the focus instead.
                        runCatching { focus.requestFocus() }
                    }, tint = SnColors.fgSecondary)
                }
            },
    )
}

/** Longest search kept; release names are far shorter. */
private const val MAX_QUERY_LENGTH = 200

/** A filter's chip: its name, how many of its options are ticked, and the list to tick them in. */
@Composable
private fun FacetChip(
    facet: SourceFacet,
    options: List<FacetOption>,
    onToggle: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val chipFocus = remember { FocusRequester() }
    val name = stringResource(facet.label())
    val chosen = options.count { it.selected }
    val chosenState = pluralStringResource(Res.plurals.sources_facet_chosen_count, chosen, chosen)
    Box {
        MenuChip(
            text = if (chosen == 0) name else stringResource(Res.string.sources_facet_chosen, name, chosen),
            active = chosen > 0,
            onClick = { open = true },
            modifier =
                Modifier
                    .focusRequester(chipFocus)
                    .semantics { if (chosen > 0) stateDescription = chosenState },
        )
        if (open) {
            val close = {
                open = false
                runCatching { chipFocus.requestFocus() }
                Unit
            }
            MenuPopup(onDismiss = close) { first ->
                options.forEachIndexed { index, option ->
                    val label = optionLabel(facet, option.value)
                    MenuOption(
                        label = label,
                        count = option.count,
                        checked = option.selected,
                        multiple = true,
                        onClick = { onToggle(option.value) },
                        modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                    )
                }
            }
        }
    }
}

/** "Sort: Best", and the orders to choose from. */
@Composable
private fun SortMenu(
    sort: SourceSort,
    onSort: (SourceSort) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val chipFocus = remember { FocusRequester() }
    Box {
        MenuChip(
            text = stringResource(Res.string.sources_sort, stringResource(sort.label())),
            active = false,
            onClick = { open = true },
            modifier = Modifier.focusRequester(chipFocus),
        )
        if (open) {
            val close = {
                open = false
                runCatching { chipFocus.requestFocus() }
                Unit
            }
            MenuPopup(onDismiss = close) { first ->
                SourceSort.entries.forEachIndexed { index, option ->
                    MenuOption(
                        label = stringResource(option.label()),
                        count = null,
                        checked = option == sort,
                        multiple = false,
                        onClick = {
                            onSort(option)
                            close()
                        },
                        modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                    )
                }
            }
        }
    }
}

/** A pill with a downward chevron that opens a menu; light while its filter is set. */
@Composable
private fun MenuChip(
    text: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(SnRadius.radiusFull)
    val interaction = remember { MutableInteractionSource() }
    val state = interaction.collectSnInteraction()
    val color = if (active) SnColors.onInverse else SnColors.fgSecondary
    Row(
        modifier =
            modifier
                .heightIn(min = SnSize.controlSm)
                .snClickable(shape, highlight = false, interactionSource = interaction, onClick = onClick)
                .snStateBackground(if (active) SnColors.fg else SnColors.surfaceMuted, shape, state)
                .padding(start = SnSpacing.space4, end = SnSpacing.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space1),
    ) {
        BasicText(text, style = SnTypography.label.copy(color = color), maxLines = 1)
        Image(SnIcons.ChevronDown, null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(color))
    }
}

/**
 * A menu under its chip: a raised panel that scrolls when its options do not fit. It takes
 * the focus, its first option first ([content] gets the requester to put on it); Esc, Back or
 * a click outside close it.
 */
@Composable
private fun MenuPopup(
    onDismiss: () -> Unit,
    content: @Composable (first: FocusRequester) -> Unit,
) {
    val gap = with(LocalDensity.current) { SnSpacing.space2.roundToPx() }
    val position = remember(gap) { BelowAnchorStart(gap) }
    val first = remember { FocusRequester() }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        val shape = RoundedCornerShape(SnRadius.radiusLg)
        Column(
            Modifier
                .widthIn(min = MenuMinWidth, max = MenuMaxWidth)
                .heightIn(max = MenuMaxHeight)
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.Escape) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown) onDismiss()
                    true
                }.background(SnColors.surfaceRaised, shape)
                .border(1.dp, SnColors.border, shape)
                .verticalScroll(rememberScrollState())
                .padding(vertical = SnSpacing.space2),
        ) {
            content(first)
        }
    }
}

private val MenuMinWidth = 220.dp
private val MenuMaxWidth = 320.dp
private val MenuMaxHeight = 400.dp

/**
 * One option of a menu: a box ticked when chosen ([multiple]), or a tick beside the one order
 * chosen, its label, and how many sources it would show when [count] is given.
 */
@Composable
private fun MenuOption(
    label: String,
    count: Int?,
    checked: Boolean,
    multiple: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(SnRadius.radiusMd)
    val interaction = remember { MutableInteractionSource() }
    val state = interaction.collectSnInteraction()
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = SnSize.controlMd)
                .padding(horizontal = SnSpacing.space2)
                .semantics(mergeDescendants = true) {
                    if (multiple) toggleableState = ToggleableState(checked) else selected = checked
                }.snClickable(
                    shape,
                    role = if (multiple) Role.Checkbox else Role.RadioButton,
                    highlight = false,
                    interactionSource = interaction,
                    onClick = onClick,
                ).snStateBackground(SnColors.surfaceRaised, shape, state)
                .padding(horizontal = SnSpacing.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
    ) {
        if (multiple) {
            val box = RoundedCornerShape(SnRadius.radiusSm)
            Box(
                Modifier
                    .size(18.dp)
                    .background(if (checked) SnColors.fg else SnColors.surfaceRaised, box)
                    .border(1.dp, if (checked) SnColors.fg else SnColors.borderStrong, box),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) {
                    Image(SnIcons.Check, null, Modifier.size(14.dp), colorFilter = ColorFilter.tint(SnColors.onInverse))
                }
            }
        } else {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                if (checked) {
                    Image(
                        SnIcons.Check,
                        null,
                        Modifier.size(18.dp),
                        colorFilter = ColorFilter.tint(SnColors.fg),
                    )
                }
            }
        }
        BasicText(
            label,
            modifier = Modifier.weight(1f),
            style = SnTypography.body.copy(color = SnColors.fg),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count != null) {
            BasicText(count.toString(), style = SnTypography.caption.copy(color = SnColors.fgMuted))
        }
    }
}

/** What an option of [facet] reads: a language's name, a kind of source, or the value itself. */
@Composable
private fun optionLabel(
    facet: SourceFacet,
    value: String,
): String =
    when (facet) {
        SourceFacet.Language -> {
            remember(value) { languageName(value, Locale.getDefault()) }
        }

        SourceFacet.Hdr -> {
            when (value) {
                "DV" -> stringResource(Res.string.sources_hdr_dolby_vision)
                SDR -> stringResource(Res.string.sources_hdr_sdr)
                else -> value
            }
        }

        SourceFacet.Type -> {
            when (value) {
                SourceType.InApp.name -> stringResource(Res.string.sources_type_in_app)
                SourceType.Torrent.name -> stringResource(Res.string.sources_type_torrent)
                SourceType.Outside.name -> stringResource(Res.string.sources_type_outside)
                else -> value
            }
        }

        SourceFacet.Resolution, SourceFacet.Addon -> {
            value
        }
    }

private fun SourceFacet.label() =
    when (this) {
        SourceFacet.Resolution -> Res.string.sources_facet_resolution
        SourceFacet.Addon -> Res.string.sources_facet_addon
        SourceFacet.Hdr -> Res.string.sources_facet_hdr
        SourceFacet.Language -> Res.string.sources_facet_language
        SourceFacet.Type -> Res.string.sources_facet_type
    }

private fun SourceSort.label() =
    when (this) {
        SourceSort.Best -> Res.string.sources_sort_best
        SourceSort.Size -> Res.string.sources_sort_size
        SourceSort.Seeds -> Res.string.sources_sort_seeds
        SourceSort.Resolution -> Res.string.sources_sort_resolution
    }

/** Below the anchor, its start edge lined up with the anchor's, kept inside the window. */
private class BelowAnchorStart(
    private val gap: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x =
            if (layoutDirection == LayoutDirection.Ltr) {
                anchorBounds.left
            } else {
                anchorBounds.right - popupContentSize.width
            }
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        // Above the chip when the menu does not fit below it.
        val below = anchorBounds.bottom + gap
        val y =
            if (below + popupContentSize.height > windowSize.height &&
                anchorBounds.top - gap - popupContentSize.height >= 0
            ) {
                anchorBounds.top - gap - popupContentSize.height
            } else {
                below
            }
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
    }
}
