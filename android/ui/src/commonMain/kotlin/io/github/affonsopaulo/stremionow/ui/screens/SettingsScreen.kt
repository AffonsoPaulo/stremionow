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
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.ui.components.SnChipRow
import io.github.affonsopaulo.stremionow.ui.components.SnPageTitle
import io.github.affonsopaulo.stremionow.ui.components.SnSwitch
import io.github.affonsopaulo.stremionow.ui.crash.CrashReports
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.io
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.platform.LocalPlatformContext
import io.github.affonsopaulo.stremionow.ui.platform.deviceLanguage
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

/** Playback, download and privacy preferences. */
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
                SwitchRow(
                    title = stringResource(Res.string.settings_aniskip),
                    body = stringResource(Res.string.settings_aniskip_body),
                    checked = current.aniskip,
                    onToggle = { viewModel.update { it.copy(aniskip = !it.aniskip) } },
                )
            }
        }
        item {
            Section(stringResource(Res.string.settings_torrents)) {
                SwitchRow(
                    title = stringResource(Res.string.settings_wifi_only),
                    body = stringResource(Res.string.settings_wifi_only_body),
                    checked = current.torrentWifiOnly,
                    onToggle = { viewModel.update { it.copy(torrentWifiOnly = !it.torrentWifiOnly) } },
                )
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
        item { PrivacySection() }
    }
}

/**
 * Crash reports. The choice lives with the reports rather than in the core's database, so a
 * crash while the core opens still knows it.
 */
@Composable
private fun PrivacySection() {
    val mode by CrashReports.mode.collectAsStateWithLifecycle()
    val ask = stringResource(Res.string.settings_crash_ask)
    val never = stringResource(Res.string.settings_crash_never)
    Section(stringResource(Res.string.settings_privacy)) {
        Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space2)) {
            BasicText(
                stringResource(Res.string.settings_crash_reports),
                style = SnTypography.bodyLg.copy(color = SnColors.fg),
            )
            BasicText(
                stringResource(Res.string.settings_crash_reports_body),
                style = SnTypography.body.copy(color = SnColors.fgMuted),
            )
            SnChipRow(
                options = CrashReports.Mode.entries,
                selected = mode,
                label = { if (it == CrashReports.Mode.Ask) ask else never },
                onSelect = CrashReports::setMode,
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

/** A preference that is on or off: its name and what it does, with the switch beside them. */
@Composable
private fun SwitchRow(
    title: String,
    body: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space4),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SnSpacing.space1)) {
            BasicText(title, style = SnTypography.bodyLg.copy(color = SnColors.fg))
            BasicText(body, style = SnTypography.body.copy(color = SnColors.fgMuted))
        }
        SnSwitch(
            checked = checked,
            label = title,
            onText = stringResource(Res.string.state_on),
            offText = stringResource(Res.string.state_off),
            onToggle = onToggle,
        )
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
