// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import io.github.affonsopaulo.stremionow.player.MpvPlayer
import io.github.affonsopaulo.stremionow.player.VideoPlayer
import java.awt.Image
import java.awt.Rectangle
import javax.swing.SwingUtilities

/**
 * The desktop player: libmpv drawing into its own native [video] window. Releasing it destroys
 * mpv first and closes the window after, since mpv draws into a child of that window.
 */
internal class WindowedPlayer private constructor(
    val video: VideoWindow,
    private val mpv: MpvPlayer,
) : VideoPlayer by mpv {
    override fun release() {
        video.hide()
        mpv.release { SwingUtilities.invokeLater(video::dispose) }
    }

    companion object {
        /** Shows a video window titled [title] at [bounds] and creates the player that draws into it. */
        fun open(
            title: String,
            bounds: Rectangle,
            icons: List<Image>,
            createPlayer: (windowHandle: Long) -> MpvPlayer,
        ): WindowedPlayer {
            val video = VideoWindow(title, icons)
            val handle = video.show(bounds)
            val mpv =
                try {
                    createPlayer(handle)
                } catch (failure: Throwable) {
                    video.dispose()
                    throw failure
                }
            return WindowedPlayer(video, mpv)
        }
    }
}
