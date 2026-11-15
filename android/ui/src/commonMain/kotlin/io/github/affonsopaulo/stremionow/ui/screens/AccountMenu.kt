// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.affonsopaulo.stremionow.core.coreVersion
import io.github.affonsopaulo.stremionow.ui.components.SnButton
import io.github.affonsopaulo.stremionow.ui.components.SnDialog
import io.github.affonsopaulo.stremionow.ui.components.SnDialogActions
import io.github.affonsopaulo.stremionow.ui.components.SnDialogFrame
import io.github.affonsopaulo.stremionow.ui.components.snClickable
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.SecureStore
import io.github.affonsopaulo.stremionow.ui.data.io
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.platform.LocalPlatformContext
import io.github.affonsopaulo.stremionow.ui.platform.PlatformContext
import io.github.affonsopaulo.stremionow.ui.platform.appVersion
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Tells every screen that shows the account that it changed: signed in, out, or synced. */
object AccountEvents {
    private val mutableChanges = MutableStateFlow(0)

    /** Bumped on every change; screens reload what they show of the account. */
    val changes: StateFlow<Int> = mutableChanges.asStateFlow()

    fun changed() = mutableChanges.update { it + 1 }
}

/** Something about the saved session the user should know. */
enum class AccountNotice {
    /** Signed in, but the secret store refused the key: the next launch starts signed out. */
    SessionNotRemembered,

    /** Signed out, but the saved key could not be removed and may sign in again at launch. */
    SessionNotCleared,
}

/** The notice shown over the app, from wherever it was raised (often a background thread). */
object AccountNotices {
    private val mutableCurrent = MutableStateFlow<AccountNotice?>(null)
    val current: StateFlow<AccountNotice?> = mutableCurrent.asStateFlow()

    fun show(notice: AccountNotice) {
        mutableCurrent.value = notice
    }

    fun dismiss() {
        mutableCurrent.value = null
    }
}

/** The signed-in account's email and last sync, or nulls on a device used without one. */
data class AccountSummary(
    val email: String?,
    val lastSyncedAt: Long?,
) {
    val signedIn: Boolean get() = email != null
}

/** The account as it is now, reloaded after every [AccountEvents] change; null while loading. */
@Composable
internal fun rememberAccountSummary(): AccountSummary? {
    val context = LocalPlatformContext.current
    val changes by AccountEvents.changes.collectAsState()
    return produceState<AccountSummary?>(null, changes) {
        value =
            runCatching {
                val core = AppGraph.core(context)
                io { AccountSummary(core.accountEmail()?.ifBlank { null }, core.lastSyncedAt()) }
            }.getOrDefault(AccountSummary(email = null, lastSyncedAt = null))
    }.value
}

/**
 * Signs out of Stremio. The key leaves the secret store at once; the server is told when it
 * can be reached. Local addons, list and progress stay.
 */
internal fun signOutOfStremio(context: PlatformContext) {
    // The secret store can block (a keyring prompt), so even the clear runs off the main thread.
    AppGraph.scope.launch {
        if (!SecureStore.clear(context, SecureStore.STREMIO_AUTH_KEY)) {
            AccountNotices.show(AccountNotice.SessionNotCleared)
        }
        runCatching { AppGraph.core(context).signOut() }
        AccountEvents.changed()
    }
}

/** How the last sync reads in the account's header. */
internal sealed interface SyncStatus {
    /** In the last minute. */
    data object JustNow : SyncStatus

    /** Earlier: the time today, or the date and time before today, in the region's format. */
    data class At(
        val text: String,
    ) : SyncStatus

    data object Never : SyncStatus
}

internal fun syncStatus(
    lastSyncedAt: Long?,
    now: Long,
    locale: Locale = Locale.getDefault(),
    zone: TimeZone = TimeZone.getDefault(),
): SyncStatus {
    if (lastSyncedAt == null || lastSyncedAt <= 0) return SyncStatus.Never
    if (now - lastSyncedAt in 0 until JUST_NOW_MS) return SyncStatus.JustNow
    val format =
        if (sameDay(lastSyncedAt, now, zone)) {
            DateFormat.getTimeInstance(DateFormat.SHORT, locale)
        } else {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale)
        }
    format.timeZone = zone
    return SyncStatus.At(format.format(Date(lastSyncedAt)))
}

