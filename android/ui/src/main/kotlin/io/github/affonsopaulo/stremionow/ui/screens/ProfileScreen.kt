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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.ui.R
import io.github.affonsopaulo.stremionow.ui.components.SnListRow
import io.github.affonsopaulo.stremionow.ui.components.snClickable
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.io
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
    private val mutableAddonCount = MutableStateFlow<Int?>(null)

    /** Number of installed addons, or null until it is known. */
    val addonCount = mutableAddonCount.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            mutableAddonCount.value = runCatching { io { core.addons().size } }.getOrNull()
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
) {
    val context = LocalContext.current
    val viewModel = viewModel { ProfileViewModel(AppGraph.core(context)) }
    val addonCount by viewModel.addonCount.collectAsStateWithLifecycle()
    // Addons can change on their own page, so recount whenever this screen comes back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val version =
        remember(context) {
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = SnLayout.gutter, vertical = SnSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space8),
    ) {
        item(key = "identity") { Identity() }
        item(key = "rows") {
            Column(
                modifier = Modifier.widthIn(max = 720.dp),
                verticalArrangement = Arrangement.spacedBy(SnSpacing.space2),
            ) {
                SnListRow(
                    icon = SnIcons.Layers,
                    title = stringResource(R.string.nav_addons),
                    detail = addonCount?.let { pluralStringResource(R.plurals.profile_addons_count, it, it) },
                    onClick = onOpenAddons,
                )
                SnListRow(
                    icon = SnIcons.Settings,
                    title = stringResource(R.string.settings_title),
                    onClick = onOpenSettings,
                )
            }
        }
        if (version != null) {
            item(key = "version") {
                BasicText(
                    stringResource(R.string.profile_version, version),
                    style = SnTypography.caption.copy(color = SnColors.fgSubtle),
                )
            }
        }
    }
}

@Composable
private fun Identity() {
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
                stringResource(R.string.profile_local_name),
                style = SnTypography.headingMd.copy(color = SnColors.fg),
            )
            BasicText(
                stringResource(R.string.profile_local_body),
                style = SnTypography.body.copy(color = SnColors.fgMuted),
            )
        }
    }
}
