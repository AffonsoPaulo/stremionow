// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.affonsopaulo.stremionow.core.AddonInfo
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.ui.R
import io.github.affonsopaulo.stremionow.ui.components.SnButton
import io.github.affonsopaulo.stremionow.ui.components.SnButtonStyle
import io.github.affonsopaulo.stremionow.ui.components.SnDialog
import io.github.affonsopaulo.stremionow.ui.components.SnIconButton
import io.github.affonsopaulo.stremionow.ui.components.SnPageTitle
import io.github.affonsopaulo.stremionow.ui.components.SnSwitch
import io.github.affonsopaulo.stremionow.ui.components.snClickable
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.LocalNetwork
import io.github.affonsopaulo.stremionow.ui.data.io
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSize
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** State of the addons screen. */
data class AddonsState(
    val addons: List<AddonInfo> = emptyList(),
    val installUrl: String = "",
    val installing: Boolean = false,
    val installFailed: Boolean = false,
    /** The addon is on the local network and the user declined access to it. */
    val localNetworkDenied: Boolean = false,
) {
    val hasError: Boolean get() = installFailed || localNetworkDenied
}

class AddonsViewModel(
    private val core: StremioCore,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AddonsState())
    val state = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun setInstallUrl(url: String) =
        mutableState.update { it.copy(installUrl = url.take(2048), installFailed = false, localNetworkDenied = false) }

    fun localNetworkDenied() = mutableState.update { it.copy(localNetworkDenied = true) }

    fun install() {
        val url = state.value.installUrl.trim()
        if (url.isEmpty() || state.value.installing) return
        mutableState.update { it.copy(installing = true, installFailed = false, localNetworkDenied = false) }
        viewModelScope.launch {
            val result =
                runCatching { core.installAddon(url) }
                    .onFailure { android.util.Log.w("StremioNow", "Could not install an addon", it) }
            mutableState.update {
                it.copy(
                    installing = false,
                    installFailed = result.isFailure,
                    installUrl = if (result.isSuccess) "" else it.installUrl,
                )
            }
            refresh()
        }
    }

    fun setEnabled(
        addon: AddonInfo,
        enabled: Boolean,
    ) = mutate { core.setAddonEnabled(addon.manifestUrl, enabled) }

    fun move(
        addon: AddonInfo,
        index: Int,
    ) = mutate { core.moveAddon(addon.manifestUrl, index.coerceAtLeast(0).toUInt()) }

    fun remove(addon: AddonInfo) = mutate { core.removeAddon(addon.manifestUrl) }

    private fun mutate(change: () -> Unit) {
        viewModelScope.launch {
            runCatching { io(change) }
            refresh()
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            val addons = runCatching { io { core.addons() } }.getOrDefault(state.value.addons)
            mutableState.update { it.copy(addons = addons) }
        }
    }
}

@Composable
fun AddonsScreen() {
    val context = LocalContext.current
    val viewModel = viewModel { AddonsViewModel(AppGraph.core(context)) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = SnLayout.gutter, vertical = SnSpacing.space2),
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space3),
    ) {
        item(key = "intro") {
            Column(Modifier.widthIn(max = 1000.dp), verticalArrangement = Arrangement.spacedBy(SnSpacing.space4)) {
                SnPageTitle(stringResource(R.string.nav_addons))
                BasicText(
                    stringResource(R.string.addons_intro),
                    style = SnTypography.body.copy(color = SnColors.fgMuted),
                )
                InstallField(state, viewModel)
            }
        }
        itemsIndexed(state.addons, key = { _, addon -> addon.manifestUrl }) { index, addon ->
            AddonRow(
                addon = addon,
                canMoveUp = index > 0,
                canMoveDown = index < state.addons.lastIndex,
                onToggle = { viewModel.setEnabled(addon, !addon.enabled) },
                onMoveUp = { viewModel.move(addon, index - 1) },
                onMoveDown = { viewModel.move(addon, index + 1) },
                onRemove = { viewModel.remove(addon) },
            )
        }
    }
}

