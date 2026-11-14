// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.affonsopaulo.stremionow.core.CoreException
import io.github.affonsopaulo.stremionow.core.FoundDevice
import io.github.affonsopaulo.stremionow.core.OtherDevice
import io.github.affonsopaulo.stremionow.core.OtherDeviceHealth
import io.github.affonsopaulo.stremionow.core.PairedDeviceInfo
import io.github.affonsopaulo.stremionow.core.PairingException
import io.github.affonsopaulo.stremionow.core.ServePause
import io.github.affonsopaulo.stremionow.core.ServeStatus
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.ui.components.SnButton
import io.github.affonsopaulo.stremionow.ui.components.SnButtonStyle
import io.github.affonsopaulo.stremionow.ui.components.SnDialog
import io.github.affonsopaulo.stremionow.ui.components.SnPageTitle
import io.github.affonsopaulo.stremionow.ui.components.SnScrollArea
import io.github.affonsopaulo.stremionow.ui.components.SnTextField
import io.github.affonsopaulo.stremionow.ui.components.collectSnInteraction
import io.github.affonsopaulo.stremionow.ui.components.snClickable
import io.github.affonsopaulo.stremionow.ui.components.snStateBackground
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.DeviceKind
import io.github.affonsopaulo.stremionow.ui.data.DevicePlatform
import io.github.affonsopaulo.stremionow.ui.data.DeviceSharing
import io.github.affonsopaulo.stremionow.ui.data.io
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.platform.LocalPlatformContext
import io.github.affonsopaulo.stremionow.ui.platform.PlatformContext
import io.github.affonsopaulo.stremionow.ui.platform.logWarning
import io.github.affonsopaulo.stremionow.ui.platform.rememberLocalNetworkAccess
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How often the server's state is read while the page is open: the code counts down. */
private const val SERVE_REFRESH_MS = 1_000L

/** Longest name for this device as a server, as the core keeps it. */
private const val MAX_NAME_CHARS = 40

/**
 * Stands in for an address when asking for local network access before discovery, which
 * reaches every device on the network rather than one address.
 */
private const val ANY_LOCAL_ADDRESS = "http://stremionow.local"

/** Why serving could not start. */
enum class ServeProblem { NoNetwork, LocalNetwork, Failed }

/** Why pairing failed, for the message under the form. */
enum class PairProblem {
    Address,
    Code,
    Unreachable,
    NoCode,
    WrongCode,
    TooMany,
    Full,
    NotGenuine,
    Failed,
    LocalNetwork,
}

/** Everything the page shows. */
data class DevicesState(
    val loaded: Boolean = false,
    /** The "Serve to other devices" switch, as saved in Settings. */
    val serveOn: Boolean = false,
    val serveBusy: Boolean = false,
    val serve: ServeStatus? = null,
    val serveProblem: ServeProblem? = null,
    /** The name other devices see, as saved; empty until serving is first turned on. */
    val name: String = "",
    val nameInput: String = "",
    val other: OtherDevice? = null,
    /** Null while being checked. */
    val health: OtherDeviceHealth? = null,
    val searching: Boolean = false,
    val searched: Boolean = false,
    val found: List<FoundDevice> = emptyList(),
    /** The found device the address came from, whose other addresses pairing also tries. */
    val chosen: FoundDevice? = null,
    val address: String = "",
    val code: String = "",
    val pairing: Boolean = false,
    val pairProblem: PairProblem? = null,
)

