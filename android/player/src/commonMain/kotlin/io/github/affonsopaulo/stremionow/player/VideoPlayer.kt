// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.player

import kotlinx.coroutines.flow.StateFlow

/** Playback preferences applied when the player is created. */
data class PlayerOptions(
    /** Preferred audio languages, most wanted first, as ISO 639 codes. */
    val audioLanguages: List<String> = emptyList(),
    /** Preferred subtitle languages, most wanted first; empty turns subtitles off by default. */
    val subtitleLanguages: List<String> = emptyList(),
    /** Memory budget for buffered data ahead of the playback position, in MiB (Android). */
    val bufferAheadMiB: Int = 64,
    /** How much of the stream to keep in memory on the desktop; null picks it from the computer's memory. */
    val streamMemory: StreamMemory? = null,
    /** Exact sizes of the stream memory on the desktop (the user's "Custom"); they win over [streamMemory]. */
    val streamMemorySizes: StreamMemorySizes? = null,
    /** How subtitles look, from Settings; the player's own buttons change size and place afterwards. */
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    /**
     * Detailed mpv logs. Keep it off in release builds: mpv logs the addresses it opens, and
     * debrid addresses carry the user's API key.
     */
    val verboseLogs: Boolean = false,
)

/** How the picture fills a screen of another shape. */
enum class VideoScale {
    /** The whole picture, with bars where the shapes differ (the default). */
    Fit,

    /** Fills the screen, cropping the picture's edges. */
    Fill,

    /** Fills the screen, distorting the picture. */
    Stretch,
}

/**
 * What the shared player screen needs from a video player. Each platform implements it with
 * libmpv: `MpvPlayer` draws into an Android surface on Android and into a native window on the
 * desktop.
 *
 * Methods are safe to call from the main thread and do nothing once the player is released.
 */
interface VideoPlayer {
    /** Current playback state, updated as mpv reports changes. */
    val state: StateFlow<PlaybackState>

    /** Starts playing [url], optionally from [startSeconds]. Replaces whatever is playing. */
    fun load(
        url: String,
        startSeconds: Double = 0.0,
    )

    /**
     * Adds an external subtitle file or URL, selecting it unless [select] is false. Subtitles
     * added before the file has loaded are applied once it has.
     */
    fun addSubtitle(
        url: String,
        title: String? = null,
        language: String? = null,
        select: Boolean = true,
    )

    fun play()

    fun pause()

    fun togglePause()

    /** Moves the playback position by [seconds], backwards when negative. */
    fun seekBy(seconds: Double)

    fun seekTo(seconds: Double)

    /** Selects an audio track, or turns audio off with null. */
    fun selectAudio(trackId: Int?)

    /** Selects a subtitle track, or turns subtitles off with null. */
    fun selectSubtitle(trackId: Int?)

    /** Sets the volume, from 0 to 100. Players that cannot ignore it. */
    fun setVolume(percent: Int) = Unit

    /** Mutes or unmutes the sound, keeping the volume. Players that cannot ignore it. */
    fun setMuted(muted: Boolean) = Unit

    /** Shifts subtitles in time; positive values show them later. Players that cannot ignore it. */
    fun setSubtitleDelay(seconds: Double) = Unit

    /** Scales subtitles, 1 being their normal size. Players that cannot ignore it. */
    fun setSubtitleScale(scale: Double) = Unit

    /**
     * Places subtitles vertically, as a percentage of the picture's height from its top: 100 is
     * the bottom, where they normally are; lower values raise them. Players that cannot ignore it.
     */
    fun setSubtitlePosition(percent: Int) = Unit

    /** Plays at [speed] times the normal rate. Players that cannot ignore it. */
    fun setSpeed(speed: Double) = Unit

    /** How the picture fills the screen. Players that cannot ignore it. */
    fun setVideoScale(scale: VideoScale) = Unit

    /** Stops playback and frees native resources. The player cannot be used afterwards. */
    fun release()
}
