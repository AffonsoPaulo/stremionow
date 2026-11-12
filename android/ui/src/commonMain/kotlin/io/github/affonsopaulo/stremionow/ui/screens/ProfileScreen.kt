// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.ui.components.SnDialog
import io.github.affonsopaulo.stremionow.ui.components.SnListRow
import io.github.affonsopaulo.stremionow.ui.components.snClickable
import io.github.affonsopaulo.stremionow.ui.crash.CrashReports
import io.github.affonsopaulo.stremionow.ui.crash.TestCrashMenu
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.SecureStore
import io.github.affonsopaulo.stremionow.ui.data.io
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.pluralStringResource
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.platform.LocalPlatformContext
import io.github.affonsopaulo.stremionow.ui.platform.PlatformContext
import io.github.affonsopaulo.stremionow.ui.platform.appVersion
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProfileViewModel(
    private val core: StremioCore,
) : ViewModel() {
    private val mutableEmail = MutableStateFlow<String?>(null)

    /** The signed-in Stremio account, or null when the app is used locally. */
    val email = mutableEmail.asStateFlow()
    private val mutableAddonCount = MutableStateFlow<Int?>(null)

    /** Number of installed addons, or null until it is known. */
    val addonCount = mutableAddonCount.asStateFlow()

    fun signOut(context: PlatformContext) {
        SecureStore.clear(context, SecureStore.STREMIO_AUTH_KEY)
        viewModelScope.launch {
            runCatching { core.signOut() }
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            mutableAddonCount.value = runCatching { io { core.addons().size } }.getOrNull()
            mutableEmail.value = runCatching { io { core.accountEmail() } }.getOrNull()?.ifBlank { null }
        }
    }
}

/**
 * Profile and settings hub, opened from the avatar. Until Stremio accounts arrive everything
 * is stored on the device, and the header says so instead of showing a fake account.
 */
@Composable
fun ProfileScreen(
    onOpenAddons: () -> Unit,
    onOpenSettings: () -> Unit,
    onSignIn: () -> Unit,
) {
    val context = LocalPlatformContext.current
    val viewModel = viewModel { ProfileViewModel(AppGraph.core(context)) }
    val addonCount by viewModel.addonCount.collectAsStateWithLifecycle()
    val email by viewModel.email.collectAsStateWithLifecycle()
    var confirmSignOut by remember { mutableStateOf(false) }
    var showTestCrashes by remember { mutableStateOf(false) }
    if (showTestCrashes) TestCrashMenu(onDismiss = { showTestCrashes = false })
    if (confirmSignOut) {
        SnDialog(
            title = stringResource(Res.string.profile_sign_out_title),
            body = stringResource(Res.string.profile_sign_out_body),
            confirmText = stringResource(Res.string.profile_sign_out),
            dismissText = stringResource(Res.string.action_cancel),
            onConfirm = {
                confirmSignOut = false
                viewModel.signOut(context)
            },
            onDismiss = { confirmSignOut = false },
        )
    }
    // Addons can change on their own page, so recount whenever this screen comes back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val version =
        remember(context) { appVersion(context) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = SnLayout.gutter, vertical = SnSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space8),
    ) {
        item(key = "identity") { Identity(email) }
        item(key = "rows") {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SnSpacing.space2),
            ) {
                if (email == null) {
                    SnListRow(
                        icon = SnIcons.User,
                        title = stringResource(Res.string.onboarding_sign_in),
                        onClick = onSignIn,
                    )
                } else {
                    SnListRow(
                        icon = SnIcons.User,
                        title = stringResource(Res.string.profile_sign_out),
                        onClick = { confirmSignOut = true },
                    )
                }
                SnListRow(
                    icon = SnIcons.Layers,
                    title = stringResource(Res.string.nav_addons),
                    detail = addonCount?.let { pluralStringResource(Res.plurals.profile_addons_count, it, it) },
                    onClick = onOpenAddons,
                )
                SnListRow(
                    icon = SnIcons.Settings,
                    title = stringResource(Res.string.settings_title),
                    onClick = onOpenSettings,
                )
            }
        }
        if (version != null) {
            item(key = "version") {
                val text = stringResource(Res.string.profile_version, version)
                val style = SnTypography.caption.copy(color = SnColors.fgSubtle)
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
            }
        }
    }
}

/** Selections of the version line that open the test-crash menu of debug builds. */
private const val TEST_CRASH_TAPS = 7

@Composable
private fun Identity(email: String?) {
    val circle = RoundedCornerShape(SnRadius.radiusFull)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space4),
    ) {
        Box(
            Modifier
                .size(64.dp)
                .background(SnColors.surfaceRaised, circle)
                .border(1.dp, SnColors.border, circle),
            contentAlignment = Alignment.Center,
        ) {
            Image(SnIcons.User, null, Modifier.size(30.dp), colorFilter = ColorFilter.tint(SnColors.fgSecondary))
        }
        Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space1)) {
            BasicText(
                email ?: stringResource(Res.string.profile_local_name),
                style = SnTypography.headingMd.copy(color = SnColors.fg),
            )
            BasicText(
                stringResource(if (email == null) Res.string.profile_local_body else Res.string.profile_account_body),
                style = SnTypography.body.copy(color = SnColors.fgMuted),
            )
        }
    }
}
