// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.affonsopaulo.stremionow.core.NextVideo
import io.github.affonsopaulo.stremionow.core.StreamSource
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.core.SubtitleTrack
import io.github.affonsopaulo.stremionow.core.TorrentProgress
import io.github.affonsopaulo.stremionow.core.WatchProgress
import io.github.affonsopaulo.stremionow.player.Markers
import io.github.affonsopaulo.stremionow.player.PlayerOptions
import io.github.affonsopaulo.stremionow.player.Track
import io.github.affonsopaulo.stremionow.player.VideoPlayer
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.PlaybackSession
import io.github.affonsopaulo.stremionow.ui.data.io
import io.github.affonsopaulo.stremionow.ui.platform.logWarning
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Locale

/** How often the torrent state is read for the loading screen. */
private const val TORRENT_STATUS_INTERVAL_MS = 1_000L

/** How often progress is written while playing. */
private const val PROGRESS_INTERVAL_MS = 10_000L

/** External subtitles kept per language, so the list stays usable. */
private const val SUBTITLES_PER_LANGUAGE = 3

/**
 * Plays one source of [selection]: asks the core for the address to play, picks subtitles in
 * the viewer's languages and saves progress while playing and when the screen goes away. The
 * platform's player comes from [createPlayer], given the playback preferences.
 */
