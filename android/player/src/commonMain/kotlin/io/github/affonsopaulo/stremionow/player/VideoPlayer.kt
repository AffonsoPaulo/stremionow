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
    /**
     * Detailed mpv logs. Keep it off in release builds: mpv logs the addresses it opens, and
     * debrid addresses carry the user's API key.
     */
    val verboseLogs: Boolean = false,
)

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

    /** Stops playback and frees native resources. The player cannot be used afterwards. */
    fun release()
}
