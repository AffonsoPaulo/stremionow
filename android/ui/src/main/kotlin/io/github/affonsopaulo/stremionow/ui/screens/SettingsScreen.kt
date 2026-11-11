// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.affonsopaulo.stremionow.core.AppSettings
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.ui.R
import io.github.affonsopaulo.stremionow.ui.components.SnChipRow
import io.github.affonsopaulo.stremionow.ui.components.SnPageTitle
import io.github.affonsopaulo.stremionow.ui.components.SnSwitch
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.io
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val core: StremioCore,
) : ViewModel() {
    private val mutableSettings = MutableStateFlow<AppSettings?>(null)
    val settings = mutableSettings.asStateFlow()
    val cacheChoices: List<UInt> = core.torrentCacheChoices()

    init {
        viewModelScope.launch { mutableSettings.value = runCatching { io { core.settings() } }.getOrNull() }
    }

    /** Applies a change at once; there is no Save button to forget. */
    fun update(change: (AppSettings) -> AppSettings) {
        val current = settings.value ?: return
        val updated = change(current)
        mutableSettings.value = updated
        viewModelScope.launch { runCatching { io { core.updateSettings(updated) } } }
    }
}

/** Playback and download preferences. */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val viewModel = viewModel { SettingsViewModel(AppGraph.core(context)) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val current = settings ?: return

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = SnLayout.gutter, vertical = SnSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space8),
    ) {
        item { SnPageTitle(stringResource(R.string.settings_title)) }
        item {
            Section(stringResource(R.string.settings_torrents)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SnSpacing.space4),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SnSpacing.space1)) {
                        BasicText(
                            stringResource(R.string.settings_wifi_only),
                            style = SnTypography.bodyLg.copy(color = SnColors.fg),
                        )
                        BasicText(
                            stringResource(R.string.settings_wifi_only_body),
                            style = SnTypography.body.copy(color = SnColors.fgMuted),
                        )
                    }
                    SnSwitch(
                        checked = current.torrentWifiOnly,
                        label = stringResource(R.string.settings_wifi_only),
                        onText = stringResource(R.string.state_on),
                        offText = stringResource(R.string.state_off),
                        onToggle = { viewModel.update { it.copy(torrentWifiOnly = !it.torrentWifiOnly) } },
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space2)) {
                    BasicText(
                        stringResource(R.string.settings_cache),
                        style = SnTypography.bodyLg.copy(color = SnColors.fg),
                    )
                    BasicText(
                        stringResource(R.string.settings_cache_body),
                        style = SnTypography.body.copy(color = SnColors.fgMuted),
                    )
                    SnChipRow(
                        options = viewModel.cacheChoices,
                        selected = current.torrentCacheMb,
                        label = { stringResource(R.string.settings_gigabytes, (it.toInt() / 1024)) },
                        onSelect = { size -> viewModel.update { it.copy(torrentCacheMb = size) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(SnSpacing.space4)) {
        BasicText(
            title.uppercase(),
            modifier = Modifier.semantics { heading() },
            style = SnTypography.overline.copy(color = SnColors.fgMuted),
        )
        content()
    }
}
