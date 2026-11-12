// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import io.github.affonsopaulo.stremionow.player.MpvPlayer
import io.github.affonsopaulo.stremionow.player.MpvUnavailableException
import io.github.affonsopaulo.stremionow.player.PlaybackState
import io.github.affonsopaulo.stremionow.player.PlayerError
import io.github.affonsopaulo.stremionow.player.VideoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.awt.Canvas
import java.awt.Image
import java.awt.Rectangle
import javax.swing.SwingUtilities

/**
 * The desktop player: libmpv drawing into its own native [video] window. Releasing it destroys
 * mpv first and closes the window after, since mpv draws into that window.
 *
 * When libmpv is missing, the window still opens, with a player that reports
 * [PlayerError.PlayerUnavailable], so the overlay can say so and offer a way back.
 */
internal class WindowedPlayer private constructor(
    val video: VideoWindow,
    /** The mpv player, or null when libmpv could not be loaded. */
    val mpv: MpvPlayer?,
) : VideoPlayer by (mpv ?: UnavailablePlayer) {
    override fun release() = release {}

    /** Releases mpv, then closes the window and runs [onReleased], both on the AWT event thread. */
    fun release(onReleased: () -> Unit) {
        video.hide()
        val closeWindow = {
            SwingUtilities.invokeLater {
                video.dispose()
                onReleased()
            }
        }
        if (mpv != null) mpv.release(closeWindow) else closeWindow()
    }

    companion object {
        private val log = System.getLogger("StremioNow.player")

        /** Shows a video window titled [title] at [bounds] and creates the player that draws into it. */
        fun open(
            title: String,
            bounds: Rectangle,
            icons: List<Image>,
            createPlayer: (canvas: Canvas) -> MpvPlayer,
        ): WindowedPlayer {
            val video = VideoWindow(title, icons)
            video.show(bounds)
            val mpv =
                try {
                    createPlayer(video.canvas)
                } catch (failure: MpvUnavailableException) {
                    log.log(System.Logger.Level.ERROR, "Video playback is unavailable: ${failure.message}", failure)
                    return WindowedPlayer(video, null)
                } catch (failure: Exception) {
                    // Any other failure to start mpv (a rejected option, the macOS video layer):
                    // the same message, rather than an exception that takes the app down.
                    log.log(System.Logger.Level.ERROR, "The player could not start", failure)
                    return WindowedPlayer(video, null)
                } catch (failure: Throwable) {
                    video.dispose()
                    throw failure
                }
            return WindowedPlayer(video, mpv)
        }
    }
}

/** Stands in for mpv when it cannot be loaded: reports why, and ignores every command. */
private object UnavailablePlayer : VideoPlayer {
    override val state: StateFlow<PlaybackState> =
        MutableStateFlow(PlaybackState(error = PlayerError.PlayerUnavailable))

    override fun load(
        url: String,
        startSeconds: Double,
    ) = Unit

    override fun addSubtitle(
        url: String,
        title: String?,
        language: String?,
        select: Boolean,
    ) = Unit

    override fun play() = Unit

    override fun pause() = Unit

    override fun togglePause() = Unit

    override fun seekBy(seconds: Double) = Unit

    override fun seekTo(seconds: Double) = Unit

    override fun selectAudio(trackId: Int?) = Unit

    override fun selectSubtitle(trackId: Int?) = Unit

    override fun release() = Unit
}
