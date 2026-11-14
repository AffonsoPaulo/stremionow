// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.affonsopaulo.stremionow.core.AddonInfo
import io.github.affonsopaulo.stremionow.ui.components.SnButton
import io.github.affonsopaulo.stremionow.ui.components.SnButtonStyle
import io.github.affonsopaulo.stremionow.ui.components.SnDialog
import io.github.affonsopaulo.stremionow.ui.components.SnDialogActions
import io.github.affonsopaulo.stremionow.ui.components.SnDialogFrame
import io.github.affonsopaulo.stremionow.ui.components.SnQrCode
import io.github.affonsopaulo.stremionow.ui.components.SnTextField
import io.github.affonsopaulo.stremionow.ui.components.fitsQrCode
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.platform.LocalPlatformContext
import io.github.affonsopaulo.stremionow.ui.platform.canShareText
import io.github.affonsopaulo.stremionow.ui.platform.copyToClipboard
import io.github.affonsopaulo.stremionow.ui.platform.openWebPage
import io.github.affonsopaulo.stremionow.ui.platform.rememberLocalNetworkAccess
import io.github.affonsopaulo.stremionow.ui.platform.shareText
import io.github.affonsopaulo.stremionow.ui.theme.FormFactor
import io.github.affonsopaulo.stremionow.ui.theme.LocalIsTv
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography

/** Size of the QR codes a TV shows; a phone reads it from across a room. */
private val QrSize = 240.dp

/** Width of a dialog with a QR code beside its text. */
internal val CodeDialogWidth = 760.dp

/**
 * "Add addon": a link, checked by downloading its manifest, then a summary of the addon to
 * confirm before anything is installed. Back from the summary returns to the link.
 */
@Composable
internal fun AddLinkDialog(
    link: LinkFlow,
    onUrlChange: (String) -> Unit,
    onCheck: () -> Unit,
    onLocalNetworkDenied: () -> Unit,
    onEdit: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val preview = link.preview
    if (preview != null) {
        InstallConfirmDialog(
            addon = preview.addon,
            replaces = preview.replaces,
            installing = link.installing,
            failed = link.installFailed,
            changed = link.changed,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
            onBack = onEdit,
        )
        return
    }
    // An addon on the user's network needs local network access first (Android 17+); ask
    // for it at the moment it is needed, then check either way so the outcome is shown.
    val requestLocalNetwork =
        rememberLocalNetworkAccess { granted ->
            if (granted) onCheck() else onLocalNetworkDenied()
        }
    val keyboard = LocalSoftwareKeyboardController.current
    val canContinue = link.url.isNotBlank() && !link.checking
    val submit = {
        if (canContinue) {
            keyboard?.hide()
            requestLocalNetwork(link.url.trim())
        }
    }
    SnDialogFrame(title = stringResource(Res.string.addons_add), onDismiss = onDismiss) {
        val fieldFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }
        BasicText(
            stringResource(Res.string.addons_add_body),
            style = SnTypography.body.copy(color = SnColors.fgSecondary),
        )
        SnTextField(
            value = link.url,
            onValueChange = onUrlChange,
            label = stringResource(Res.string.addons_install_label),
            hint = stringResource(Res.string.addons_install_hint),
            isError = link.error != null,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
            onImeAction = submit,
            focusRequester = fieldFocus,
            modifier = Modifier.fillMaxWidth(),
        )
        link.error?.let { error ->
            BasicText(
                stringResource(
                    when (error) {
                        LinkError.Invalid -> Res.string.addons_install_error
                        LinkError.Unreachable -> Res.string.addons_install_failed
                        LinkError.LocalNetworkDenied -> Res.string.addons_local_network_needed
                    },
                ),
                style = SnTypography.caption.copy(color = SnColors.danger),
            )
        }
        SnDialogActions {
            SnButton(stringResource(Res.string.action_cancel), onDismiss, style = SnButtonStyle.Ghost)
            SnButton(
                text =
                    stringResource(
                        if (link.checking) Res.string.addons_checking else Res.string.addons_add_continue,
                    ),
                onClick = submit,
                enabled = canContinue,
            )
        }
    }
}

/**
 * Confirmation before installing: what the addon provides, what it replaces, and that it can
 * see what the user watches. Cancel has the initial focus, as in every confirmation.
 */
