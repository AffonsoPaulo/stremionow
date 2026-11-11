// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.SwingDialog
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.application
import io.github.affonsopaulo.stremionow.core.coreVersion
import io.github.affonsopaulo.stremionow.desktop.mpv.Player
import io.github.affonsopaulo.stremionow.desktop.mpv.PlayerOptions
import io.github.affonsopaulo.stremionow.desktop.ui.ControlsState
import io.github.affonsopaulo.stremionow.desktop.ui.PlayerActions
import io.github.affonsopaulo.stremionow.desktop.ui.PlayerOverlay
import io.github.affonsopaulo.stremionow.desktop.ui.SEEK_STEP_SECONDS
import io.github.affonsopaulo.stremionow.desktop.window.ResizeEdge
import io.github.affonsopaulo.stremionow.desktop.window.VideoWindow
import io.github.affonsopaulo.stremionow.desktop.window.WindowPair
import java.awt.Dialog
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.net.URLDecoder
import javax.swing.WindowConstants

/** Played when no address is given on the command line. */
private const val DEFAULT_URL =
    "https://repo.jellyfin.org/test-videos/SDR/AVC/Test%20Jellyfin%201080p%20AVC%2010M.mp4"

private const val APP_NAME = "StremioNow"

private val log = System.getLogger("StremioNow")

/**
 * Phase 5 proof of concept: plays one video in a native mpv window with Compose controls drawn
 * over it from a transparent window.
 *
 * Usage: `StremioNow [--verbose] [url]`. `--verbose` turns on mpv's detailed logs, which include
 * the addresses mpv opens.
 */
fun main(args: Array<String>) {
    val verbose = "--verbose" in args
    val url = args.firstOrNull { !it.startsWith("--") } ?: DEFAULT_URL
    NativeLibraries.install()
    val core =
        runCatching { coreVersion() }
            .onFailure { log.log(System.Logger.Level.ERROR, "The Rust core could not be loaded", it) }
            .getOrNull()
    log.log(System.Logger.Level.INFO, "StremioNow core ${core ?: "unavailable"}")

    application {
        val session = remember { PlaybackSession.open(url, PlayerOptions(verboseLogs = verbose)) }
        // Declared before the overlay, so it runs after the overlay is gone: overlay, mpv, then
        // the video window, whose child window mpv draws into.
        DisposableEffect(session) { onDispose(session::close) }
        PlayerWindow(session, coreVersion = core, onExit = ::exitApplication)
    }
}

/** The video window and the player drawing into it. */
private class PlaybackSession(
    val video: VideoWindow,
    val player: Player,
    val title: String,
) : AutoCloseable {
    override fun close() {
        player.close()
        video.dispose()
    }

    companion object {
        fun open(
            url: String,
            options: PlayerOptions,
        ): PlaybackSession {
            val title = titleOf(url)
            val video = VideoWindow("$title – $APP_NAME", AppIcon.images)
            val handle = video.show(initialBounds())
            val player =
                try {
                    Player.create(handle, NativeLibraries.caBundle, options)
                } catch (failure: Throwable) {
                    video.dispose()
                    throw failure
                }
            player.load(url)
            return PlaybackSession(video, player, title)
        }

        /** 1280 × 720 (or 80 % of a smaller screen), centered on the main screen. */
        private fun initialBounds(): Rectangle {
            val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
            val width = minOf(1280, screen.width * 4 / 5)
            val height = minOf(720, screen.height * 4 / 5)
            return Rectangle(
                screen.x + (screen.width - width) / 2,
                screen.y + (screen.height - height) / 2,
                width,
                height,
            )
        }

        /** The file name of [url] without its extension, for example "Test Jellyfin 1080p AVC 10M". */
        private fun titleOf(url: String): String {
            val name = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
            return runCatching { URLDecoder.decode(name, Charsets.UTF_8) }
                .getOrDefault(name)
                .substringBeforeLast('.')
                .ifBlank { APP_NAME }
        }
    }
}