class DevicesViewModel(
    context: PlatformContext,
    private val core: StremioCore,
) : ViewModel() {
    private val app = DevicePlatform.applicationContext(context)
    private val mutableState = MutableStateFlow(DevicesState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = runCatching { io { core.settings() } }.getOrNull()
            mutableState.update {
                it.copy(
                    loaded = true,
                    serveOn = settings?.serveToDevices ?: false,
                    name = settings?.serveName.orEmpty(),
                    nameInput = settings?.serveName.orEmpty(),
                    serve = core.serveStatus(),
                    other = core.otherDevice(),
                )
            }
            checkOther()
        }
    }

    /** Reads the server's state again: the code's time left, new and streaming devices. */
    fun refreshServe() {
        mutableState.update { it.copy(serve = core.serveStatus()) }
    }

    /**
     * Turns serving on or off. Turning it on makes the current network the home network and
     * announces [defaultName] unless the user chose a name before.
     */
    fun setServing(
        on: Boolean,
        defaultName: String,
    ) {
        if (state.value.serveBusy) return
        val name = state.value.name.ifBlank { defaultName }
        mutableState.update { it.copy(serveOn = on, serveBusy = true, serveProblem = null) }
        viewModelScope.launch {
            val problem =
                if (on) {
                    runCatching { DeviceSharing.startServing(app, core, name, turnedOn = true) }
                        .exceptionOrNull()
                        ?.let { error ->
                            logWarning("Could not serve to other devices", error)
                            if (error is CoreException.NoLocalNetwork) ServeProblem.NoNetwork else ServeProblem.Failed
                        }
                } else {
                    runCatching { DeviceSharing.stopServing(app, core) }
                    null
                }
            val serving = on && problem == null
            runCatching {
                io { core.updateSettings(core.settings().copy(serveToDevices = serving, serveName = name)) }
            }
            mutableState.update {
                it.copy(
                    name = name,
                    nameInput = name,
                    serveOn = serving,
                    serveBusy = false,
                    serveProblem = problem,
                    serve = core.serveStatus(),
                    other = core.otherDevice(),
                )
            }
            if (!serving) checkOther()
        }
    }

    fun setNameInput(name: String) {
        mutableState.update { it.copy(nameInput = name.take(MAX_NAME_CHARS)) }
    }

    /** Saves the name other devices see and announces it, restarting the server if it runs. */
    fun saveName() {
        val name =
            state.value.nameInput
                .trim()
                .ifBlank { return }
        if (name == state.value.name || state.value.serveBusy) return
        mutableState.update { it.copy(name = name, nameInput = name) }
        viewModelScope.launch {
            runCatching { io { core.updateSettings(core.settings().copy(serveName = name)) } }
            if (state.value.serveOn) {
                // Same identity, pairings and home network; only the name changes.
                runCatching { DeviceSharing.startServing(app, core, name, turnedOn = false) }
                    .onFailure { logWarning("Could not rename the server", it) }
                refreshServe()
            }
        }
    }

    /** Serves on the network the device is on now, after the user confirmed it. */
    fun serveHere() {
        viewModelScope.launch {
            runCatching { core.serveOnThisNetwork() }
                .onSuccess { status -> mutableState.update { it.copy(serve = status) } }
                .onFailure { logWarning("Could not serve on this network", it) }
        }
    }

    fun localNetworkDeniedForServing() {
        mutableState.update { it.copy(serveProblem = ServeProblem.LocalNetwork) }
    }

    fun newCode() {
        runCatching { core.newPairingCode() }.onSuccess { status -> mutableState.update { it.copy(serve = status) } }
    }

    fun cancelCode() {
        core.cancelPairingCode()
        refreshServe()
    }

    fun remove(device: PairedDeviceInfo) {
        viewModelScope.launch {
            core.removePairedDevice(device.id)
            refreshServe()
        }
    }

    fun find() {
        if (state.value.searching) return
        mutableState.update { it.copy(searching = true, pairProblem = null) }
        viewModelScope.launch {
            val found = DevicePlatform.withMulticast(app) { core.discoverOtherDevices() }
            mutableState.update {
                it.copy(
                    searching = false,
                    searched = true,
                    found = found,
                    // One answer is almost always the device the user means.
                    chosen = if (it.address.isBlank() && found.size == 1) found.first() else it.chosen,
                    address =
                        if (it.address.isBlank() && found.size == 1) {
                            found
                                .first()
                                .addresses
                                .firstOrNull()
                                .orEmpty()
                        } else {
                            it.address
                        },
                )
            }
        }
    }

    fun choose(device: FoundDevice) {
        mutableState.update {
            it.copy(chosen = device, address = device.addresses.firstOrNull().orEmpty(), pairProblem = null)
        }
    }

    fun setAddress(address: String) {
        mutableState.update { it.copy(address = address, pairProblem = null) }
    }

    fun setCode(code: String) {
        // Digits only, and no more than a code has; spaces typed from the screen are dropped.
        val digits = code.filter(Char::isDigit).take(6)
        mutableState.update { it.copy(code = digits, pairProblem = null) }
    }

    fun localNetworkDeniedForPairing() {
        mutableState.update { it.copy(searching = false, pairing = false, pairProblem = PairProblem.LocalNetwork) }
    }

    fun pair() {
        val current = state.value
        if (current.pairing) return
        when {
            current.address.isBlank() -> {
                mutableState.update { it.copy(pairProblem = PairProblem.Address) }
                return
            }

            current.code.length != 6 -> {
                mutableState.update { it.copy(pairProblem = PairProblem.Code) }
                return
            }
        }
        mutableState.update { it.copy(pairing = true, pairProblem = null) }
        viewModelScope.launch {
            val typed = current.address.trim()
            // A found device's other addresses are tried too, in case the first does not answer.
            val addresses =
                current.chosen
                    ?.addresses
                    ?.takeIf { it.firstOrNull() == typed }
                    ?: listOf(typed)
            val result = runCatching { DeviceSharing.pair(app, core, addresses, current.code) }
            mutableState.update {
                it.copy(
                    pairing = false,
                    pairProblem = result.exceptionOrNull()?.let(::pairProblem),
                    other = result.getOrNull() ?: it.other,
                    health = if (result.isSuccess) OtherDeviceHealth.READY else it.health,
                    code = if (result.isSuccess) "" else it.code,
                )
            }
        }
    }

    fun stopUsing() {
        viewModelScope.launch {
            DeviceSharing.forget(app, core)
            mutableState.update { it.copy(other = null, health = null, address = "", code = "") }
        }
    }

    private suspend fun checkOther() {
        if (core.otherDevice() == null) return
        mutableState.update { it.copy(health = null) }
        val health = core.checkOtherDevice()
        mutableState.update { it.copy(health = health, other = core.otherDevice()) }
    }

    private fun pairProblem(error: Throwable): PairProblem =
        when (error) {
            is PairingException.InvalidAddress -> PairProblem.Address
            is PairingException.InvalidCode -> PairProblem.Code
            is PairingException.Unreachable -> PairProblem.Unreachable
            is PairingException.NoCode -> PairProblem.NoCode
            is PairingException.WrongCode -> PairProblem.WrongCode
            is PairingException.TooManyAttempts -> PairProblem.TooMany
            is PairingException.TooManyDevices -> PairProblem.Full
            is PairingException.NotGenuine -> PairProblem.NotGenuine
            else -> PairProblem.Failed
        }
}

