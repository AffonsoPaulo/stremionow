// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.desktop.mpv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.Locale

/** What the controls show about the current file. */
data class PlaybackState(
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double? = null,
    val isPaused: Boolean = false,
    val isBuffering: Boolean = false,
    val isEnded: Boolean = false,
    val isLoaded: Boolean = false,
    /** Hardware decoder in use (for example `d3d11va`), or null while decoding in software. */
    val hardwareDecoder: String? = null,
    /** mpv's reason when the file could not be played, or null. Technical: meant for logs. */
    val error: String? = null,
)

/** Playback preferences applied when the player is created. Mirrors the Android `PlayerOptions`. */
data class PlayerOptions(
    /** Memory budget for buffered data ahead of the playback position, in MiB. */
    val bufferAheadMiB: Int = 64,
    /**
     * Detailed mpv logs. Keep it off by default: mpv logs the addresses it opens, and debrid
     * addresses carry the user's API key.
     */
    val verboseLogs: Boolean = false,
)

/**
 * A video player that renders into a native window owned by the app.
 *
 * mpv creates its own child window inside [windowHandle] (an HWND on Windows, an X11 window id on
 * Linux) and draws there with its GPU output and hardware decoding; no frame passes through the
 * JVM. State changes arrive on mpv's event thread and are published through [state].
 */
class Player private constructor(
    private val mpv: Mpv,
) : AutoCloseable {
    private val mutableState = MutableStateFlow(PlaybackState())

    /** Current playback state. */
    val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    @Volatile private var fileLoaded = false

    /** Whether the video format of the current file was logged; it is known after the first frame. */
    @Volatile private var videoInfoLogged = false

    init {
        mpv.onEvent(::onEvent)
        mpv.observeDouble("time-pos") { value -> mutableState.update { it.copy(positionSeconds = value ?: 0.0) } }
        mpv.observeDouble("duration") { value ->
            mutableState.update { it.copy(durationSeconds = value?.takeIf { d -> d > 0 }) }
        }
        mpv.observeFlag("pause") { value -> mutableState.update { it.copy(isPaused = value == true) } }
        mpv.observeFlag("paused-for-cache") { value -> mutableState.update { it.copy(isBuffering = value == true) } }
        mpv.observeFlag("eof-reached") { value -> mutableState.update { it.copy(isEnded = value == true) } }
        mpv.observeString("video-params/gamma") { value ->
            if (value != null && !videoInfoLogged) {
                videoInfoLogged = true
                logVideoInfo()
            }
        }
        mpv.observeString("hwdec-current") { value ->
            val decoder = value?.takeUnless { it.isEmpty() || it == "no" }
            log.log(System.Logger.Level.INFO, "hwdec-current=${value ?: "unavailable"}")
            mutableState.update { it.copy(hardwareDecoder = decoder) }
        }
    }

    /** Starts playing [url], replacing whatever is playing. */
    fun load(url: String) {
        fileLoaded = false
        videoInfoLogged = false
        mutableState.value = PlaybackState()
        mpv.command("loadfile", url, "replace")
    }

    fun togglePause() {
        mpv.setProperty("pause", if (state.value.isPaused) "no" else "yes")
    }

    /** Moves the playback position by [seconds], backwards when negative. */
    fun seekBy(seconds: Double) {
        mpv.command("seek", formatSeconds(seconds), "relative")
    }

    fun seekTo(seconds: Double) {
        mpv.command("seek", formatSeconds(seconds.coerceAtLeast(0.0)), "absolute")
    }

    /** Stops playback and frees mpv, including its window. The player cannot be used afterwards. */
    override fun close() {
        if (fileLoaded) logStatistics()
        mpv.close()
    }

    private fun onEvent(event: Mpv.Event) {
        when (event) {
            Mpv.Event.FileLoaded -> {
                fileLoaded = true
                mutableState.update { it.copy(isLoaded = true, error = null) }
            }

            is Mpv.Event.EndFile -> {
                if (fileLoaded) logStatistics()
                // An end without a successful load means the file could not be opened.
                if (event.failed || !fileLoaded) {
                    val reason = event.error ?: "the file could not be opened"
                    log.log(System.Logger.Level.WARNING, "Playback failed: $reason")
                    mutableState.update { it.copy(error = reason) }
                }
            }

            Mpv.Event.StartFile, Mpv.Event.Shutdown -> {
                Unit
            }
        }
    }

    private fun logVideoInfo() = logProperties("Video", VIDEO_INFO_PROPERTIES)

    private fun logStatistics() = logProperties("Playback statistics", STATISTICS_PROPERTIES)

    private fun logProperties(
        title: String,
        names: List<String>,
    ) {
        val values = names.joinToString(", ") { name -> "$name=${mpv.getProperty(name) ?: "-"}" }
        log.log(System.Logger.Level.INFO, "$title: $values")
    }

    companion object {
        private val log = System.getLogger("StremioNow.player")

        /** Logged once per file, so the proof of concept can report what mpv chose. */
        private val VIDEO_INFO_PROPERTIES =
            listOf(
                "current-vo",
                "video-codec",
                "width",
                "height",
                "video-params/pixelformat",
                "video-params/gamma",
                "video-params/primaries",
                "hwdec",
            )

        /** Logged when a file ends or the player closes, to judge playback smoothness. */
        private val STATISTICS_PROPERTIES =
            listOf(
                "hwdec-current",
                "time-pos",
                "frame-drop-count",
                "decoder-frame-drop-count",
                "vo-delayed-frame-count",
                "display-fps",
                "estimated-vf-fps",
            )

        /**
         * Creates a player that draws into the native window [windowHandle]. [caFile] is the CA
         * store mpv uses to verify HTTPS servers.
         */
        fun create(
            windowHandle: Long,
            caFile: File?,
            options: PlayerOptions = PlayerOptions(),
        ): Player {
            val mpvOptions =
                buildMap {
                    // Ignore user config files, scripts and key bindings: the app owns the UI and input.
                    put("config", "no")
                    put("load-scripts", "no")
                    put("osc", "no")
                    put("ytdl", "no")
                    put("msg-level", if (options.verboseLogs) "all=v" else "all=warn")
                    put("input-default-bindings", "no")
                    put("input-vo-keyboard", "no")
                    put("input-cursor", "no")
                    put("cursor-autohide", "no")
                    put("idle", "yes")
                    put("keep-open", "yes")

                    // Render into the app's window, on the GPU, decoding in hardware when it is safe.
                    put("wid", windowHandle.toString())
                    put("force-window", "yes")
                    put("vo", "gpu-next")
                    put("hwdec", "auto-safe")

                    put("tls-verify", "yes")
                    if (caFile != null) put("tls-ca-file", caFile.absolutePath)

                    put("cache", "yes")
                    put("demuxer-max-bytes", "${options.bufferAheadMiB}MiB")
                    put("demuxer-max-back-bytes", "${(options.bufferAheadMiB / 4).coerceAtLeast(8)}MiB")
                    put("sid", "no")
                }
            val mpv = Mpv.create(mpvOptions, logLevel = if (options.verboseLogs) "v" else "warn")
            return Player(mpv)
        }

        private fun formatSeconds(seconds: Double): String = String.format(Locale.ROOT, "%.3f", seconds)
    }
}
