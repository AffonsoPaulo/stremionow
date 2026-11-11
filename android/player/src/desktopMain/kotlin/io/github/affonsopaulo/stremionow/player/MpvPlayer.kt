// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

/**
 * A video player backed by libmpv that renders into a native window owned by the app.
 *
 * mpv creates its own child window inside the window given to [create] (an HWND on Windows, an
 * X11 window id on Linux) and draws there with its GPU output and hardware decoding; no frame
 * passes through the JVM. State changes arrive on mpv's event thread and are published through
 * [state]. Mirrors the Android `MpvPlayer`.
 */
class MpvPlayer private constructor(
    private val mpv: Mpv,
) : VideoPlayer {
    private val mutableState = MutableStateFlow(PlaybackState())

    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    @Volatile private var released = false

    @Volatile private var fileLoaded = false

    /** Whether the video format of the current file was logged; it is known after the first frame. */
    @Volatile private var videoInfoLogged = false

    /** External subtitles requested before the current file finished loading. */
    private val pendingSubtitles = mutableListOf<Array<String>>()

    init {
        mpv.onEvent(::onEvent)
        mpv.observeDouble("time-pos") { value -> mutableState.update { it.copy(positionSeconds = value ?: 0.0) } }
        mpv.observeDouble("duration") { value ->
            mutableState.update { it.copy(durationSeconds = value?.takeIf { d -> d > 0 }) }
        }
        mpv.observeDouble("demuxer-cache-time") { value ->
            mutableState.update {
                it.copy(
                    bufferedAheadSeconds = ((value ?: 0.0) - it.positionSeconds).coerceAtLeast(0.0),
                )
            }
        }
        mpv.observeFlag("pause") { value -> mutableState.update { it.copy(isPaused = value == true) } }
        mpv.observeFlag("paused-for-cache") { value -> mutableState.update { it.copy(isBuffering = value == true) } }
        mpv.observeFlag("eof-reached") { value -> mutableState.update { it.copy(isEnded = value == true) } }
        mpv.observeString("hwdec-current") { value ->
            val decoder = value?.takeUnless { it.isEmpty() || it == "no" }
            log.log(System.Logger.Level.INFO, "hwdec-current=${value ?: "unavailable"}")
            mutableState.update { it.copy(hardwareDecoder = decoder) }
        }
        // Node properties arrive as JSON when read as text.
        mpv.observeString("track-list") { json ->
            val tracks = json?.let { runCatching { parseTracks(JSONArray(it)) }.getOrNull() }.orEmpty()
            mutableState.update { it.copy(tracks = tracks) }
        }
        mpv.observeString("chapter-list") { json ->
            mutableState.update { it.copy(chapters = json?.let(Chapters::parse).orEmpty()) }
        }
        mpv.observeString("video-params/gamma") { value ->
            if (value != null && !videoInfoLogged) {
                videoInfoLogged = true
                logProperties("Video", VIDEO_INFO_PROPERTIES)
            }
        }
    }

    override fun load(
        url: String,
        startSeconds: Double,
    ) {
        if (released) return
        synchronized(pendingSubtitles) {
            fileLoaded = false
            pendingSubtitles.clear()
        }
        videoInfoLogged = false
        mutableState.value = PlaybackState()
        mpv.setProperty("start", if (startSeconds > 0) formatSeconds(startSeconds) else "none")
        mpv.command("loadfile", url, "replace")
    }

    override fun addSubtitle(
        url: String,
        title: String?,
        language: String?,
        select: Boolean,
    ) {
        if (released) return
        // "auto" adds the track without switching to it, leaving the choice to the user.
        val command = arrayOf("sub-add", url, if (select) "select" else "auto", title.orEmpty(), language.orEmpty())
        val applyNow =
            synchronized(pendingSubtitles) {
                if (!fileLoaded) pendingSubtitles += command
                fileLoaded
            }
        if (applyNow) mpv.command(*command)
    }

    override fun play() = setPaused(false)

    override fun pause() = setPaused(true)

    override fun togglePause() = setPaused(!state.value.isPaused)

    override fun seekBy(seconds: Double) {
        if (!released) mpv.command("seek", formatSeconds(seconds), "relative")
    }

    override fun seekTo(seconds: Double) {
        if (!released) mpv.command("seek", formatSeconds(seconds.coerceAtLeast(0.0)), "absolute")
    }

    override fun selectAudio(trackId: Int?) = selectTrack("aid", trackId)

    override fun selectSubtitle(trackId: Int?) = selectTrack("sid", trackId)

    override fun release() = release {}

    /**
     * Stops playback and frees mpv, including the child window it draws in, then runs
     * [onReleased] on mpv's release thread. Destroying waits for mpv's threads, which can be
     * stuck on a slow network read, so it never happens on the caller's thread. Keep the native
     * window mpv draws into until [onReleased] runs.
     */
    fun release(onReleased: () -> Unit) {
        if (released) return
        released = true
        thread(name = "mpv-release", isDaemon = true) {
            runCatching {
                if (fileLoaded) logProperties("Playback statistics", STATISTICS_PROPERTIES)
                mpv.command("stop")
                mpv.close()
            }.onFailure { log.log(System.Logger.Level.WARNING, "mpv did not shut down cleanly", it) }
            onReleased()
        }
    }

    private fun setPaused(paused: Boolean) {
        if (!released) mpv.setProperty("pause", if (paused) "yes" else "no")
    }

    private fun selectTrack(
        property: String,
        trackId: Int?,
    ) {
        if (!released) mpv.setProperty(property, trackId?.toString() ?: "no")
    }

    private fun onEvent(event: Mpv.Event) {
        when (event) {
            Mpv.Event.FileLoaded -> {
                val pending =
                    synchronized(pendingSubtitles) {
                        fileLoaded = true
                        pendingSubtitles.toList().also { pendingSubtitles.clear() }
                    }
                if (!released) pending.forEach { mpv.command(*it) }
                mutableState.update { it.copy(isLoaded = true, error = null) }
            }

            is Mpv.Event.EndFile -> {
                // An end without a successful load means the file could not be opened. mpv's
                // reason is technical; it goes to the log, not to the screen.
                if (event.failed || !fileLoaded) {
                    log.log(
                        System.Logger.Level.WARNING,
                        "Playback failed: ${event.error ?: "the file could not be opened"}",
                    )
                    if (!fileLoaded) mutableState.update { it.copy(error = PlayerError.LoadFailed) }
                }
            }

            Mpv.Event.StartFile, Mpv.Event.Shutdown -> {}
        }
    }

    private fun logProperties(
        title: String,
        names: List<String>,
    ) {
        val values = names.joinToString(", ") { name -> "$name=${mpv.getProperty(name) ?: "-"}" }
        log.log(System.Logger.Level.INFO, "$title: $values")
    }

    companion object {
        private val log = System.getLogger("StremioNow.player")

        /** Logged once per file, to report what mpv chose. */
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
        ): MpvPlayer {
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

                    if (options.audioLanguages.isNotEmpty()) put("alang", options.audioLanguages.joinToString(","))
                    if (options.subtitleLanguages.isNotEmpty()) {
                        put("slang", options.subtitleLanguages.joinToString(","))
                    } else {
                        put("sid", "no")
                    }
                }
            val mpv = Mpv.create(mpvOptions, logLevel = if (options.verboseLogs) "v" else "warn")
            return MpvPlayer(mpv)
        }

        private fun formatSeconds(seconds: Double): String = String.format(Locale.ROOT, "%.3f", seconds)
    }
}