/**
 * Settings › Other devices: serving torrents to the other devices on the home network, or
 * using one of them for torrents (ADR 0008).
 */
@Composable
fun DevicesScreen() {
    val context = LocalPlatformContext.current
    val viewModel = viewModel { DevicesViewModel(context, AppGraph.core(context)) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.loaded) return

    LaunchedEffect(state.serve != null) {
        while (state.serve != null) {
            delay(SERVE_REFRESH_MS)
            viewModel.refreshServe()
        }
    }

    val listState = rememberLazyListState()
    SnScrollArea(listState) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(horizontal = SnLayout.gutter, vertical = SnSpacing.space4),
            verticalArrangement = Arrangement.spacedBy(SnSpacing.space8),
        ) {
            item {
                Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(SnSpacing.space4)) {
                    SnPageTitle(stringResource(Res.string.devices_title))
                    BasicText(
                        stringResource(Res.string.devices_intro),
                        style = SnTypography.body.copy(color = SnColors.fgMuted),
                    )
                }
            }
            item { ServeSection(state, viewModel) }
            item { UseSection(state, viewModel) }
        }
    }
}

@Composable
private fun ServeSection(
    state: DevicesState,
    viewModel: DevicesViewModel,
) {
    val defaultName = defaultServeName()
    // Serving receives connections from the local network, which Android 17 gates.
    val requestLocalNetwork =
        rememberLocalNetworkAccess { granted ->
            if (granted) viewModel.setServing(true, defaultName) else viewModel.localNetworkDeniedForServing()
        }
    SettingsSection(stringResource(Res.string.devices_section_serve)) {
        SettingsToggle(
            title = stringResource(Res.string.devices_serve),
            body = stringResource(Res.string.devices_serve_body),
            checked = state.serveOn,
            enabled = !state.serveBusy && state.other == null,
            onToggle = {
                if (state.serveOn) viewModel.setServing(false, defaultName) else requestLocalNetwork(ANY_LOCAL_ADDRESS)
            },
        )
        if (state.other != null && !state.serveOn) {
            Caption(stringResource(Res.string.devices_serve_using_other))
        }
        when {
            state.serveBusy -> {
                Caption(stringResource(Res.string.devices_serve_starting))
            }

            state.serveProblem != null -> {
                Caption(
                    stringResource(
                        when (state.serveProblem) {
                            ServeProblem.NoNetwork -> Res.string.devices_serve_no_network
                            ServeProblem.LocalNetwork -> Res.string.devices_error_local_network
                            ServeProblem.Failed -> Res.string.devices_serve_failed
                        },
                    ),
                    error = true,
                )
            }
        }
        val serve = state.serve ?: return@SettingsSection
        SnTextField(
            value = state.nameInput,
            onValueChange = viewModel::setNameInput,
            label = stringResource(Res.string.devices_name_label),
            hint = defaultName,
            onImeAction = viewModel::saveName,
        )
        if (state.nameInput.trim().let { it.isNotEmpty() && it != state.name }) {
            SnButton(
                stringResource(Res.string.devices_name_save),
                onClick = viewModel::saveName,
                style = SnButtonStyle.Tonal,
            )
        }
        when (serve.paused) {
            ServePause.OTHER_NETWORK -> {
                OtherNetworkNotice(onServeHere = viewModel::serveHere)
                return@SettingsSection
            }

            ServePause.NO_NETWORK -> {
                Caption(stringResource(Res.string.devices_paused_no_network), error = true)
                return@SettingsSection
            }

            null -> {
                Detail(stringResource(Res.string.devices_serve_address), serve.addresses.joinToString(", "))
            }
        }
        val code = serve.pairingCode
        if (code != null) {
            PairingCode(
                code,
                serve.pairingSecondsLeft.toInt(),
                onNewCode = viewModel::newCode,
                onCancel = viewModel::cancelCode,
            )
        }
        if (serve.pairingContested) {
            Caption(stringResource(Res.string.devices_pair_contested), error = true)
        }
        if (code == null) {
            SnButton(
                text = stringResource(Res.string.devices_pair_action),
                onClick = viewModel::newCode,
                style = SnButtonStyle.Tonal,
                icon = SnIcons.Plus,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space2)) {
            BasicText(
                stringResource(Res.string.devices_paired_title),
                style = SnTypography.bodyLg.copy(color = SnColors.fg),
            )
            if (serve.devices.isEmpty()) {
                Caption(stringResource(Res.string.devices_paired_empty))
            }
            serve.devices.forEach { device -> PairedDeviceRow(device, onRemove = { viewModel.remove(device) }) }
        }
    }
}

