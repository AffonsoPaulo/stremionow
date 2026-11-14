// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.affonsopaulo.stremionow.core.AppSettings
import io.github.affonsopaulo.stremionow.core.CoreException
import io.github.affonsopaulo.stremionow.core.DisplaySwitch
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.ui.components.SnButton
import io.github.affonsopaulo.stremionow.ui.components.SnButtonStyle
import io.github.affonsopaulo.stremionow.ui.components.SnChipRow
import io.github.affonsopaulo.stremionow.ui.components.SnDialog
import io.github.affonsopaulo.stremionow.ui.components.SnListRow
import io.github.affonsopaulo.stremionow.ui.components.SnPageTitle
import io.github.affonsopaulo.stremionow.ui.components.SnScrollArea
import io.github.affonsopaulo.stremionow.ui.components.SnSwitch
import io.github.affonsopaulo.stremionow.ui.components.initialFocus
import io.github.affonsopaulo.stremionow.ui.components.snClickable
import io.github.affonsopaulo.stremionow.ui.crash.CrashReports
import io.github.affonsopaulo.stremionow.ui.crash.IssueLink
import io.github.affonsopaulo.stremionow.ui.crash.TestCrashMenu
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.io
import io.github.affonsopaulo.stremionow.ui.l10n.AppLanguage
import io.github.affonsopaulo.stremionow.ui.l10n.InterfaceLanguages
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.endonym
import io.github.affonsopaulo.stremionow.ui.l10n.pluralStringResource
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.platform.LocalPlatformContext
import io.github.affonsopaulo.stremionow.ui.platform.SnBackHandler
import io.github.affonsopaulo.stremionow.ui.platform.automaticStreamMemory
import io.github.affonsopaulo.stremionow.ui.platform.deviceLanguage
import io.github.affonsopaulo.stremionow.ui.platform.displaySwitchingSupported
import io.github.affonsopaulo.stremionow.ui.platform.openExternalLink
import io.github.affonsopaulo.stremionow.ui.theme.FormFactor
import io.github.affonsopaulo.stremionow.ui.theme.LocalIsTv
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The project's source code, linked from About. */
private const val SOURCE_URL = "https://github.com/AffonsoPaulo/stremionow"

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

    /**
     * Applies a change at once; there is no Save button to forget. The change is applied to
     * the saved settings again, so what other pages changed meanwhile (Other devices) stays.
     */
    fun update(change: (AppSettings) -> AppSettings) {
        val current = settings.value ?: return
        mutableSettings.value = change(current)
        viewModelScope.launch { runCatching { io { core.updateSettings(change(core.settings())) } } }
    }
}

/**
 * Settings: a side menu of sections and the chosen section beside it, on large screens and
 * TV; on phones, the list of sections ([page] null) or one section full screen. [page] is the
 * section a route asked for; Back leaves Settings rather than stepping through sections.
 * [onOpenPage] opens a section from the phone's list. [onSignIn] opens the sign-in dialog.
 */
@Composable
fun SettingsScreen(
    page: SettingsPage?,
    onOpenPage: (SettingsPage) -> Unit,
    onSignIn: () -> Unit,
) {
    val pages = SettingsPage.shown(displaySwitchingSupported())
    val asked = page?.takeIf { it in pages }
    if (SnLayout.formFactor == FormFactor.Phone) {
        if (asked == null) SettingsList(pages, onOpenPage) else PageContent(asked, onSignIn)
    } else {
        SideBySide(pages, initial = asked ?: SettingsPage.General, onSignIn = onSignIn)
    }
}

/** The phone's list of sections; each opens full screen. */
@Composable
private fun SettingsList(
    pages: List<SettingsPage>,
    onOpenPage: (SettingsPage) -> Unit,
) {
    val listState = rememberLazyListState()
    SnScrollArea(listState) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(horizontal = SnLayout.gutter, vertical = SnSpacing.space4),
            verticalArrangement = Arrangement.spacedBy(SnSpacing.space2),
        ) {
            items(pages, key = { it.slug }) { page ->
                SnListRow(icon = page.icon(), title = stringResource(page.title()), onClick = { onOpenPage(page) })
            }
        }
    }
}

/**
 * The side menu and the section. On TV, left and right move between them, Back from the
 * section returns to its menu item, and Back from the menu leaves Settings.
 */