@Composable
internal fun InstallConfirmDialog(
    addon: AddonInfo,
    replaces: AddonInfo?,
    installing: Boolean,
    failed: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onBack: () -> Unit = onDismiss,
    changed: Boolean = false,
) {
    SnDialogFrame(title = stringResource(Res.string.addons_install_title, addon.name), onDismiss = onBack) {
        val cancelFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
        ) {
            AddonLogo(addon, size = 48.dp)
            // The title names the addon; this says what it covers.
            Column(Modifier.weight(1f)) {
                typesText(addon)?.let {
                    BasicText(
                        it,
                        style = SnTypography.label.copy(color = SnColors.fg),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (addon.version.isNotBlank()) {
                    BasicText(
                        stringResource(Res.string.addons_version, addon.version),
                        style = SnTypography.caption.copy(color = SnColors.fgMuted),
                    )
                }
            }
        }
        if (changed) {
            BasicText(
                stringResource(Res.string.addons_install_changed),
                style = SnTypography.body.copy(color = SnColors.warning),
            )
        }
        providesText(addon)?.let {
            BasicText(
                stringResource(Res.string.addons_provides, it),
                style = SnTypography.body.copy(color = SnColors.fg),
            )
        }
        if (addon.configurationRequired) {
            BasicText(
                stringResource(Res.string.addons_install_needs_setup),
                style = SnTypography.body.copy(color = SnColors.warning),
            )
        }
        if (replaces != null) {
            BasicText(
                if (replaces.manifestUrl == addon.manifestUrl) {
                    stringResource(Res.string.addons_install_refreshes, replaces.name)
                } else {
                    stringResource(Res.string.addons_install_replaces, replaces.name)
                },
                style = SnTypography.body.copy(color = SnColors.fgSecondary),
            )
        }
        BasicText(
            stringResource(Res.string.addons_install_privacy),
            style = SnTypography.body.copy(color = SnColors.fgSecondary),
        )
        if (failed) {
            BasicText(
                stringResource(Res.string.addons_install_failed),
                style = SnTypography.caption.copy(color = SnColors.danger),
            )
        }
        SnDialogActions {
            SnButton(
                text = stringResource(Res.string.action_cancel),
                onClick = onDismiss,
                style = SnButtonStyle.Ghost,
                modifier = Modifier.focusRequester(cancelFocus),
            )
            SnButton(
                text =
                    stringResource(
                        if (installing) Res.string.addons_installing else Res.string.addons_install_action,
                    ),
                onClick = onConfirm,
                enabled = !installing,
            )
        }
    }
}

/**
 * Shares an addon's link: copy or the share sheet where there is one, a QR code on TV. A link
 * that seems to carry the user's settings (and maybe a debrid key) shows a warning first.
 */
@Composable
internal fun ShareAddonDialog(
    addon: AddonInfo,
    onDismiss: () -> Unit,
) {
    var revealed by rememberSaveable(addon.manifestUrl) { mutableStateOf(!addon.linkHasSettings) }
    if (!revealed) {
        SnDialog(
            title = stringResource(Res.string.addons_share_private_title),
            body = stringResource(Res.string.addons_share_private_body),
            confirmText = stringResource(Res.string.addons_share_private_confirm),
            dismissText = stringResource(Res.string.action_cancel),
            onConfirm = { revealed = true },
            onDismiss = onDismiss,
            destructive = true,
        )
        return
    }
    val context = LocalPlatformContext.current
    val isTv = LocalIsTv.current
    val url = addon.manifestUrl
    var copied by remember { mutableStateOf(false) }
    val clipLabel = stringResource(Res.string.addons_clip_label)
    SnDialogFrame(
        title = stringResource(Res.string.addons_share_title, addon.name),
        onDismiss = onDismiss,
        maxWidth = if (isTv) CodeDialogWidth else 440.dp,
    ) {
        val closeFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }
        val text: @Composable () -> Unit = {
            BasicText(
                stringResource(if (isTv) Res.string.addons_share_tv_body else Res.string.addons_share_body),
                style = SnTypography.body.copy(color = SnColors.fgSecondary),
            )
            LinkText(url)
        }
        if (isTv) TextBesideCode(url, stringResource(Res.string.addons_qr_description), text) else text()
        SnDialogActions {
            // A TV has no use for its clipboard, and its share sheet rarely leads anywhere, so
            // Close is its one action. Elsewhere sharing leads: the share sheet where there is
            // one (phones), else copying (the desktop).
            val canShare = !isTv && canShareText(context)
            SnButton(
                text = stringResource(Res.string.action_close),
                onClick = onDismiss,
                style = if (isTv) SnButtonStyle.Primary else SnButtonStyle.Ghost,
                modifier = Modifier.focusRequester(closeFocus),
            )
            if (!isTv) {
                SnButton(
                    text = stringResource(if (copied) Res.string.addons_copied else Res.string.addons_copy_link),
                    onClick = {
                        copyToClipboard(context, clipLabel, url, sensitive = addon.linkHasSettings)
                        copied = true
                    },
                    style = if (canShare) SnButtonStyle.Tonal else SnButtonStyle.Primary,
                )
            }
            if (canShare) {
                SnButton(
                    text = stringResource(Res.string.addons_share),
                    onClick = { shareText(context, addon.name, url) },
                    icon = SnIcons.Share,
                )
            }
        }
    }
}

