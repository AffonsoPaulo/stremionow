// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.SwingDialog
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.affonsopaulo.stremionow.core.Episode
import io.github.affonsopaulo.stremionow.player.MpvPlayer
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.PlaybackSession
import io.github.affonsopaulo.stremionow.ui.desktop.DesktopInput
import io.github.affonsopaulo.stremionow.ui.desktop.DisplayChoices
import io.github.affonsopaulo.stremionow.ui.desktop.LocalDesktopHost
import io.github.affonsopaulo.stremionow.ui.desktop.MediaKeys
import io.github.affonsopaulo.stremionow.ui.desktop.OverlayState
import io.github.affonsopaulo.stremionow.ui.desktop.PlayerOverlay
import io.github.affonsopaulo.stremionow.ui.desktop.ProvideDesktopInput
import io.github.affonsopaulo.stremionow.ui.desktop.VideoWindow
import io.github.affonsopaulo.stremionow.ui.desktop.WindowPair
import io.github.affonsopaulo.stremionow.ui.desktop.WindowedPlayer
import io.github.affonsopaulo.stremionow.ui.desktop.display.DesktopDisplays
import io.github.affonsopaulo.stremionow.ui.desktop.handleMediaCommand
import io.github.affonsopaulo.stremionow.ui.desktop.handlePlayerShortcut
import io.github.affonsopaulo.stremionow.ui.desktop.playerBack
import io.github.affonsopaulo.stremionow.ui.platform.LocalPlatformContext
import kotlinx.coroutines.launch
import java.awt.Dialog
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.WindowConstants

/**
 * The desktop player, in the two windows of docs/adr/0005-desktop-ui.md: mpv draws into a native
 * video window, and the controls are Compose content in a transparent window kept on top of it.
 * The pair opens where the app's window is and the app's window steps aside; leaving the player
 * (Back, Esc, closing the window, another source or episode) brings it back first.
 */
@Composable
actual fun PlayerScreen(
    onBack: () -> Unit,
    onChangeSource: () -> Unit,
    onOpenEpisode: (Episode) -> Unit,
) {
    // What this player opened with. Moving on to the next episode clears the session's source
    // before the player has finished leaving; read live, that made the leaving player press
    // Back and drop the new episode's source list, landing on the title page.
    val selection = remember { PlaybackSession.selection }
    val source = remember { PlaybackSession.source }
    if (selection == null || (source?.url == null && source?.infoHash == null)) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val host = LocalDesktopHost.current
    val context = LocalPlatformContext.current
    val viewModel =
        viewModel(key = "player-${selection.videoId}-${source.url.hashCode()}") {
            PlayerViewModel(AppGraph.core(context), selection, source) { options ->
                WindowedPlayer.open(
                    title = "${selection.details.item.name} – StremioNow",
                    bounds = host.playerBounds(),
                    icons = host.icons,
                ) { canvas ->
                    MpvPlayer.create(
                        canvas = canvas,
                        libraryDirectory = host.nativeLibraries,
                        caFile = host.caBundle,
                        options = options.copy(verboseLogs = host.verboseLogs),
                    )
                }
            }
        }
    val player = viewModel.player
    // HDR and refresh rate changes for this player, put back when it goes (Windows only).
    val display = remember(player) { DesktopDisplays.session(AppGraph.core(context), selection.details.item.id) }
    // One instance per player: the overlay restarts its display check when this changes.
    val displayChoices =
        remember(display) {
            display?.let { DisplayChoices(it, player.video.frame) { on -> player.mpv?.setColorspaceHint(on) } }
        }
    val state = remember { OverlayState() }
    val leave = remember(player) { Leave() }
    val overlay = remember(player) { createOverlay(player.video, onCloseRequest = leave::back) }
    val pair = remember(player) { WindowPair(player.video.frame, overlay) }
    SideEffect {
        leave.onBack = onBack
        leave.restore = {
            player.mpv?.setColorspaceHint(false)
            display?.let { AppGraph.scope.launch(DesktopDisplays.dispatcher) { it.restore() } }
            host.comeBack(if (pair.isFullScreen) null else player.video.frame.bounds)
            player.video.hide()
        }
    }

    // Also when the app closes with the player open: never leave the video window behind.
    DisposableEffect(player) {
        onDispose {
            display?.let { AppGraph.scope.launch(DesktopDisplays.dispatcher) { it.restore() } }
            player.video.hide()
        }
    }

    val next = viewModel.selection.nextEpisode
    val playNext: (() -> Unit)? = next?.let { episode -> { leave.to { onOpenEpisode(episode) } } }
    val currentPlayNext by rememberUpdatedState(playNext)
    val input = remember { DesktopInput() }
    SwingDialog(
        create = { overlay },
        dispose = ComposeDialog::dispose,
        onPreviewKeyEvent = { event ->
            input.onPreviewKeyEvent(event)
            handlePlayerShortcut(event, player, pair, state, onBack = leave::back, onNext = currentPlayNext)
        },
    ) {
        DisposableEffect(pair) {
            pair.attach()
            val mediaKeys =
                MediaKeys.install(overlay) { command -> handleMediaCommand(command, player, state, currentPlayNext) }
            // Hand the foreground to the player before the app's window steps aside: hiding the
            // active window first lets the system activate whatever comes next, often another app.
            player.video.frame.toFront()
            host.stepAside()
            onDispose {
                mediaKeys.close()
                pair.close()
            }
        }
        ProvideDesktopInput(input, onBack = { playerBack(pair, state, leave::back) }) {
            PlayerOverlay(
                viewModel = viewModel,
                pair = pair,
                state = state,
                display = displayChoices,
                onBack = leave::back,
                onChangeSource = { leave.to(onChangeSource) },
                onOpenEpisode = { episode -> leave.to { onOpenEpisode(episode) } },
            )
        }
    }
}

/**
 * Leaves the player once, however many requests arrive (a double click, Esc and the close
 * button together), bringing the app's window back with [restore] before navigating.
 */
private class Leave {
    var onBack: () -> Unit = {}
    var restore: () -> Unit = {}
    private var left = false

    fun back() = to(onBack)

    fun to(navigate: () -> Unit) {
        if (left) return
        left = true
        restore()
        navigate()
    }
}

/**
 * The transparent window over the video. It is a dialog owned by the video window, so the system
 * keeps it right above the video, minimizes it along with it and gives the pair a single taskbar
 * entry; [WindowPair] keeps the two aligned.
 */
private fun createOverlay(
    video: VideoWindow,
    onCloseRequest: () -> Unit,
): ComposeDialog =
    ComposeDialog(video.frame, Dialog.ModalityType.MODELESS).apply {
        title = video.frame.title
        isUndecorated = true
        isTransparent = true
        // Compose's own edge resizer would resize the overlay alone and has no minimum size; the
        // overlay draws its own handles, which resize both windows in one step (WindowPair).
        isResizable = false
        bounds = video.frame.bounds
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        // Alt+F4 on the overlay, or "Close window" on the taskbar entry of the video window.
        val closeListener =
            object : WindowAdapter() {
                override fun windowClosing(event: WindowEvent) = onCloseRequest()
            }
        addWindowListener(closeListener)
        video.frame.addWindowListener(closeListener)
    }