@Composable
private fun SideBySide(
    pages: List<SettingsPage>,
    initial: SettingsPage,
    onSignIn: () -> Unit,
) {
    var selected by rememberSaveable(initial) { mutableStateOf(initial) }
    val isTv = LocalIsTv.current
    val menuFocus = remember(pages) { pages.associateWith { FocusRequester() } }
    var inSection by remember { mutableStateOf(false) }
    SnBackHandler(enabled = isTv && inSection) { runCatching { menuFocus[selected]?.requestFocus() } }
    LaunchedEffect(Unit) {
        if (!isTv) return@LaunchedEffect
        // The menu is composed a frame or so after the page.
        repeat(10) {
            withFrameNanos { }
            if (runCatching { menuFocus[selected]?.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .width(if (isTv) 300.dp else 260.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(start = SnLayout.gutter, top = SnSpacing.space4, bottom = SnSpacing.space8),
            verticalArrangement = Arrangement.spacedBy(SnSpacing.space1),
        ) {
            SnPageTitle(stringResource(Res.string.settings_title), Modifier.padding(bottom = SnSpacing.space4))
            for (page in pages) {
                MenuRow(
                    page = page,
                    selected = page == selected,
                    modifier = menuFocus[page]?.let { Modifier.focusRequester(it) } ?: Modifier,
                    onClick = { selected = page },
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight().onFocusChanged { inSection = it.hasFocus }) {
            key(selected) { PageContent(selected, onSignIn) }
        }
    }
}

@Composable
private fun MenuRow(
    page: SettingsPage,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(SnRadius.radiusMd)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics { this.selected = selected }
            .snClickable(shape, role = Role.Tab, onClick = onClick)
            .background(if (selected) SnColors.surfaceSelected else Color.Transparent, shape)
            .padding(horizontal = SnSpacing.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
    ) {
        Image(
            page.icon(),
            null,
            Modifier.size(20.dp),
            colorFilter = ColorFilter.tint(if (selected) SnColors.fg else SnColors.fgMuted),
        )
        BasicText(
            stringResource(page.title()),
            style = SnTypography.body.copy(color = if (selected) SnColors.fg else SnColors.fgSecondary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One section. Addons and Other devices are today's pages, whole. */
@Composable
private fun PageContent(
    page: SettingsPage,
    onSignIn: () -> Unit,
) {
    when (page) {
        SettingsPage.Addons -> {
            AddonsScreen()
        }

        SettingsPage.Devices -> {
            DevicesScreen()
        }

        else -> {
            PageColumn(page) {
                when (page) {
                    SettingsPage.General -> GeneralPage()
                    SettingsPage.Playback -> PlaybackPage()
                    SettingsPage.Display -> DisplayPage()
                    SettingsPage.Torrents -> TorrentsPage()
                    SettingsPage.Privacy -> PrivacyPage()
                    SettingsPage.Account -> AccountPage(onSignIn)
                    SettingsPage.About -> AboutPage()
                    SettingsPage.Addons, SettingsPage.Devices -> Unit
                }
            }
        }
    }
}

/** A section's title and its settings, scrolling, at a readable width. */
@Composable
private fun PageColumn(
    page: SettingsPage,
    content: @Composable () -> Unit,
) {
    val scroll = rememberScrollState()
    SnScrollArea(scroll) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = SnLayout.gutter, vertical = SnSpacing.space4),
        ) {
            Column(
                Modifier.widthIn(max = 880.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SnSpacing.space8),
            ) {
                SnPageTitle(stringResource(page.title()))
                content()
            }
        }
    }
}

@Composable
private fun settingsViewModel(): SettingsViewModel {
    val context = LocalPlatformContext.current
    return viewModel { SettingsViewModel(AppGraph.core(context)) }
}

/** The interface language; the system's by default (see [AppLanguage]). */
@Composable
private fun GeneralPage() {
    val context = LocalPlatformContext.current
    var language by remember { mutableStateOf(AppLanguage.current(context)) }
    val system = stringResource(Res.string.settings_language_system)
    Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space2)) {
        BasicText(stringResource(Res.string.settings_language), style = SnTypography.bodyLg.copy(color = SnColors.fg))
        BasicText(
            stringResource(Res.string.settings_language_body),
            style = SnTypography.body.copy(color = SnColors.fgMuted),
        )
        SnChipRow(
            options = listOf("") + InterfaceLanguages,
            selected = language,
            // Each in its own language, so whoever lands on the wrong one finds theirs.
            label = { if (it.isEmpty()) system else endonym(it) },
            onSelect = { tag ->
                language = tag
                AppLanguage.set(context, tag)
            },
        )
    }
}

@Composable
private fun PlaybackPage() {
    val viewModel = settingsViewModel()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val current = settings ?: return
    val off = stringResource(Res.string.player_subtitles_off)
    val best = stringResource(Res.string.onboarding_quality_best)
    val languages = playbackLanguages(deviceLanguage())
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
            options = listOf("") + (listOf(current.subtitleLanguage) + languages).filter { it.isNotEmpty() }.distinct(),
            selected = current.subtitleLanguage,
            label = { if (it.isEmpty()) off else languageName(it) },
            onSelect = { language -> viewModel.update { it.copy(subtitleLanguage = language) } },
        )
    }
    SubtitleStyleSetting(current, viewModel::update)
    Group(stringResource(Res.string.onboarding_quality)) {
        SnChipRow(
            options = listOf(0u, 2160u, 1080u, 720u),
            selected = current.maxResolution,
            label = {
                when (it) {
                    0u -> best
                    2160u -> "4K"
                    else -> "${it}p"
                }
            },
            onSelect = { lines -> viewModel.update { it.copy(maxResolution = lines) } },
        )
    }
    SettingsToggle(
        title = stringResource(Res.string.settings_aniskip),
        body = stringResource(Res.string.settings_aniskip_body),
        checked = current.aniskip,
        onToggle = { viewModel.update { it.copy(aniskip = !it.aniskip) } },
    )
    // Only where the player takes the choice (the desktop); Android keeps its own sizes.
    val automatic = remember { automaticStreamMemory() }
    if (automatic != null) VideoMemorySetting(current, automatic, viewModel::update)
}

/** HDR and refresh rate switching, where the app can change the display (Windows). */
@Composable
private fun DisplayPage() {
    val viewModel = settingsViewModel()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val hdrTitles by viewModel.hdrTitles.collectAsStateWithLifecycle()
    val current = settings ?: return
    Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space2)) {
        BasicText(stringResource(Res.string.settings_hdr), style = SnTypography.bodyLg.copy(color = SnColors.fg))
        BasicText(
            stringResource(Res.string.settings_hdr_body),
            style = SnTypography.body.copy(color = SnColors.fgMuted),
        )
        SnChipRow(
            options = listOf(DisplaySwitch.ASK, DisplaySwitch.ALWAYS, DisplaySwitch.NEVER),
            selected = current.hdr,
            label = {
                stringResource(
                    when (it) {
                        DisplaySwitch.ASK -> Res.string.settings_switch_ask
                        DisplaySwitch.ALWAYS -> Res.string.settings_switch_always
                        DisplaySwitch.NEVER -> Res.string.settings_switch_never
                    },
                )
            },
            onSelect = { choice -> viewModel.update { it.copy(hdr = choice) } },
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
                    viewModel::forgetHdrTitles,
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
            checked = current.matchRefreshRate,
            label = stringResource(Res.string.settings_refresh),
            onText = stringResource(Res.string.state_on),
            offText = stringResource(Res.string.state_off),
            onToggle = { viewModel.update { it.copy(matchRefreshRate = !it.matchRefreshRate) } },
        )
    }
}