/** The neutral name a server announces until the user chooses one: "StremioNow (TV)". */
@Composable
private fun defaultServeName(): String {
    val context = LocalPlatformContext.current
    val kind =
        when (DevicePlatform.kind(context)) {
            DeviceKind.Tv -> Res.string.devices_kind_tv
            DeviceKind.Phone -> Res.string.devices_kind_phone
            DeviceKind.Tablet -> Res.string.devices_kind_tablet
            DeviceKind.Computer -> Res.string.devices_kind_computer
        }
    return stringResource(Res.string.devices_default_name, stringResource(kind))
}

/** Serving paused on a network other than the home one, with the way to serve here. */
@Composable
private fun OtherNetworkNotice(onServeHere: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    Caption(stringResource(Res.string.devices_paused_other_network), error = true)
    SnButton(
        text = stringResource(Res.string.devices_serve_here),
        onClick = { confirming = true },
        style = SnButtonStyle.Tonal,
    )
    if (confirming) {
        SnDialog(
            title = stringResource(Res.string.devices_serve_here_title),
            body = stringResource(Res.string.devices_serve_here_body),
            confirmText = stringResource(Res.string.devices_serve_here),
            dismissText = stringResource(Res.string.action_cancel),
            onConfirm = {
                confirming = false
                onServeHere()
            },
            onDismiss = { confirming = false },
        )
    }
}

