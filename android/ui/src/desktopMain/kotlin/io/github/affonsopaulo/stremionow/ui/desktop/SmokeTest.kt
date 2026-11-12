// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import com.sun.jna.Platform
import io.github.affonsopaulo.stremionow.player.MpvPlayer
import io.github.affonsopaulo.stremionow.player.PlayerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Rectangle
import kotlin.coroutines.resume

/**
 * A start-up check for CI and packaging: once the app's window has composed, opens the player's
 * video window, initializes libmpv in it with the app's real options, plays a short generated
 * test pattern and closes it again. No network access is needed.
 *
 * It passes when the pattern loaded, its playback position moved on and mpv's video output
 * opened in the app's window (and, on macOS, drew frames through the render API). Every value
 * it read is printed, so a failure on a CI runner shows what was missing.
 */
object SmokeTest {
    /** mpv's built-in lavfi source: a moving test pattern, no audio. */
    private const val TEST_SOURCE = "av://lavfi:testsrc2=size=640x360:rate=30:duration=20"
    private const val LOAD_TIMEOUT_MS = 30_000L
    private const val PLAY_SECONDS = 1.0
    private const val SHOW_TIMEOUT_MS = 15_000L
    private const val POLL_MS = 50L

    private val PROPERTIES = listOf("mpv-version", "current-vo", "vo-configured", "hwdec-current", "width", "height")

    /** Runs the check on the AWT event thread and returns whether it passed. */
    suspend fun run(host: DesktopHost): Boolean {
        // The window shows once its first composition is done.
        val showing = withTimeoutOrNull(SHOW_TIMEOUT_MS) { while (!host.mainWindow.isShowing) delay(POLL_MS) } != null
        report("main window showing: $showing, ${host.mainWindow.width}x${host.mainWindow.height}")
        if (!showing) return false.also { report("FAILED: the main window did not show") }

        val player =
            WindowedPlayer.open("StremioNow smoke test", Rectangle(100, 100, 640, 360), host.icons) { canvas ->
                MpvPlayer.create(
                    canvas,
                    host.nativeLibraries,
                    host.caBundle,
                    PlayerOptions(verboseLogs = host.verboseLogs),
                )
            }
        val mpv = player.mpv
        try {
            if (mpv == null) {
                report("FAILED: libmpv could not be loaded (see the log above)")
                return false
            }
            player.load(TEST_SOURCE)
            val playing =
                withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                    player.state
                        .first {
                            it.error != null || (it.isLoaded && it.positionSeconds >= PLAY_SECONDS)
                        }.error ==
                        null
                } ?: false
            val state = player.state.value
            report("state: loaded=${state.isLoaded}, position=${state.positionSeconds}, error=${state.error}")
            for (name in PROPERTIES) report("$name: ${mpv.readProperty(name) ?: "-"}")
            if (Platform.isMac()) report("frames drawn through the render API: ${mpv.framesDrawn}")

            val failures =
                buildList {
                    if (!playing) add("the test pattern did not play")
                    if (mpv.readProperty("vo-configured") != "yes") add("mpv's video output did not open")
                    if (Platform.isMac() && mpv.framesDrawn == 0L) add("no frame was drawn")
                }
            failures.forEach { report("FAILED: $it") }
            return failures.isEmpty()
        } finally {
            suspendCancellableCoroutine { continuation -> player.release { continuation.resume(Unit) } }
            report("player released")
        }
    }

    private fun report(message: String) = println("[smoke-test] $message")
}