@Composable
private fun TorrentsPage() {
    val viewModel = settingsViewModel()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val current = settings ?: return
    SettingsToggle(
        title = stringResource(Res.string.settings_wifi_only),
        body = stringResource(Res.string.settings_wifi_only_body),
        checked = current.torrentWifiOnly,
        onToggle = { viewModel.update { it.copy(torrentWifiOnly = !it.torrentWifiOnly) } },
    )
    Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space2)) {
        BasicText(stringResource(Res.string.settings_cache), style = SnTypography.bodyLg.copy(color = SnColors.fg))
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

/**
 * Crash reports. The choice lives with the reports rather than in the core's database, so a
 * crash while the core opens still knows it.
 */
@Composable
private fun PrivacyPage() {
    val mode by CrashReports.mode.collectAsStateWithLifecycle()
    val ask = stringResource(Res.string.settings_crash_ask)
    val never = stringResource(Res.string.settings_crash_never)
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

/**
 * The account: who is signed in and when it last synced, Sync now and Sign out; or signing
 * in. On TV, signed out, it opens straight on the link code and its QR code.
 */
@Composable
private fun AccountPage(onSignIn: () -> Unit) {
    val context = LocalPlatformContext.current
    val summary = rememberAccountSummary() ?: return
    val isTv = LocalIsTv.current
    var confirmSignOut by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var syncFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (confirmSignOut) {
        SnDialog(
            title = stringResource(Res.string.profile_sign_out_title),
            body = stringResource(Res.string.profile_sign_out_body),
            confirmText = stringResource(Res.string.profile_sign_out),
            dismissText = stringResource(Res.string.action_cancel),
            onConfirm = {
                confirmSignOut = false
                signOutOfStremio(context)
            },
            onDismiss = { confirmSignOut = false },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space4)) {
        AccountHeader(summary, contentPadding = PaddingValues(0.dp))
        BasicText(
            stringResource(if (summary.signedIn) Res.string.profile_account_body else Res.string.profile_local_body),
            style = SnTypography.body.copy(color = SnColors.fgMuted),
        )
    }
    if (summary.signedIn) {
        Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3)) {
                SnButton(
                    stringResource(if (syncing) Res.string.account_syncing else Res.string.account_sync_now),
                    {
                        if (syncing) return@SnButton
                        syncing = true
                        syncFailed = false
                        scope.launch {
                            val result = runCatching { AppGraph.core(context).sync() }
                            // An expired session signs out quietly, as at start-up.
                            if (result.exceptionOrNull() is CoreException.SignIn) signOutOfStremio(context)
                            syncFailed = result.isFailure
                            syncing = false
                            AccountEvents.changed()
                        }
                    },
                    style = SnButtonStyle.Secondary,
                    enabled = !syncing,
                    modifier = Modifier.initialFocus(),
                )
                SnButton(
                    stringResource(Res.string.profile_sign_out),
                    { confirmSignOut = true },
                    style = SnButtonStyle.Ghost,
                )
            }
            if (syncFailed) {
                BasicText(
                    stringResource(Res.string.account_sync_failed),
                    style = SnTypography.body.copy(color = SnColors.danger),
                )
            }
        }
    } else if (isTv) {
        val flow = rememberSignInFlow(onSignedIn = {})
        StartOnLinkCode(flow)
        Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(SnSpacing.space4)) {
            SignInPanel(flow, onCancel = null)
        }
    } else {
        Row {
            SnButton(stringResource(Res.string.onboarding_sign_in), onSignIn)
        }
    }
}