/**
 * The transparent Compose window over the video. It is a dialog owned by the video window, so
 * the system keeps it right above the video, minimizes it along with it and gives the pair a
 * single taskbar entry; [WindowPair] keeps the two aligned.
 */
@Composable
private fun PlayerWindow(
    session: PlaybackSession,
    coreVersion: String?,
    onExit: () -> Unit,
) {
    val overlay = remember { createOverlay(session, onExit) }
    val pair = remember { WindowPair(session.video.frame, overlay) }
    val controls = remember { ControlsState() }
    val actions = remember { Actions(session.player, pair, controls, onExit) }

    SwingDialog(
        create = { overlay },
        dispose = ComposeDialog::dispose,
        onPreviewKeyEvent = { event -> handleShortcut(event, actions, pair, controls) },
    ) {
        DisposableEffect(pair) {
            pair.attach()
            log.log(System.Logger.Level.INFO, "Overlay renders with ${overlay.renderApi}")
            onDispose(pair::close)
        }
        val state by session.player.state.collectAsState()
        val decoding =
            when {
                state.error != null -> "not playing"
                !state.isLoaded -> "loading"
                state.hardwareDecoder != null -> "hardware decoding (${state.hardwareDecoder})"
                else -> "software decoding"
            }
        PlayerOverlay(
            title = session.title,
            detail = "StremioNow core ${coreVersion ?: "unavailable"} · $decoding",
            state = state,
            isFullScreen = pair.isFullScreen,
            controls = controls,
            actions = actions,
        )
    }
}

private fun createOverlay(
    session: PlaybackSession,
    onExit: () -> Unit,
): ComposeDialog =
    ComposeDialog(session.video.frame, Dialog.ModalityType.MODELESS).apply {
        title = APP_NAME
        isUndecorated = true
        isTransparent = true
        // Compose's own edge resizer would resize the overlay alone and has no minimum size; the
        // overlay draws its own handles, which resize both windows in one step (WindowPair).
        isResizable = false
        bounds = session.video.frame.bounds
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        // Alt+F4 on the overlay, or "Close window" on the taskbar entry of the video window.
        val closeListener =
            object : WindowAdapter() {
                override fun windowClosing(event: WindowEvent) = onExit()
            }
        addWindowListener(closeListener)
        session.video.frame.addWindowListener(closeListener)
    }

/** Space, Left, Right, F and Esc, wherever the focus is in the overlay. */
private fun handleShortcut(
    event: KeyEvent,
    actions: PlayerActions,
    pair: WindowPair,
    controls: ControlsState,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    when (event.key) {
        Key.Tab -> {
            // Compose moves the focus; the controls only need to show where it is.
            controls.onKeyboardNavigation()
            return false
        }

        Key.Spacebar -> {
            actions.togglePause()
        }

        Key.DirectionLeft -> {
            actions.seekBy(-SEEK_STEP_SECONDS)
        }

        Key.DirectionRight -> {
            actions.seekBy(SEEK_STEP_SECONDS)
        }

        Key.F -> {
            actions.toggleFullScreen()
        }

        Key.Escape -> {
            if (pair.isFullScreen) pair.exitFullScreen() else return false
        }

        else -> {
            return false
        }
    }
    controls.poke()
    return true
}

private class Actions(
    private val player: Player,
    private val pair: WindowPair,
    private val controls: ControlsState,
    private val onExit: () -> Unit,
) : PlayerActions {
    override fun togglePause() = player.togglePause()

    override fun seekBy(seconds: Double) = player.seekBy(seconds)

    override fun seekTo(seconds: Double) = player.seekTo(seconds)

    override fun toggleFullScreen() = pair.toggleFullScreen()

    override fun minimize() = pair.minimize()

    override fun close() = onExit()

    override fun beginWindowGesture(edge: ResizeEdge?) = pair.beginGesture(edge)

    override fun updateWindowGesture() {
        pair.updateGesture()
        controls.poke()
    }

    override fun endWindowGesture(cancelled: Boolean) = pair.endGesture(cancelled)
}
