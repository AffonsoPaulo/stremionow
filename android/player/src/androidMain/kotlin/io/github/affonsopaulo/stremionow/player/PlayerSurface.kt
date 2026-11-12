// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.player

import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Shows the video of [player]. The surface is attached while this composable is on screen.
 *
 * Once the video's frame rate is known, the surface tells the system (Android 11 and later), so
 * the display can switch to a matching refresh rate. Whether it does is the user's system setting
 * ("Match content frame rate" on Android TV); the app only asks for seamless switches and clears
 * the request when the player goes away.
 */
@Composable
fun PlayerSurface(
    player: MpvPlayer,
    modifier: Modifier = Modifier,
) {
    val state by player.state.collectAsState()
    val frameRate = remember { FrameRateHint() }
    val framesPerSecond = state.video?.framesPerSecond
    DisposableEffect(framesPerSecond) {
        frameRate.set(framesPerSecond)
        onDispose { frameRate.set(null) }
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            player.attachSurface(holder.surface)
                            frameRate.attach(holder.surface)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) = player.resizeSurface(width, height)

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            frameRate.attach(null)
                            player.detachSurface()
                        }
                    },
                )
            }
        },
    )
}

/** The content frame rate requested on the video surface, kept until the surface changes. */
private class FrameRateHint {
    private var surface: Surface? = null
    private var framesPerSecond: Double? = null

    fun attach(surface: Surface?) {
        this.surface = surface
        apply()
    }

    /** [framesPerSecond] comes from [VideoFormat], already checked; null clears the request. */
    fun set(framesPerSecond: Double?) {
        if (framesPerSecond == this.framesPerSecond) return
        this.framesPerSecond = framesPerSecond
        apply()
    }

    private fun apply() {
        val surface = surface?.takeIf { it.isValid } ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val rate = framesPerSecond?.toFloat() ?: 0f
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(
                    rate,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
                )
            } else {
                surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
        }
    }
}