private const val JUST_NOW_MS = 60_000L

private fun sameDay(
    a: Long,
    b: Long,
    zone: TimeZone,
): Boolean {
    val first = Calendar.getInstance(zone).apply { timeInMillis = a }
    val second = Calendar.getInstance(zone).apply { timeInMillis = b }
    return first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
        first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
}

@Composable
internal fun syncStatusText(summary: AccountSummary): String =
    when (val status = syncStatus(summary.lastSyncedAt, System.currentTimeMillis())) {
        SyncStatus.JustNow -> stringResource(Res.string.account_synced_just_now)
        is SyncStatus.At -> stringResource(Res.string.account_synced_at, status.text)
        SyncStatus.Never -> stringResource(Res.string.account_not_synced)
    }

/** The app's version for the menu and About, such as "0.0.1". */
internal fun versionName(context: PlatformContext): String =
    appVersion(context) ?: runCatching { coreVersion() }.getOrDefault("")

/**
 * What the avatar opens, and the dialogs it leads to. One per app, created at its root so the
 * dialogs open over whatever page shows.
 */
@Stable
class AccountUi {
    /** The anchored menu (large screens) or the bottom sheet (phones). */
    var menuOpen by mutableStateOf(false)

    /** The sign-in dialog, over the current page. */
    var signInOpen by mutableStateOf(false)

    /** Asking before signing out. */
    var confirmSignOut by mutableStateOf(false)
}

/**
 * The sign-in dialog and the sign-out question, wherever they were asked for, and the phone's
 * account sheet when [sheet] is set.
 */
@Composable
fun AccountOverlays(
    ui: AccountUi,
    sheet: Boolean,
    onOpenAddons: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalPlatformContext.current
    if (sheet && ui.menuOpen) {
        AccountSheet(ui, onOpenAddons, onOpenSettings)
    }
    if (ui.signInOpen) {
        SignInDialog(onDismiss = { ui.signInOpen = false })
    }
    if (ui.confirmSignOut) {
        SnDialog(
            title = stringResource(Res.string.profile_sign_out_title),
            body = stringResource(Res.string.profile_sign_out_body),
            confirmText = stringResource(Res.string.profile_sign_out),
            dismissText = stringResource(Res.string.action_cancel),
            onConfirm = {
                ui.confirmSignOut = false
                signOutOfStremio(context)
            },
            onDismiss = { ui.confirmSignOut = false },
        )
    }
    val notice by AccountNotices.current.collectAsState()
    notice?.let { AccountNoticeDialog(it, onDismiss = AccountNotices::dismiss) }
}

@Composable
private fun AccountNoticeDialog(
    notice: AccountNotice,
    onDismiss: () -> Unit,
) {
    val (title, body) =
        when (notice) {
            AccountNotice.SessionNotRemembered -> {
                Res.string.account_session_not_remembered_title to Res.string.account_session_not_remembered_body
            }

            AccountNotice.SessionNotCleared -> {
                Res.string.account_session_not_cleared_title to Res.string.account_session_not_cleared_body
            }
        }
    SnDialogFrame(title = stringResource(title), onDismiss = onDismiss) {
        val closeFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }
        BasicText(stringResource(body), style = SnTypography.body.copy(color = SnColors.fgSecondary))
        SnDialogActions {
            SnButton(stringResource(Res.string.action_close), onDismiss, modifier = Modifier.focusRequester(closeFocus))
        }
    }
}

/** The avatar's menu on large screens, anchored below the avatar it is composed in. */
@Composable
fun AccountMenuPopup(
    ui: AccountUi,
    onOpenAddons: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (!ui.menuOpen) return
    val gap = with(LocalDensity.current) { SnSpacing.space2.roundToPx() }
    val position = remember(gap) { BelowAnchorEnd(gap) }
    val close = { ui.menuOpen = false }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = close,
        properties = PopupProperties(focusable = true),
    ) {
        val shape = RoundedCornerShape(SnRadius.radiusLg)
        Box(
            Modifier
                .width(320.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        close()
                        true
                    } else {
                        false
                    }
                }.background(SnColors.surfaceRaised, shape)
                .border(1.dp, SnColors.border, shape)
                .padding(vertical = SnSpacing.space2),
        ) {
            AccountMenuContent(ui, onOpenAddons, onOpenSettings)
        }
    }
}