@Composable
private fun PairingCode(
    code: String,
    secondsLeft: Int,
    onNewCode: () -> Unit,
    onCancel: () -> Unit,
) {
    val shape = RoundedCornerShape(SnRadius.radiusLg)
    val shown = if (code.length == 6) "${code.take(3)} ${code.drop(3)}" else code
    val spoken = code.toList().joinToString(" ")
    Column(
        Modifier
            .fillMaxWidth()
            .background(SnColors.accentSurface, shape)
            .border(1.dp, SnColors.accentBorder, shape)
            .padding(SnSpacing.space6),
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space3),
    ) {
        // Read digit by digit, as it is typed on the other device.
        val label = stringResource(Res.string.devices_pair_code_label, spoken)
        BasicText(
            shown,
            modifier = Modifier.clearAndSetSemantics { contentDescription = label },
            style = SnTypography.headingLg.copy(color = SnColors.fg),
        )
        BasicText(
            stringResource(Res.string.devices_pair_code_body),
            style = SnTypography.body.copy(color = SnColors.fgSecondary),
        )
        val minutes = secondsLeft / 60
        val seconds = (secondsLeft % 60).toString().padStart(2, '0')
        BasicText(
            stringResource(Res.string.devices_pair_code_expires, "$minutes:$seconds"),
            style = SnTypography.caption.copy(color = SnColors.fgMuted),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(SnSpacing.space2)) {
            SnButton(stringResource(Res.string.devices_new_code), onClick = onNewCode, style = SnButtonStyle.Tonal)
            SnButton(stringResource(Res.string.action_cancel), onClick = onCancel, style = SnButtonStyle.Ghost)
        }
    }
}

@Composable
private fun PairedDeviceRow(
    device: PairedDeviceInfo,
    onRemove: () -> Unit,
) {
    var confirming by rememberSaveable(device.id) { mutableStateOf(false) }
    val removeLabel = stringResource(Res.string.devices_remove_label, device.name)
    val shape = RoundedCornerShape(SnRadius.radiusLg)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(SnColors.surface, shape)
            .padding(
                start = SnSpacing.space4,
                end = SnSpacing.space2,
                top = SnSpacing.space2,
                bottom = SnSpacing.space2,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
    ) {
        Image(SnIcons.Tv, null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(SnColors.fgSecondary))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SnSpacing.space1)) {
            BasicText(
                device.name,
                style = SnTypography.body.copy(color = SnColors.fg),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (device.streaming) {
                BasicText(
                    stringResource(Res.string.devices_streaming),
                    style = SnTypography.caption.copy(color = SnColors.accentText),
                )
            }
        }
        SnButton(
            text = stringResource(Res.string.devices_remove),
            onClick = { confirming = true },
            style = SnButtonStyle.Ghost,
            icon = SnIcons.Trash,
            modifier = Modifier.semantics { contentDescription = removeLabel },
        )
    }
    if (confirming) {
        SnDialog(
            title = stringResource(Res.string.devices_remove_title, device.name),
            body = stringResource(Res.string.devices_remove_body),
            confirmText = stringResource(Res.string.devices_remove),
            dismissText = stringResource(Res.string.action_cancel),
            onConfirm = {
                confirming = false
                onRemove()
            },
            onDismiss = { confirming = false },
            destructive = true,
        )
    }
}

