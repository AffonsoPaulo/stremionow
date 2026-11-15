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
    /**
     * How far the wait in [isBuffering] has got, from 0 to 1 (how much of the data mpv waits
     * for has arrived), or null where the player does not report it.
     */
    val bufferingProgress: Float? = null,
    /** A seek is in progress: until it completes, no new frame shows. */
    val isSeeking: Boolean = false,
    /** Volume, from 0 to 100. */
    val volume: Int = 100,
    val isMuted: Boolean = false,
    /** How much later subtitles show, in seconds; negative shows them earlier. */
    val subtitleDelaySeconds: Double = 0.0,
    /** Subtitle size, 1 being normal. */
    val subtitleScale: Double = 1.0,
    /** Subtitles' vertical place, from the top, in percent of the picture; 100 is the bottom. */
    val subtitlePosition: Int = 100,
    /** Playback rate, 1 being normal. */
    val speed: Double = 1.0,
    /** How the picture fills the screen. */
    val videoScale: VideoScale = VideoScale.Fit,
    /** Seconds already downloaded ahead of the current position. */
    val bufferedAheadSeconds: Double = 0.0,
    val isEnded: Boolean = false,
    val error: PlayerError? = null,
    val tracks: List<Track> = emptyList(),
    /** Hardware decoder in use, or null when decoding in software. */
    val hardwareDecoder: String? = null,
    /** Chapters of the current file, in order; empty when it has none. */
    val chapters: List<Chapter> = emptyList(),
    /** What the video is, once its first frame is decoded; null until then or without video. */
    val video: VideoFormat? = null,
)

/**
 * What the display should know about the video: whether it is HDR and its frame rate. Built from
 * mpv's properties with [from], which only accepts values it recognizes, so a stream cannot make
 * the app act on garbage.
 */
data class VideoFormat(
    /** The transfer function is an HDR one (PQ, as in HDR10 and Dolby Vision, or HLG). */
    val isHdr: Boolean,
    /** Frames per second, or null when unknown or outside [MIN_FPS]..[MAX_FPS]. */
    val framesPerSecond: Double?,
) {
    companion object {
        const val MIN_FPS = 10.0
        const val MAX_FPS = 240.0
        private val HDR_TRANSFERS = setOf("pq", "hlg")

        /** From mpv's `video-params/gamma` and `container-fps` (or `estimated-vf-fps`). */
        fun from(
            gamma: String?,
            framesPerSecond: Double?,
        ): VideoFormat =
            VideoFormat(
                isHdr = gamma?.trim()?.lowercase() in HDR_TRANSFERS,
                framesPerSecond = framesPerSecond?.takeIf { it.isFinite() && it in MIN_FPS..MAX_FPS },
            )
    }
}

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

/** A fresh state for the next video, keeping what the viewer set for the player itself. */
fun PlaybackState.forNextVideo(): PlaybackState =
    PlaybackState(
        volume = volume,
        isMuted = isMuted,
        subtitleDelaySeconds = subtitleDelaySeconds,
        subtitleScale = subtitleScale,
        subtitlePosition = subtitlePosition,
        speed = speed,
        videoScale = videoScale,
    )
