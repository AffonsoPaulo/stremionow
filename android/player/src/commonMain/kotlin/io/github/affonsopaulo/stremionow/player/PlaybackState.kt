// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.player

/** Snapshot of the player, updated as mpv reports changes. */
data class PlaybackState(
    /** A file is loaded and playback has started at least once. */
    val isLoaded: Boolean = false,
    val positionSeconds: Double = 0.0,
    /** Total length, or null while unknown (for example on a live stream). */
    val durationSeconds: Double? = null,
    val isPaused: Boolean = false,
    /** Playback is waiting for data. */
    val isBuffering: Boolean = false,
    /** Seconds already downloaded ahead of the current position. */
    val bufferedAheadSeconds: Double = 0.0,
    val isEnded: Boolean = false,
    val error: PlayerError? = null,
    val tracks: List<Track> = emptyList(),
    /** Hardware decoder in use, or null when decoding in software. */
    val hardwareDecoder: String? = null,
    /** Chapters of the current file, in order; empty when it has none. */
    val chapters: List<Chapter> = emptyList(),
)

/** An audio, subtitle or video track of the current file. */
data class Track(
    val id: Int,
    val type: Type,
    val title: String?,
    val language: String?,
    val codec: String?,
    val isSelected: Boolean,
    val isExternal: Boolean,
) {
    enum class Type { Video, Audio, Subtitle }
}

/** Why playback stopped unexpectedly. */
enum class PlayerError {
    /** The file could not be opened or decoded. */
    LoadFailed,

    /** The player itself could not start, for example because libmpv is missing (desktop). */
    PlayerUnavailable,
}
