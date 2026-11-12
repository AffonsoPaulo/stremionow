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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.affonsopaulo.stremionow.core.AppSettings
import io.github.affonsopaulo.stremionow.core.DisplaySwitch
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.ui.components.SnButton
import io.github.affonsopaulo.stremionow.ui.components.SnButtonStyle
import io.github.affonsopaulo.stremionow.ui.components.SnChipRow
import io.github.affonsopaulo.stremionow.ui.components.SnPageTitle
import io.github.affonsopaulo.stremionow.ui.components.SnSwitch
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.io
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.pluralStringResource
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.platform.LocalPlatformContext
import io.github.affonsopaulo.stremionow.ui.platform.deviceLanguage
import io.github.affonsopaulo.stremionow.ui.platform.displaySwitchingSupported
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
    private val mutableHdrTitles = MutableStateFlow(0)

    /** Titles the user turned HDR on for ("Only for this title"). */
    val hdrTitles = mutableHdrTitles.asStateFlow()

    init {
        viewModelScope.launch {
            mutableSettings.value = runCatching { io { core.settings() } }.getOrNull()
            mutableHdrTitles.value = runCatching { io { core.hdrTitleCount().toInt() } }.getOrDefault(0)
        }
    }

    /** Forgets the titles HDR was turned on for. */
    fun forgetHdrTitles() {
        mutableHdrTitles.value = 0
        viewModelScope.launch { runCatching { io { core.clearHdrTitles() } } }
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
    val context = LocalPlatformContext.current
    val viewModel = viewModel { SettingsViewModel(AppGraph.core(context)) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val current = settings ?: return

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = SnLayout.gutter, vertical = SnSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space8),
    ) {
        item { SnPageTitle(stringResource(Res.string.settings_title)) }
        item {
            val off = stringResource(Res.string.player_subtitles_off)
            val best = stringResource(Res.string.onboarding_quality_best)
            val languages = playbackLanguages(deviceLanguage())
            Section(stringResource(Res.string.settings_playback)) {
                Group(stringResource(Res.string.onboarding_audio)) {
                    SnChipRow(
                        options = (listOf(current.audioLanguage) + languages).filter { it.isNotEmpty() }.distinct(),
                        selected = current.audioLanguage.ifEmpty { languages.first() },
                        label = { languageName(it) },
                        onSelect = { language -> viewModel.update { it.copy(audioLanguage = language) } },
                    )
                }
                Group(stringResource(Res.string.player_subtitles)) {
                    SnChipRow(
                        options =
                            listOf("") +
                                (listOf(current.subtitleLanguage) + languages).filter { it.isNotEmpty() }.distinct(),
                        selected = current.subtitleLanguage,
                        label = { if (it.isEmpty()) off else languageName(it) },
                        onSelect = { language -> viewModel.update { it.copy(subtitleLanguage = language) } },
                    )
                }
                Group(stringResource(Res.string.onboarding_quality)) {
                    SnChipRow(
                        options = listOf(0u, 2160u, 1080u, 720u),
                        selected = current.maxResolution,
                        label = {
                            if (it == 0u) {
                                best
                            } else if (it == 2160u) {
                                "4K"
                            } else {
                                "${it}p"
                            }
                        },
                        onSelect = { lines -> viewModel.update { it.copy(maxResolution = lines) } },
                    )
                }
            }
        }
        if (displaySwitchingSupported()) {
            item {
                val hdrTitles by viewModel.hdrTitles.collectAsStateWithLifecycle()
                DisplaySection(
                    settings = current,
                    hdrTitles = hdrTitles,
                    onChange = viewModel::update,
                    onForgetHdrTitles = viewModel::forgetHdrTitles,
                )
            }
        }
        item {
            Section(stringResource(Res.string.settings_torrents)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SnSpacing.space4),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SnSpacing.space1)) {
                        BasicText(
                            stringResource(Res.string.settings_wifi_only),
                            style = SnTypography.bodyLg.copy(color = SnColors.fg),
                        )
                        BasicText(
                            stringResource(Res.string.settings_wifi_only_body),
                            style = SnTypography.body.copy(color = SnColors.fgMuted),
                        )
                    }
                    SnSwitch(
                        checked = current.torrentWifiOnly,
                        label = stringResource(Res.string.settings_wifi_only),
                        onText = stringResource(Res.string.state_on),
                        offText = stringResource(Res.string.state_off),
                        onToggle = { viewModel.update { it.copy(torrentWifiOnly = !it.torrentWifiOnly) } },
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space2)) {
                    BasicText(
                        stringResource(Res.string.settings_cache),
                        style = SnTypography.bodyLg.copy(color = SnColors.fg),
                    )
                    BasicText(
                        stringResource(Res.string.settings_cache_body),
                        style = SnTypography.body.copy(color = SnColors.fgMuted),
                    )
                    SnChipRow(
                        options = viewModel.cacheChoices,
                        selected = current.torrentCacheMb,
                        label = { stringResource(Res.string.settings_gigabytes, (it.toInt() / 1024)) },
                        onSelect = { size -> viewModel.update { it.copy(torrentCacheMb = size) } },
                    )
                }
            }
        }
    }
}

/** HDR and refresh rate switching, where the app can change the display (Windows). */
@Composable
private fun DisplaySection(
    settings: AppSettings,
    hdrTitles: Int,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onForgetHdrTitles: () -> Unit,
) {
    Section(stringResource(Res.string.settings_display)) {
        Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space2)) {
            BasicText(stringResource(Res.string.settings_hdr), style = SnTypography.bodyLg.copy(color = SnColors.fg))
            BasicText(
                stringResource(Res.string.settings_hdr_body),
                style = SnTypography.body.copy(color = SnColors.fgMuted),
            )
            SnChipRow(
                options = listOf(DisplaySwitch.ASK, DisplaySwitch.ALWAYS, DisplaySwitch.NEVER),
                selected = settings.hdr,
                label = {
                    stringResource(
                        when (it) {
                            DisplaySwitch.ASK -> Res.string.settings_switch_ask
                            DisplaySwitch.ALWAYS -> Res.string.settings_switch_always
                            DisplaySwitch.NEVER -> Res.string.settings_switch_never
                        },
                    )
                },
                onSelect = { choice -> onChange { it.copy(hdr = choice) } },
            )
            if (hdrTitles > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
                ) {
                    BasicText(
                        pluralStringResource(Res.plurals.settings_hdr_titles, hdrTitles, hdrTitles),
                        modifier = Modifier.weight(1f),
                        style = SnTypography.body.copy(color = SnColors.fgSecondary),
                    )
                    SnButton(
                        stringResource(Res.string.settings_hdr_forget),
                        onForgetHdrTitles,
                        style = SnButtonStyle.Secondary,
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space4),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SnSpacing.space1)) {
                BasicText(
                    stringResource(Res.string.settings_refresh),
                    style = SnTypography.bodyLg.copy(color = SnColors.fg),
                )
                BasicText(
                    stringResource(Res.string.settings_refresh_body),
                    style = SnTypography.body.copy(color = SnColors.fgMuted),
                )
            }
            SnSwitch(
                checked = settings.matchRefreshRate,
                label = stringResource(Res.string.settings_refresh),
                onText = stringResource(Res.string.state_on),
                offText = stringResource(Res.string.state_off),
                onToggle = { onChange { it.copy(matchRefreshRate = !it.matchRefreshRate) } },
            )
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

@Composable
private fun Group(
    label: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space2)) {
        BasicText(label, style = SnTypography.bodyLg.copy(color = SnColors.fg))
        content()
    }
}
