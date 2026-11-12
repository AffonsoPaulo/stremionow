// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.player

import android.content.Context
import android.view.Surface
import dev.jdtech.mpv.MPVLib
import dev.jdtech.mpv.MPVLib.MpvEvent
import dev.jdtech.mpv.MPVLib.MpvFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import java.util.Locale
import kotlin.concurrent.thread

/**
 * A video player backed by libmpv.
 *
 * Create one per playback screen and call [release] when the screen goes away. All methods
 * are safe to call from the main thread; state changes arrive on mpv's event thread and are
 * published through [state].
 */
class MpvPlayer private constructor(
    private val mpv: MPVLib,
) : VideoPlayer,
    MPVLib.EventObserver {
    private val mutableState = MutableStateFlow(PlaybackState())

    /** Current playback state. */
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    @Volatile private var released = false

    @Volatile private var fileLoaded = false

    /** Frame rate the container declares for the current file, when it does. */
    @Volatile private var containerFps: Double? = null

    /** External subtitles requested before the current file finished loading. */
    private val pendingSubtitles = mutableListOf<Array<String>>()

    /** Starts playing [url], optionally from [startSeconds]. Replaces whatever is playing. */
    override fun load(
        url: String,
        startSeconds: Double,
    ) {
        if (released) return
        synchronized(pendingSubtitles) {
            fileLoaded = false
            pendingSubtitles.clear()
        }
        mutableState.value = PlaybackState()
        containerFps = null
        mpv.setPropertyString("start", if (startSeconds > 0) formatSeconds(startSeconds) else "none")
        mpv.command(arrayOf("loadfile", url, "replace"))
    }

    /**
     * Adds an external subtitle file or URL, selecting it unless [select] is false. Subtitles added before the file has
     * loaded are applied once it has, since mpv attaches them to the current file.
     */
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
        if (applyNow) mpv.command(command)
    }

    override fun play() = setPaused(false)

    override fun pause() = setPaused(true)

    override fun togglePause() = setPaused(!state.value.isPaused)

    /** Moves the playback position by [seconds], backwards when negative. */
    override fun seekBy(seconds: Double) {
        if (!released) mpv.command(arrayOf("seek", formatSeconds(seconds), "relative+exact"))
    }

    override fun seekTo(seconds: Double) {
        if (!released) mpv.command(arrayOf("seek", formatSeconds(seconds.coerceAtLeast(0.0)), "absolute"))
    }

    /** Selects an audio track, or turns audio off with null. */
    override fun selectAudio(trackId: Int?) = selectTrack("aid", trackId)

    /** Selects a subtitle track, or turns subtitles off with null. */
    override fun selectSubtitle(trackId: Int?) = selectTrack("sid", trackId)

    /** Shifts subtitles in time; positive values show them later. */
    fun setSubtitleDelay(seconds: Double) {
        if (!released) mpv.setPropertyDouble("sub-delay", seconds)
    }

    /** Scales subtitle text, where 1.0 is the default size. */
    fun setSubtitleScale(scale: Double) {
        if (!released) mpv.setPropertyDouble("sub-scale", scale.coerceIn(0.5, 3.0))
    }

    internal fun attachSurface(surface: Surface) {
        if (released) return
        mpv.attachSurface(surface)
        mpv.setOptionString("force-window", "yes")
        mpv.setPropertyString("vo", VIDEO_OUTPUT)
    }

    internal fun resizeSurface(
        width: Int,
        height: Int,
    ) {
        if (!released) mpv.setPropertyString("android-surface-size", "${width}x$height")
    }

    internal fun detachSurface() {
        if (released) return
        mpv.setPropertyString("vo", "null")
        mpv.setOptionString("force-window", "no")
        mpv.detachSurface()
    }

    /** Stops playback and frees native resources. The player cannot be used afterwards. */
    override fun release() {
        if (released) return
        released = true
        mpv.removeObserver(this)
        // Destroying waits for mpv's threads, which can be stuck on a slow network read (a
        // torrent still finding pieces, say). Doing it on the main thread froze the app long
        // enough for Android to kill it, so it happens on its own thread.
        thread(name = "mpv-release", isDaemon = true) {
            runCatching {
                mpv.command(arrayOf("stop"))
                mpv.destroy()
            }
        }
    }

    private fun setPaused(paused: Boolean) {
        if (!released) mpv.setPropertyBoolean("pause", paused)
    }

    private fun selectTrack(
        property: String,
        trackId: Int?,
    ) {
        if (!released) mpv.setPropertyString(property, trackId?.toString() ?: "no")
    }

    override fun eventProperty(property: String) {
        when (property) {
            "track-list" -> refreshTracks()
            "chapter-list" -> refreshChapters()
        }
    }

    override fun eventProperty(
        property: String,
        value: Long,
    ) = Unit

    override fun eventProperty(
        property: String,
        value: Double,
    ) {
        when (property) {
            "time-pos" -> {
                mutableState.update { it.copy(positionSeconds = value) }
            }

            "duration" -> {
                mutableState.update { it.copy(durationSeconds = value.takeIf { d -> d > 0 }) }
            }

            "demuxer-cache-time" -> {
                mutableState.update { it.copy(bufferedAheadSeconds = (value - it.positionSeconds).coerceAtLeast(0.0)) }
            }

            "container-fps" -> {
                containerFps = value
            }
        }
    }

    override fun eventProperty(
        property: String,
        value: Boolean,
    ) {
        when (property) {
            "pause" -> mutableState.update { it.copy(isPaused = value) }
            "paused-for-cache" -> mutableState.update { it.copy(isBuffering = value) }
            "eof-reached" -> mutableState.update { it.copy(isEnded = value) }
        }
    }

    override fun eventProperty(
        property: String,
        value: String,
    ) {
        when (property) {
            "hwdec-current" -> {
                mutableState.update { it.copy(hardwareDecoder = value.takeUnless { v -> v.isEmpty() || v == "no" }) }
            }

            // Known once the first frame is decoded; the frame rate lets the display match it.
            "video-params/gamma" -> {
                if (mutableState.value.video == null) {
                    mutableState.update { it.copy(video = VideoFormat.from(value, containerFps)) }
                }
            }
        }
    }

    override fun event(eventId: Int) {
        when (eventId) {
            MpvEvent.MPV_EVENT_FILE_LOADED -> {
                val pending =
                    synchronized(pendingSubtitles) {
                        fileLoaded = true
                        pendingSubtitles.toList().also { pendingSubtitles.clear() }
                    }
                if (!released) pending.forEach(mpv::command)
                mutableState.update { it.copy(isLoaded = true, error = null) }
            }

            MpvEvent.MPV_EVENT_END_FILE -> {
                // An end without a successful load means the file could not be opened.
                if (!fileLoaded) mutableState.update { it.copy(error = PlayerError.LoadFailed) }
            }
        }
    }

    private fun refreshChapters() {
        if (released) return
        val json = mpv.getPropertyString("chapter-list") ?: return
        mutableState.update { it.copy(chapters = Chapters.parse(json)) }
    }

    private fun refreshTracks() {
        if (released) return
        val json = mpv.getPropertyString("track-list") ?: return
        val tracks =
            runCatching { parseTracks(JSONArray(json)) }
                .getOrDefault(emptyList())
        mutableState.update { it.copy(tracks = tracks) }
    }

    companion object {
        private const val VIDEO_OUTPUT = "gpu"

        /** Creates a player with the given [options]. */
        fun create(
            context: Context,
            options: PlayerOptions = PlayerOptions(),
        ): MpvPlayer {
            val appContext = context.applicationContext
            val caBundle = CaBundle.install(appContext)
            val mpv = checkNotNull(MPVLib.create(appContext)) { "libmpv could not be initialized" }

            with(mpv) {
                // Ignore any user config files and default key bindings: the app owns the UI.
                setOptionString("config", "no")
                setOptionString("msg-level", if (options.verboseLogs) "all=v" else "all=warn")
                setOptionString("input-default-bindings", "no")
                setOptionString("idle", "yes")
                setOptionString("keep-open", "yes")

                setOptionString("vo", VIDEO_OUTPUT)
                setOptionString("gpu-context", "android")
                setOptionString("opengl-es", "yes")
                setOptionString("hwdec", "mediacodec-copy")
                setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
                setOptionString("ao", "audiotrack,opensles")

                setOptionString("tls-verify", "yes")
                setOptionString("tls-ca-file", caBundle.absolutePath)

                setOptionString("cache", "yes")
                setOptionString("demuxer-max-bytes", "${options.bufferAheadMiB}MiB")
                setOptionString("demuxer-max-back-bytes", "${(options.bufferAheadMiB / 4).coerceAtLeast(8)}MiB")

                if (options.audioLanguages.isNotEmpty()) {
                    setOptionString("alang", options.audioLanguages.joinToString(","))
                }
                if (options.subtitleLanguages.isNotEmpty()) {
                    setOptionString("slang", options.subtitleLanguages.joinToString(","))
                } else {
                    setOptionString("sid", "no")
                }

                init()
                setOptionString("force-window", "no")
            }

            val player = MpvPlayer(mpv)
            mpv.addObserver(player)
            mpv.observeProperty("time-pos", MpvFormat.MPV_FORMAT_DOUBLE)
            mpv.observeProperty("duration", MpvFormat.MPV_FORMAT_DOUBLE)
            mpv.observeProperty("demuxer-cache-time", MpvFormat.MPV_FORMAT_DOUBLE)
            mpv.observeProperty("pause", MpvFormat.MPV_FORMAT_FLAG)
            mpv.observeProperty("paused-for-cache", MpvFormat.MPV_FORMAT_FLAG)
            mpv.observeProperty("eof-reached", MpvFormat.MPV_FORMAT_FLAG)
            mpv.observeProperty("hwdec-current", MpvFormat.MPV_FORMAT_STRING)
            mpv.observeProperty("track-list", MpvFormat.MPV_FORMAT_NONE)
            mpv.observeProperty("chapter-list", MpvFormat.MPV_FORMAT_NONE)
            mpv.observeProperty("container-fps", MpvFormat.MPV_FORMAT_DOUBLE)
            mpv.observeProperty("video-params/gamma", MpvFormat.MPV_FORMAT_STRING)
            return player
        }

        private fun formatSeconds(seconds: Double): String = String.format(Locale.ROOT, "%.3f", seconds)
    }
}