@Composable
private fun InstallField(
    state: AddonsState,
    viewModel: AddonsViewModel,
) {
    val context = LocalContext.current
    // An addon on the user's network needs local network access first (Android 17+); ask
    // for it at the moment it is needed, then install either way so the outcome is shown.
    val requestLocalNetwork =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) viewModel.install() else viewModel.localNetworkDenied()
        }
    val install = {
        val permission = LocalNetwork.permission
        if (permission != null && LocalNetwork.needsPermission(context, state.installUrl)) {
            requestLocalNetwork.launch(permission)
        } else {
            viewModel.install()
        }
    }
    val label = stringResource(R.string.addons_install_label)
    val shape = RoundedCornerShape(SnRadius.radiusMd)
    Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space2)) {
        BasicText(label, style = SnTypography.label.copy(color = SnColors.fgSecondary))
        Row(
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .height(SnSize.controlLg)
                    .background(SnColors.surfaceInput, shape)
                    .border(1.dp, if (state.hasError) SnColors.danger else SnColors.border, shape)
                    .padding(horizontal = SnSpacing.space4),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (state.installUrl.isEmpty()) {
                    BasicText(
                        stringResource(R.string.addons_install_hint),
                        style = SnTypography.body.copy(color = SnColors.fgMuted),
                    )
                }
                BasicTextField(
                    value = state.installUrl,
                    onValueChange = viewModel::setInstallUrl,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                    textStyle = SnTypography.body.copy(color = SnColors.fg),
                    singleLine = true,
                    cursorBrush = SolidColor(SnColors.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (state.installUrl.isNotBlank()) install() }),
                )
            }
            SnButton(
                text =
                    stringResource(
                        if (state.installing) R.string.addons_installing else R.string.addons_install_action,
                    ),
                onClick = install,
                enabled = !state.installing && state.installUrl.isNotBlank(),
            )
        }
        if (state.hasError) {
            BasicText(
                stringResource(
                    if (state.localNetworkDenied) {
                        R.string.addons_local_network_needed
                    } else {
                        R.string.addons_install_error
                    },
                ),
                style = SnTypography.caption.copy(color = SnColors.danger),
            )
        }
    }
}

@Composable
private fun AddonRow(
    addon: AddonInfo,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val shape = RoundedCornerShape(SnRadius.radiusLg)
    val kinds =
        listOfNotNull(
            stringResource(R.string.addons_kind_catalogs).takeIf { addon.providesCatalogs },
            stringResource(R.string.addons_kind_metadata).takeIf { addon.providesMetadata },
            stringResource(R.string.addons_kind_streams).takeIf { addon.providesStreams },
            stringResource(R.string.addons_kind_subtitles).takeIf { addon.providesSubtitles },
        )
    val meta = (listOf(stringResource(R.string.addons_version, addon.version)) + kinds).joinToString(" · ")
    // Removing is confirmed in a dialog, so a slip of the finger cannot drop an addon.
    var confirmingRemoval by rememberSaveable(addon.manifestUrl) { mutableStateOf(false) }

    // Stacked layout: name, details and switch first, then the less frequent actions in their
    // own row, so nothing is squeezed on a phone and the order still reads well on a TV.
    Column(
        Modifier
            .widthIn(max = 1000.dp)
            .fillMaxWidth()
            .background(SnColors.surface, shape)
            .border(1.dp, SnColors.border, shape)
            .padding(start = SnSpacing.space4, end = SnSpacing.space4, top = SnSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space2),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SnSpacing.space1)) {
                BasicText(
                    addon.name,
                    style =
                        SnTypography.sectionTitle.copy(
                            color = if (addon.enabled) SnColors.fg else SnColors.fgMuted,
                        ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                BasicText(
                    meta,
                    style = SnTypography.caption.copy(color = SnColors.fgSecondary),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SnSwitch(
                checked = addon.enabled,
                label = stringResource(R.string.addons_toggle, addon.name),
                onText = stringResource(R.string.state_on),
                offText = stringResource(R.string.state_off),
                onToggle = onToggle,
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
        if (confirmingRemoval) {
            SnDialog(
                title = stringResource(R.string.addons_remove_title, addon.name),
                body = stringResource(R.string.addons_remove_body),
                confirmText = stringResource(R.string.addons_remove_action),
                dismissText = stringResource(R.string.action_cancel),
                onConfirm = {
                    confirmingRemoval = false
                    onRemove()
                },
                onDismiss = { confirmingRemoval = false },
                destructive = true,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(SnColors.border))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = SnSpacing.space1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space1),
        ) {
            SnIconButton(
                SnIcons.ChevronUp,
                stringResource(R.string.addons_move_up, addon.name),
                onMoveUp,
                tint = SnColors.fgSecondary,
                enabled = canMoveUp,
            )
            SnIconButton(
                SnIcons.ChevronDown,
                stringResource(R.string.addons_move_down, addon.name),
                onMoveDown,
                tint = SnColors.fgSecondary,
                enabled = canMoveDown,
            )
            Box(Modifier.weight(1f))
            SnButton(
                text = stringResource(R.string.addons_remove_action),
                onClick = { confirmingRemoval = true },
                style = SnButtonStyle.Ghost,
                icon = SnIcons.Trash,
            )
        }
    }
}
