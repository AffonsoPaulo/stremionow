// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.mobile.debug

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import io.github.affonsopaulo.stremionow.player.MpvPlayer
import io.github.affonsopaulo.stremionow.player.PlayerOptions
import io.github.affonsopaulo.stremionow.player.PlayerSurface
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug-only screen for the player proof of concept. It plays the URL passed in the `url`
 * extra, optionally adds a bundled ASS subtitle (`subtitle` extra set to `true`) and logs the
 * playback state once per second under the `PlayerProbe` tag:
 *
 * ```
 * adb shell am start -n io.github.affonsopaulo.stremionow/.mobile.debug.PlayerProbeActivity \
 *   -e url https://example.com/video.mp4 --ez subtitle true
 * ```
 */
class PlayerProbeActivity : ComponentActivity() {
    private lateinit var player: MpvPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Exported so adb can start it, so it only plays web addresses: any other app could
        // otherwise point mpv at this app's private files.
        val url = intent.getStringExtra("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        if (url.isNullOrBlank()) {
            finish()
            return
        }

        player = MpvPlayer.create(this, PlayerOptions(subtitleLanguages = listOf("por", "eng")))
        player.load(url)
        if (intent.getBooleanExtra("subtitle", false)) {
            player.addSubtitle(bundledSubtitle().absolutePath, title = "Probe", language = "eng")
        }

        lifecycleScope.launch {
            while (true) {
                delay(1_000)
                Log.i(TAG, player.state.value.toString())
            }
        }

        setContent { ProbeScreen(player) }
    }

    override fun onDestroy() {
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    private fun bundledSubtitle(): File {
        val file = File(cacheDir, "probe.ass")
        assets.open("probe.ass").use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private companion object {
        const val TAG = "PlayerProbe"
    }
}

@Composable
private fun ProbeScreen(player: MpvPlayer) {
    val state by player.state.collectAsState()
    LaunchedEffect(Unit) { player.play() }
    Box(Modifier.fillMaxSize().background(SnColors.bg)) {
        PlayerSurface(player, Modifier.fillMaxSize())
        BasicText(
            text =
                "%.1f / %s s · %s · buffer %.0f s".format(
                    state.positionSeconds,
                    state.durationSeconds?.let { "%.1f".format(it) } ?: "?",
                    state.hardwareDecoder ?: "software",
                    state.bufferedAheadSeconds,
                ),
            modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(SnSpacing.space4),
            style = SnTypography.caption.copy(color = SnColors.fg),
        )
    }
}