@Composable
private fun UseSection(
    state: DevicesState,
    viewModel: DevicesViewModel,
) {
    SettingsSection(stringResource(Res.string.devices_use)) {
        BasicText(
            stringResource(Res.string.devices_use_body),
            style = SnTypography.body.copy(color = SnColors.fgMuted),
        )
        val other = state.other
        when {
            state.serveOn -> Caption(stringResource(Res.string.devices_use_serving))
            other != null -> OtherDeviceCard(other, state.health, onStop = viewModel::stopUsing)
            else -> PairForm(state, viewModel)
        }
    }
}

@Composable
private fun OtherDeviceCard(
    device: OtherDevice,
    health: OtherDeviceHealth?,
    onStop: () -> Unit,
) {
    var confirming by rememberSaveable(device.serverId) { mutableStateOf(false) }
    val shape = RoundedCornerShape(SnRadius.radiusLg)
    Column(
        Modifier
            .fillMaxWidth()
            .background(SnColors.surface, shape)
            .border(1.dp, SnColors.border, shape)
            .padding(SnSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space2),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
        ) {
            Image(SnIcons.Tv, null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(SnColors.fgSecondary))
            Column(Modifier.weight(1f)) {
                BasicText(
                    device.name,
                    style = SnTypography.bodyLg.copy(color = SnColors.fg),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                BasicText(device.address, style = SnTypography.caption.copy(color = SnColors.fgMuted))
            }
        }
        val (status, color) =
            when (health) {
                null -> {
                    stringResource(Res.string.devices_status_checking) to SnColors.fgMuted
                }

                OtherDeviceHealth.READY -> {
                    stringResource(Res.string.devices_status_ready) to SnColors.success
                }

                OtherDeviceHealth.UNREACHABLE -> {
                    stringResource(Res.string.devices_status_unreachable) to
                        SnColors.warning
                }

                OtherDeviceHealth.UNPAIRED -> {
                    stringResource(Res.string.devices_status_unpaired) to SnColors.danger
                }
            }
        BasicText(
            status,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = SnTypography.caption.copy(color = color),
        )
        SnButton(
            text = stringResource(Res.string.devices_stop_using),
            onClick = { confirming = true },
            style = SnButtonStyle.Ghost,
        )
    }
    if (confirming) {
        SnDialog(
            title = stringResource(Res.string.devices_stop_title, device.name),
            body = stringResource(Res.string.devices_stop_body),
            confirmText = stringResource(Res.string.devices_stop_using),
            dismissText = stringResource(Res.string.action_cancel),
            onConfirm = {
                confirming = false
                onStop()
            },
            onDismiss = { confirming = false },
            destructive = true,
        )
    }
}