/**
 * The phone's account sheet: the menu's items, rising from the bottom over a scrim. A dialog
 * window, so it covers the tab bar too and Back closes it.
 */
@Composable
private fun AccountSheet(
    ui: AccountUi,
    onOpenAddons: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val close = { ui.menuOpen = false }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        SheetContent(ui, close, onOpenAddons, onOpenSettings)
    }
}

@Composable
private fun SheetContent(
    ui: AccountUi,
    close: () -> Unit,
    onOpenAddons: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(SnColors.scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = close,
                ),
        )
        val shape = RoundedCornerShape(topStart = SnRadius.radiusXl, topEnd = SnRadius.radiusXl)
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(SnColors.surfaceRaised, shape)
                .border(1.dp, SnColors.border, shape)
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                .padding(top = SnSpacing.space2, bottom = SnSpacing.space3),
        ) {
            // The grab handle says it is a sheet; a tap above it closes it.
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = SnSpacing.space2)
                    .size(width = 40.dp, height = 4.dp)
                    .background(SnColors.borderStrong, RoundedCornerShape(SnRadius.radiusFull)),
            )
            AccountMenuContent(ui, onOpenAddons, onOpenSettings)
        }
    }
}

@Composable
private fun AccountMenuContent(
    ui: AccountUi,
    onOpenAddons: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalPlatformContext.current
    val summary = rememberAccountSummary()
    val title = stringResource(Res.string.account_menu)
    val first = remember { FocusRequester() }
    // Keyboard users land on the first item, so the menu works without a mouse.
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxWidth().semantics { paneTitle = title }) {
        AccountHeader(summary)
        Divider()
        val close = { ui.menuOpen = false }
        if (summary?.signedIn == true) {
            MenuItem(SnIcons.LogOut, stringResource(Res.string.profile_sign_out), Modifier.focusRequester(first)) {
                close()
                ui.confirmSignOut = true
            }
        } else {
            MenuItem(SnIcons.User, stringResource(Res.string.onboarding_sign_in), Modifier.focusRequester(first)) {
                close()
                ui.signInOpen = true
            }
        }
        MenuItem(SnIcons.Layers, stringResource(Res.string.nav_addons)) {
            close()
            onOpenAddons()
        }
        MenuItem(SnIcons.Settings, stringResource(Res.string.settings_title)) {
            close()
            onOpenSettings()
        }
        Divider()
        BasicText(
            stringResource(Res.string.profile_version, remember(context) { versionName(context) }),
            modifier = Modifier.padding(horizontal = SnSpacing.space4, vertical = SnSpacing.space2),
            style = SnTypography.caption.copy(color = SnColors.fgSubtle),
        )
    }
}

/** The account's email and last sync, or "This device" without an account. */
@Composable
internal fun AccountHeader(
    summary: AccountSummary?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = SnSpacing.space4, vertical = SnSpacing.space3),
) {
    val circle = RoundedCornerShape(SnRadius.radiusFull)
    Row(
        modifier.fillMaxWidth().padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .background(SnColors.surfaceMuted, circle)
                .border(1.dp, SnColors.border, circle),
            contentAlignment = Alignment.Center,
        ) {
            Image(SnIcons.User, null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(SnColors.fgSecondary))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(
                summary?.email ?: stringResource(Res.string.profile_local_name),
                style = SnTypography.bodyLg.copy(color = SnColors.fg),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary?.signedIn == true) {
                BasicText(syncStatusText(summary), style = SnTypography.caption.copy(color = SnColors.fgMuted))
            }
        }
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = SnSpacing.space1)
            .height(1.dp)
            .background(SnColors.border),
    )
}

@Composable
private fun MenuItem(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(SnRadius.radiusMd)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = SnSpacing.space2)
            .heightIn(min = 48.dp)
            .snClickable(shape, onClick = onClick)
            .padding(horizontal = SnSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
    ) {
        Image(icon, null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(SnColors.fgSecondary))
        BasicText(
            text,
            style = SnTypography.body.copy(color = SnColors.fg),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Below the anchor, its end edge lined up with the anchor's, kept inside the window. */
private class BelowAnchorEnd(
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
                anchorBounds.right - popupContentSize.width
            } else {
                anchorBounds.left
            }
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x.coerceIn(0, maxX), (anchorBounds.bottom + gap).coerceIn(0, maxY))
    }
}