@Composable
private fun AboutPage() {
    val context = LocalPlatformContext.current
    val isTv = LocalIsTv.current
    var showTestCrashes by remember { mutableStateOf(false) }
    if (showTestCrashes) TestCrashMenu(onDismiss = { showTestCrashes = false })
    Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space2)) {
        val text = stringResource(Res.string.profile_version, remember(context) { versionName(context) })
        val style = SnTypography.bodyLg.copy(color = SnColors.fg)
        if (CrashReports.isDebugBuild) {
            // Debug builds only: selecting the version seven times opens the test-crash menu.
            var taps by remember { mutableIntStateOf(0) }
            BasicText(
                text,
                style = style,
                modifier =
                    Modifier.snClickable(RoundedCornerShape(SnRadius.radiusSm)) {
                        if (++taps >= TEST_CRASH_TAPS) {
                            taps = 0
                            showTestCrashes = true
                        }
                    },
            )
        } else {
            BasicText(text, style = style)
        }
        BasicText(
            stringResource(Res.string.settings_about_license),
            style = SnTypography.body.copy(color = SnColors.fgMuted),
        )
    }
    if (isTv) {
        // A TV has no browser to open them in; the addresses are there to type elsewhere.
        Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space2)) {
            LinkLine(stringResource(Res.string.settings_about_source), SOURCE_URL)
            LinkLine(stringResource(Res.string.settings_about_report), IssueLink.NEW_ISSUE)
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3)) {
            SnButton(
                stringResource(Res.string.settings_about_source),
                { openExternalLink(context, SOURCE_URL) },
                style = SnButtonStyle.Secondary,
            )
            SnButton(
                stringResource(Res.string.settings_about_report),
                { openExternalLink(context, IssueLink.NEW_ISSUE) },
                style = SnButtonStyle.Ghost,
            )
        }
    }
}

/** Selections of the version line that open the test-crash menu of debug builds. */
private const val TEST_CRASH_TAPS = 7

@Composable
private fun LinkLine(
    label: String,
    url: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space1)) {
        BasicText(label, style = SnTypography.label.copy(color = SnColors.fgSecondary))
        BasicText(url, style = SnTypography.body.copy(color = SnColors.fgMuted))
    }
}

/** A titled group of settings, used by Settings › Other devices. */
@Composable
internal fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SnSpacing.space4)) {
        BasicText(
            title.uppercase(),
            modifier = Modifier.semantics { heading() },
            style = SnTypography.overline.copy(color = SnColors.fgMuted),
        )
        content()
    }
}

/** A setting turned on and off, with a line explaining it. */
@Composable
internal fun SettingsToggle(
    title: String,
    body: String,
    checked: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.alpha(if (enabled) 1f else 0.45f),
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
            onToggle = { if (enabled) onToggle() },
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