/**
 * Configures an addon on its own page: in the browser, or from a QR code on TV (and wherever
 * no browser opens it). The page gives a new install link, which "Add new link" takes; the
 * core then puts the new one in the old one's place.
 */
@Composable
internal fun ConfigureAddonDialog(
    addon: AddonInfo,
    onAddLink: () -> Unit,
    onDismiss: () -> Unit,
) {
    val url = addon.configureUrl ?: return
    val context = LocalPlatformContext.current
    val isTv = LocalIsTv.current
    // Only an addon the user installed over plain HTTP (one on their own network) has a page
    // without HTTPS; Discover lists HTTPS addons only.
    val allowHttp = addon.manifestUrl.startsWith("http://")
    var showCode by remember { mutableStateOf(isTv) }
    var noBrowser by remember { mutableStateOf(false) }
    SnDialogFrame(
        title = stringResource(Res.string.addons_configure_title, addon.name),
        onDismiss = onDismiss,
        maxWidth = if (showCode && SnLayout.formFactor != FormFactor.Phone) CodeDialogWidth else 440.dp,
    ) {
        val mainFocus = remember { FocusRequester() }
        LaunchedEffect(showCode) { runCatching { mainFocus.requestFocus() } }
        if (showCode) {
            TextBesideCode(url, stringResource(Res.string.addons_configure_qr_description)) {
                if (noBrowser) {
                    BasicText(
                        stringResource(Res.string.addons_configure_no_browser),
                        style = SnTypography.body.copy(color = SnColors.fg),
                    )
                }
                BasicText(
                    stringResource(Res.string.addons_configure_tv_body),
                    style = SnTypography.body.copy(color = SnColors.fgSecondary),
                )
                if (addon.linkHasSettings) {
                    BasicText(
                        stringResource(Res.string.addons_configure_private),
                        style = SnTypography.caption.copy(color = SnColors.warning),
                    )
                }
            }
        } else {
            BasicText(
                stringResource(Res.string.addons_configure_body),
                style = SnTypography.body.copy(color = SnColors.fgSecondary),
            )
        }
        SnDialogActions {
            SnButton(stringResource(Res.string.action_cancel), onDismiss, style = SnButtonStyle.Ghost)
            if (showCode) {
                SnButton(
                    text = stringResource(Res.string.addons_configure_add_link),
                    onClick = onAddLink,
                    modifier = Modifier.focusRequester(mainFocus),
                )
            } else {
                SnButton(
                    text = stringResource(Res.string.addons_configure_add_link),
                    onClick = onAddLink,
                    style = SnButtonStyle.Tonal,
                )
                SnButton(
                    text = stringResource(Res.string.addons_configure_open),
                    onClick = {
                        if (!openWebPage(context, url, allowHttp)) {
                            noBrowser = true
                            showCode = true
                        }
                    },
                    modifier = Modifier.focusRequester(mainFocus),
                )
            }
        }
    }
}

/**
 * [text] with [url] as a QR code: side by side where the screen is wide, so the dialog fits a
 * TV's height, stacked on a phone. A link too long for a code gets a note instead.
 */
@Composable
internal fun TextBesideCode(
    url: String,
    description: String,
    text: @Composable () -> Unit,
) {
    val column: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(SnSpacing.space3)) { text() }
    }
    when {
        !fitsQrCode(url) -> {
            column(Modifier.fillMaxWidth())
            BasicText(
                stringResource(Res.string.addons_qr_too_long),
                style = SnTypography.caption.copy(color = SnColors.fgMuted),
            )
        }

        SnLayout.formFactor != FormFactor.Phone -> {
            Row(horizontalArrangement = Arrangement.spacedBy(SnSpacing.space6)) {
                column(Modifier.weight(1f))
                SnQrCode(url, size = QrSize, description = description)
            }
        }

        else -> {
            column(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                SnQrCode(url, size = QrSize, description = description)
            }
        }
    }
}

/** A link in a quiet box, cut after a few lines: enough to recognize, not to fill the dialog. */
@Composable
internal fun LinkText(url: String) {
    val shape = RoundedCornerShape(SnRadius.radiusMd)
    BasicText(
        url,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SnColors.surfaceInput, shape)
                .border(1.dp, SnColors.border, shape)
                .padding(horizontal = SnSpacing.space3, vertical = SnSpacing.space2),
        style = SnTypography.caption.copy(color = SnColors.fgSecondary),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}