class PlayerViewModel<out P : VideoPlayer>(
    private val core: StremioCore,
    val selection: PlaybackSession.Selection,
    private val source: StreamSource,
    createPlayer: (PlayerOptions) -> P,
) : ViewModel() {
    /** The user's choices from setup and Settings, falling back to the device language. */
    private val settings = runCatching { core.settings() }.getOrNull()
    private val audioLanguage = settings?.audioLanguage?.ifEmpty { null } ?: Locale.getDefault().language

    /** Empty when the user turned subtitles off by default. */
    private val subtitleChoice =
        settings?.subtitleLanguage ?: Locale
            .getDefault()
            .language
            .takeIf { it != "en" }
            .orEmpty()
    private val subtitleLanguage =
        subtitleChoice.ifEmpty { null }?.let {
            Locale
                .forLanguageTag(
                    it,
                ).isO3LanguageOrNull()
        }

    val player: P =
        createPlayer(
            PlayerOptions(
                audioLanguages = listOf(audioLanguage),
                // Subtitles start on only when the device language is not English.
                subtitleLanguages = listOfNotNull(subtitleChoice.ifEmpty { null }),
                // "Memory for the video"; neither set means the player decides (see VideoMemory.kt).
                streamMemory = settings.streamMemoryOptions().first,
                streamMemorySizes = settings.streamMemoryOptions().second,
            ),
        )

    private val mutablePreparing = MutableStateFlow(true)

    /** True until the player has an address: torrents first need peers and the file list. */
    val preparing = mutablePreparing.asStateFlow()
    private val mutableTorrent = MutableStateFlow<TorrentProgress?>(null)

    /** Live torrent state for the loading screen; null for web sources. */
    val torrent = mutableTorrent.asStateFlow()
    private val mutablePrepareFailed = MutableStateFlow(false)

    /** The source could not be prepared (no peers, no video file, engine error). */
    val prepareFailed = mutablePrepareFailed.asStateFlow()
    private val mutableSkips = MutableStateFlow(Markers())

    /**
     * Openings, recaps and endings from AniSkip, for anime episodes when the setting is on.
     * Empty until the answer arrives, and for everything else; the file's chapters come first.
     */
    val skips = mutableSkips.asStateFlow()
    private val mutableWatched = MutableStateFlow<Set<String>>(emptySet())

    /** The title's videos marked watched, for the episodes strip; empty until read. */
    val watched = mutableWatched.asStateFlow()

    init {
        viewModelScope.launch {
            mutableWatched.value =
                runCatching { io { core.watchedVideos(selection.details.item.id) } }.getOrDefault(emptyList()).toSet()
        }
        source.infoHash?.let { hash ->
            viewModelScope.launch {
                while (isActive) {
                    mutableTorrent.value = core.torrentStatus(hash)
                    delay(TORRENT_STATUS_INTERVAL_MS)
                }
            }
        }
        viewModelScope.launch {
            // Every source plays through the local server, so the player never sees debrid keys.
            val url =
                runCatching { core.playbackUrl(source) }
                    .onFailure { logWarning("Could not prepare the source", it) }
                    .getOrNull()
            if (url == null) {
                mutablePreparing.value = false
                mutablePrepareFailed.value = true
                return@launch
            }
            mutablePreparing.value = false
            val saved = runCatching { io { core.progress(selection.details.item.id) } }.getOrNull()
            val start =
                saved
                    ?.takeIf { it.videoId == selection.videoId }
                    ?.positionMs
                    ?.toDouble()
                    ?.div(1000) ?: 0.0
            player.load(url, start)
            addSubtitles()
            selectSubtitleIfMissing()
        }
        if (settings?.aniskip != false) viewModelScope.launch { findSkips() }
        viewModelScope.launch { findNextSource() }
        viewModelScope.launch { rememberSource() }
        viewModelScope.launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                if (!player.state.value.isPaused) saveProgress()
            }
        }
    }

    /**
     * Finds the next episode's source while this one plays, as Stremio does: asks the addon
     * of the source playing for the next episode's sources and keeps the first of the same
     * binge group, so moving on needs no choice ([PlaybackSession.nextUp]). Without a group, or
     * a match, the next episode's sources show instead.
     */
    private suspend fun findNextSource() {
        val next = selection.nextEpisode ?: return
        val group = source.bingeGroup ?: return
        val match = core.sourceFinder().find(selection.kind, next.id, SourceWanted(source.addonId, bingeGroup = group))
        // Only while this episode is still the one chosen: the session may have moved on.
        if (match != null && PlaybackSession.selection?.videoId == selection.videoId) {
            PlaybackSession.nextUp = PlaybackSession.NextUp(next.id, match)
        }
    }

    /**
     * Once the video starts, remembers the source however it was picked: it is offered first
     * among this video's sources next time, and "Resume" and Continue watching play it again
     * (ResumePlayback.kt). A source that never started is not worth resuming with. Only its
     * identity, binge group and addon are kept, never its address.
     */
    private suspend fun rememberSource() {
        if (source.identity == null) return
        player.state.first { it.isLoaded }
        runCatching { io { core.rememberLastSource(selection.details.item.id, selection.videoId, source) } }
    }

    /**
     * Asks AniSkip once the file's length is known: AniSkip picks the timings submitted for a
     * file of the same length. Playback never waits for it, and a failure only means no buttons.
     */
    private suspend fun findSkips() {
        val duration =
            player.state
                .mapNotNull { state ->
                    state.durationSeconds?.takeIf { state.isLoaded && it > 0 }
                }.first()
        val episode = selection.episode
        val segments =
            runCatching {
                core.skipSegments(
                    selection.details.item.id,
                    selection.videoId,
                    episode?.season,
                    episode?.episode,
                    duration,
                )
            }.getOrDefault(emptyList())
        mutableSkips.value = segments.toMarkers()
    }

    /** Adds the source's subtitles and the addons' ones in the device language and English. */
    private suspend fun addSubtitles() {
        val fromAddons = runCatching { core.subtitles(selection.kind, selection.videoId) }.getOrDefault(emptyList())
        val wanted = listOfNotNull(subtitleLanguage, "eng").distinct()
        (source.subtitles + fromAddons)
            .filter { it.lang.lowercase(Locale.ROOT) in wanted }
            .groupBy { it.lang.lowercase(Locale.ROOT) }
            .flatMap { (_, tracks) -> tracks.distinctBy(SubtitleTrack::url).take(SUBTITLES_PER_LANGUAGE) }
            .groupBy { "${it.lang}/${it.addonName}" }
            .values
            .forEach { tracks ->
                // The panel shows the language already; tell same-language files apart by
                // addon and number.
                tracks.forEachIndexed { index, track ->
                    val title = if (tracks.size > 1) "${track.addonName} ${index + 1}" else track.addonName
                    // Relayed like the video, so subtitle addresses stay out of the player too.
                    val url = runCatching { core.relayUrl(track.url) }.getOrNull() ?: return@forEachIndexed
                    player.addSubtitle(url, title = title, language = track.lang, select = false)
                }
            }
    }

    /**
     * When the file has no subtitle in the device language, picks the first external one in
     * it, so viewers who need subtitles get them without opening the panel.
     */
    private suspend fun selectSubtitleIfMissing() {
        val language = subtitleLanguage?.takeIf { it != "eng" } ?: return
        val state = player.state.first { it.isLoaded && it.tracks.isNotEmpty() }
        val subtitles = state.tracks.filter { it.type == Track.Type.Subtitle }
        if (subtitles.none { it.isSelected }) {
            subtitles
                .firstOrNull {
                    it.language?.lowercase(
                        Locale.ROOT,
                    ) == language
                }?.let { player.selectSubtitle(it.id) }
        }
    }

    /**
     * The released episode after this one, for "Continue watching" to move on to once this
     * one is finished; specials stay apart, as in [PlaybackSession.Selection.nextEpisode].
     */
    private fun nextVideo(): NextVideo? =
        selection.nextEpisode
            ?.takeIf { it.isReleased(Instant.now()) }
            ?.let { NextVideo(videoId = it.id, season = it.season, episode = it.episode) }

    fun saveProgress() {
        val state = player.state.value
        val duration = state.durationSeconds ?: return
        if (!state.isLoaded || duration <= 0) return
        val item = selection.details.item
        val progress =
            WatchProgress(
                id = item.id,
                kind = item.kind,
                name = item.name,
                poster = item.poster,
                background = item.background,
                videoId = selection.videoId,
                season = selection.episode?.season,
                episode = selection.episode?.episode,
                positionMs = (state.positionSeconds * 1000).toLong().coerceAtLeast(0).toULong(),
                durationMs = (duration * 1000).toLong().toULong(),
                updatedAt = 0,
            )
        viewModelScope.launch { runCatching { io { core.saveProgressWithNext(progress, nextVideo()) } } }
    }

    override fun onCleared() {
        val state = player.state.value
        val duration = state.durationSeconds
        if (state.isLoaded && duration != null && duration > 0) {
            val item = selection.details.item
            // The scope is already cancelled here, so write synchronously; it is one SQLite row.
            runCatching {
                core.saveProgressWithNext(
                    WatchProgress(
                        id = item.id,
                        kind = item.kind,
                        name = item.name,
                        poster = item.poster,
                        background = item.background,
                        videoId = selection.videoId,
                        season = selection.episode?.season,
                        episode = selection.episode?.episode,
                        positionMs = (state.positionSeconds * 1000).toLong().coerceAtLeast(0).toULong(),
                        durationMs = (duration * 1000).toLong().toULong(),
                        updatedAt = 0,
                    ),
                    nextVideo(),
                )
            }
        }
        player.release()
        // The screen is gone, so finish on the app's scope: stop the download and trim the cache.
        source.infoHash?.let { hash -> AppGraph.scope.launch { runCatching { core.stopTorrent(hash) } } }
    }
}

private fun Locale.isO3LanguageOrNull(): String? = runCatching { isO3Language }.getOrNull()?.ifEmpty { null }