@Composable
private fun PairForm(
    state: DevicesState,
    viewModel: DevicesViewModel,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    // Finding and pairing reach the local network, which Android 17 gates.
    val findWithAccess =
        rememberLocalNetworkAccess { granted ->
            if (granted) viewModel.find() else viewModel.localNetworkDeniedForPairing()
        }
    val pairWithAccess =
        rememberLocalNetworkAccess { granted ->
            if (granted) viewModel.pair() else viewModel.localNetworkDeniedForPairing()
        }
    val pair = {
        keyboard?.hide()
        pairWithAccess("http://${state.address.trim()}")
    }
    Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space4)) {
        SnButton(
            text = stringResource(if (state.searching) Res.string.devices_searching else Res.string.devices_find),
            onClick = { findWithAccess(ANY_LOCAL_ADDRESS) },
            style = SnButtonStyle.Tonal,
            icon = SnIcons.Search,
            enabled = !state.searching,
        )
        if (state.searched && !state.searching && state.found.isEmpty()) {
            Caption(stringResource(Res.string.devices_found_none))
        }
        state.found.forEach { device ->
            FoundDeviceRow(
                device,
                selected = device.addresses.firstOrNull() == state.address.trim(),
                onClick = { viewModel.choose(device) },
            )
        }
        SnTextField(
            value = state.address,
            onValueChange = viewModel::setAddress,
            label = stringResource(Res.string.devices_address_label),
            hint = stringResource(Res.string.devices_address_hint),
            error =
                stringResource(
                    Res.string.devices_error_address,
                ).takeIf { state.pairProblem == PairProblem.Address },
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
        )
        SnTextField(
            value = state.code,
            onValueChange = viewModel::setCode,
            label = stringResource(Res.string.devices_code_label),
            hint = stringResource(Res.string.devices_code_hint),
            error = stringResource(Res.string.devices_error_code).takeIf { state.pairProblem == PairProblem.Code },
            keyboardType = KeyboardType.NumberPassword,
            onImeAction = pair,
        )
        SnButton(
            text = stringResource(if (state.pairing) Res.string.devices_pairing else Res.string.devices_pair),
            onClick = pair,
            enabled = !state.pairing,
        )
        val problem =
            when (state.pairProblem) {
                PairProblem.Unreachable -> Res.string.devices_error_unreachable
                PairProblem.NoCode -> Res.string.devices_error_no_code
                PairProblem.WrongCode -> Res.string.devices_error_wrong_code
                PairProblem.TooMany -> Res.string.devices_error_too_many
                PairProblem.Full -> Res.string.devices_error_full
                PairProblem.NotGenuine -> Res.string.devices_error_not_genuine
                PairProblem.Failed -> Res.string.devices_error_failed
                PairProblem.LocalNetwork -> Res.string.devices_error_local_network
                PairProblem.Address, PairProblem.Code, null -> null
            }
        if (problem != null) {
            Caption(stringResource(problem), error = true)
        }
    }
}

@Composable
private fun FoundDeviceRow(
    device: FoundDevice,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(SnRadius.radiusLg)
    val interaction = remember { MutableInteractionSource() }
    val state = interaction.collectSnInteraction()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .snClickable(shape, highlight = false, interactionSource = interaction, onClick = onClick)
            .snStateBackground(if (selected) SnColors.accentSurface else SnColors.surface, shape, state)
            .border(1.dp, if (selected) SnColors.accentBorder else SnColors.border, shape)
            .padding(horizontal = SnSpacing.space4, vertical = SnSpacing.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space4),
    ) {
        Image(SnIcons.Tv, null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(SnColors.fgSecondary))
        Column(Modifier.weight(1f)) {
            BasicText(
                device.name,
                style = SnTypography.body.copy(color = SnColors.fg),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText(
                device.addresses.joinToString(", "),
                style = SnTypography.caption.copy(color = SnColors.fgMuted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Image(SnIcons.Check, null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(SnColors.accent))
        }
    }
}

@Composable
private fun Detail(
    label: String,
    value: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(SnSpacing.space2)) {
        BasicText(label, style = SnTypography.body.copy(color = SnColors.fgMuted))
        BasicText(value, style = SnTypography.body.copy(color = SnColors.fg))
    }
}

@Composable
private fun Caption(
    text: String,
    error: Boolean = false,
) {
    BasicText(
        text,
        modifier = Modifier.semantics { if (error) liveRegion = LiveRegionMode.Polite },
        style = SnTypography.caption.copy(color = if (error) SnColors.danger else SnColors.fgMuted),
    )
}
