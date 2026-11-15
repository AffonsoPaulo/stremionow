// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.affonsopaulo.stremionow.core.Episode
import io.github.affonsopaulo.stremionow.core.NextVideo
import io.github.affonsopaulo.stremionow.core.StreamSource
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.core.SubtitleTrack
import io.github.affonsopaulo.stremionow.core.TorrentProgress
import io.github.affonsopaulo.stremionow.core.TrackChoice
import io.github.affonsopaulo.stremionow.core.WatchProgress
import io.github.affonsopaulo.stremionow.player.Markers
import io.github.affonsopaulo.stremionow.player.PlayerOptions
import io.github.affonsopaulo.stremionow.player.Track
import io.github.affonsopaulo.stremionow.player.VideoPlayer
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.PlaybackSession
import io.github.affonsopaulo.stremionow.ui.data.io
import io.github.affonsopaulo.stremionow.ui.platform.DebugLog
import io.github.affonsopaulo.stremionow.ui.platform.logWarning
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.Locale

/** How often the torrent state is read for the loading screen. */
private const val TORRENT_STATUS_INTERVAL_MS = 1_000L

/** How often progress is written while playing. */
private const val PROGRESS_INTERVAL_MS = 10_000L

/** External subtitles kept per language, so the list stays usable. */
private const val SUBTITLES_PER_LANGUAGE = 3

/** How long tracks wait for the external subtitle picked before, while addons answer. */
private const val SUBTITLE_WAIT_MS = 10_000L

/** How long moving on to an episode looks for its source before showing its sources. */
private const val EPISODE_LOOKUP_MS = 6_000L

/** How often a source still being downloaded by its debrid service is checked again. */
private const val PROVIDER_CHECK_MS = 15_000L

/** After the last player of a torrent goes, how long before it is stopped, in case another takes it. */
private const val TORRENT_STOP_DELAY_MS = 3_000L

/**
 * Plays [selection] with [source]: asks the core for the address to play, picks subtitles in
 * the viewer's languages and the tracks picked before for the title, and saves progress while
 * playing and when the screen goes away. The platform's player comes from [createPlayer],
 * given the playback preferences.
 *
 * Moving on to another episode happens in this player ([openEpisode]), as in Stremio: the same
 * window, display mode, volume and tracks, the new video in place of the old one.
 */
class PlayerViewModel<out P : VideoPlayer>(
    private val core: StremioCore,
    selection: PlaybackSession.Selection,
    source: StreamSource,
    createPlayer: (PlayerOptions) -> P,
) : ViewModel() {
    /** The video playing; it changes when the player moves on to another episode. */
    var selection: PlaybackSession.Selection by mutableStateOf(selection)
        private set

    /** The source playing [selection]. */
    private var source: StreamSource by mutableStateOf(source)

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

    /** How subtitles look, from Settings › Playback. */
    val subtitleStyle = settings.subtitleStyle()

    /** What the Up Next card does, from Settings › Playback. */
    internal val upNext = UpNextBehavior.from(settings)

    /** How far the keys and the skip buttons move, from Settings › Playback. */
    internal val seekSteps = SeekSteps.from(settings)

    /** Esc leaves full screen first (desktop), from Settings › General. */
    internal val escLeavesFullScreen = settings?.escExitsFullScreen ?: true

    val player: P =
        createPlayer(
            PlayerOptions(
                audioLanguages = listOf(audioLanguage),
                // Subtitles start on only when the device language is not English.
                subtitleLanguages = listOfNotNull(subtitleChoice.ifEmpty { null }),
                // "Memory for the video"; neither set means the player decides (see VideoMemory.kt).
                streamMemory = settings.streamMemoryOptions().first,
                streamMemorySizes = settings.streamMemoryOptions().second,
                subtitleStyle = subtitleStyle,
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

    private val mutableProviderDownloading = MutableStateFlow(false)

    /**
     * The source was not in its debrid service's cache: the provider is downloading it, and it
     * plays by itself once ready, instead of the addon's "being downloaded" clip.
     */
    val providerDownloading = mutableProviderDownloading.asStateFlow()
    private val mutableIncomplete = MutableStateFlow<Double?>(null)

    /**
     * The length of a video much shorter than expected (see [looksIncomplete]), while the viewer
     * has not said whether its progress counts; null otherwise.
     */
    val incomplete = mutableIncomplete.asStateFlow()
    private val mutableProgressCounts = MutableStateFlow(true)

    /**
     * Whether the video's progress is saved, counts it as watched and leads on to the next
     * episode: not for a video much shorter than expected, unless the viewer says so.
     */
    val progressCounts = mutableProgressCounts.asStateFlow()

    /** The work of the video playing, cancelled when the player moves on to another. */
    private var videoWork: Job? = null

    /** The tracks picked in this player, carried to the next video it plays. */
    private var carriedTracks: TrackChoice? = null

    /** The torrent the video playing holds in [TorrentUsers], if it plays one. */
    private var heldTorrent: String? = null

    init {
        startVideo()
        viewModelScope.launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                if (!player.state.value.isPaused) saveProgress()
            }
        }
    }

    /** Starts everything the current video needs, under [videoWork]. */
    private fun startVideo() {
        val selection = selection
        val source = source
        heldTorrent = source.torrentHash()?.also(TorrentUsers::take)
        videoWork =
            viewModelScope.launch {
                launch {
                    mutableWatched.value =
                        runCatching { io { core.watchedVideos(selection.details.item.id) } }
                            .getOrDefault(emptyList())
                            .toSet()
                }
                source.torrentHash()?.let { hash ->
                    launch {
                        while (isActive) {
                            mutableTorrent.value = core.torrentStatus(hash)
                            delay(TORRENT_STATUS_INTERVAL_MS)
                        }
                    }
                }
                launch { prepare(selection, source) }
                launch { checkLength(selection) }
                if (settings?.aniskip != false) launch { findSkips(selection) }
                launch { findNextSource(selection, source) }
                launch { rememberSource(selection, source) }
            }
    }

    private suspend fun prepare(
        selection: PlaybackSession.Selection,
        source: StreamSource,
    ) {
        DebugLog.log { "prepare ${selection.videoId}: ${source.addonName} ${describe(source)}" }
        if (source.cached == false) waitForProvider(source)
        val began = System.currentTimeMillis()
        // Every source plays through the local server, so the player never sees debrid keys.
        val url =
            runCatching { core.playbackUrl(source) }
                .onFailure { logWarning("Could not prepare the source", it) }
                .getOrNull()
        DebugLog.log {
            val took = System.currentTimeMillis() - began
            "prepare ${selection.videoId}: ${if (url != null) "ready" else "failed"} after $took ms"
        }
        if (url == null) {
            mutablePreparing.value = false
            mutablePrepareFailed.value = true
            return
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
        addSubtitles(selection, source)
        val remembered =
            carriedTracks ?: runCatching { io { core.tracks(selection.details.item.id) } }.getOrNull()
        if (remembered != null) applyTracks(remembered) else selectSubtitleIfMissing()
        recordTracks(selection)
    }

    /**
     * Waits while the debrid service downloads a source that was not in its cache, asking now
     * and then whether its address leads to the file yet; asking also starts the download.
     */
    private suspend fun waitForProvider(source: StreamSource) {
        mutableProviderDownloading.value = true
        while (true) {
            val ready = runCatching { core.sourceReady(source) }.getOrDefault(false)
            DebugLog.log { "provider check: ${if (ready) "ready" else "still downloading"}" }
            if (ready) break
            delay(PROVIDER_CHECK_MS)
        }
        mutableProviderDownloading.value = false
    }

    /**
     * Once the file's length is known, asks about a video much shorter than expected: until
     * the viewer says, its progress does not count.
     */
    private suspend fun checkLength(selection: PlaybackSession.Selection) {
        val duration =
            player.state
                .mapNotNull { state -> state.durationSeconds?.takeIf { state.isLoaded && it > 0 } }
                .first()
        if (looksIncomplete(duration, selection)) {
            DebugLog.log { "length ${selection.videoId}: ${clockText(duration)} looks incomplete" }
            mutableProgressCounts.value = false
            mutableIncomplete.value = duration
        }
    }

    /** The viewer's answer about a video much shorter than expected: whether its progress counts. */
    fun answerIncomplete(save: Boolean) {
        mutableIncomplete.value = null
        mutableProgressCounts.value = save
        DebugLog.log { "length ${selection.videoId}: progress ${if (save) "kept" else "not kept"}" }
    }

    /**
     * Picks the tracks of [choice] once the file lists its own, waiting a little for the
     * addons' subtitles when the one picked before is among them.
     */
    private suspend fun applyTracks(choice: TrackChoice) {
        val loaded = player.state.first { it.isLoaded && it.tracks.isNotEmpty() }
        val tracks =
            withTimeoutOrNull(SUBTITLE_WAIT_MS) {
                player.state.first { hasWantedSubtitle(it.tracks, choice) }.tracks
            } ?: player.state.value.tracks
                .ifEmpty { loaded.tracks }
        val pick = pickTracks(tracks, choice)
        pick.audio?.let(player::selectAudio)
        when (val subtitle = pick.subtitle) {
            is SubtitlePick.Track -> player.selectSubtitle(subtitle.id)
            SubtitlePick.Off -> player.selectSubtitle(null)
            null -> Unit
        }
    }

    /**
     * From now on, keeps the tracks the viewer picks for the title, to pick them again next
     * time and in the next episode.
     */
    private suspend fun recordTracks(selection: PlaybackSession.Selection) {
        player.state
            .map { if (it.isLoaded) choiceOf(it.tracks) else null }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { choice ->
                carriedTracks = choice
                runCatching { io { core.rememberTracks(selection.details.item.id, choice) } }
            }
    }

    /**
     * Finds the next episode's source while this one plays, as Stremio does: asks the addon
     * of the source playing for the next episode's sources and keeps the first of the same
     * binge group, so moving on needs no choice ([PlaybackSession.nextUp]). Without a group, or
     * a match, the next episode's sources show instead.
     */
    private suspend fun findNextSource(
        selection: PlaybackSession.Selection,
        source: StreamSource,
    ) {
        val next = selection.nextEpisode ?: return
        val group = source.bingeGroup ?: return
        val wanted = SourceWanted(source.addonId, bingeGroup = group, addonKey = source.addonKey)
        val match = core.sourceFinder().find(selection.kind, next.id, wanted)
        // Only while this episode is still the one chosen: the session may have moved on.
        if (match != null && PlaybackSession.selection?.videoId == selection.videoId) {
            PlaybackSession.nextUp = PlaybackSession.NextUp(next.id, match)
        }
    }

    /**
     * Moves on to [episode] in this player. Its source is the one already found for the next
     * episode, or else the first of the same binge group from the addon of the source playing,
     * looked up while the loading screen shows. [onNotFound] is called when there is none to
     * look for or none was found in a few seconds: the episode's sources show instead.
     */
    fun openEpisode(
        episode: Episode,
        onNotFound: () -> Unit,
    ) {
        val target = selection.copy(episode = episode)
        if (target.videoId == selection.videoId) return
        val ready = PlaybackSession.nextUp?.takeIf { it.episodeId == episode.id }?.source
        DebugLog.log { "open ${target.videoId}: ${if (ready != null) "source found ahead" else "looking it up"}" }
        if (ready != null) {
            switchTo(target, ready)
            return
        }
        val playing = source
        val group = playing.bingeGroup
        if (group == null) {
            onNotFound()
            return
        }
        leaveVideo(target)
        videoWork =
            viewModelScope.launch {
                val found =
                    withTimeoutOrNull(EPISODE_LOOKUP_MS) {
                        core.sourceFinder().find(
                            target.kind,
                            target.videoId,
                            SourceWanted(playing.addonId, bingeGroup = group, addonKey = playing.addonKey),
                        )
                    }
                DebugLog.log { "open ${target.videoId}: ${found?.let(::describe) ?: "not found, showing its sources"}" }
                if (found != null) start(target, found) else onNotFound()
            }
    }

    /** Plays [target] with [source] in place of the video playing. */
    private fun switchTo(
        target: PlaybackSession.Selection,
        source: StreamSource,
    ) {
        leaveVideo(target)
        start(target, source)
    }

    /**
     * Stops the video playing at once and shows [target] loading: saves how far the viewer
     * got (Continue watching moving on when it was finished), stops its work and its torrent.
     */
    private fun leaveVideo(target: PlaybackSession.Selection) {
        progressNow()?.let { progress ->
            val next = nextVideoAfter(selection)
            AppGraph.scope.launch { runCatching { io { core.saveProgressWithNext(progress, next) } } }
        }
        videoWork?.cancel()
        player.stop()
        heldTorrent?.let(::releaseTorrent)
        heldTorrent = null
        mutablePreparing.value = true
        mutablePrepareFailed.value = false
        mutableTorrent.value = null
        mutableSkips.value = Markers()
        mutableProviderDownloading.value = false
        mutableIncomplete.value = null
        mutableProgressCounts.value = true
        selection = target
        PlaybackSession.select(target)
    }

    private fun start(
        target: PlaybackSession.Selection,
        source: StreamSource,
    ) {
        this.source = source
        PlaybackSession.select(target)
        PlaybackSession.source = source
        PlaybackSession.alternatives = emptyList()
        startVideo()
    }

    /**
     * Once the video starts, remembers the source however it was picked: it is offered first
     * among this video's sources next time, and "Resume" and Continue watching play it again
     * (ResumePlayback.kt). A source that never started is not worth resuming with. Only its
     * identity, binge group and addon are kept, never its address.
     */
    private suspend fun rememberSource(
        selection: PlaybackSession.Selection,
        source: StreamSource,
    ) {
        if (source.identity == null) return
        player.state.first { it.isLoaded }
        runCatching { io { core.rememberLastSource(selection.details.item.id, selection.videoId, source) } }
    }

    /**
     * Asks AniSkip once the file's length is known: AniSkip picks the timings submitted for a
     * file of the same length. Playback never waits for it, and a failure only means no buttons.
     */
    private suspend fun findSkips(selection: PlaybackSession.Selection) {
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
    private suspend fun addSubtitles(
        selection: PlaybackSession.Selection,
        source: StreamSource,
    ) {
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
     * The released episode after [selection]'s, for "Continue watching" to move on to once it
     * is finished; specials stay apart, as in [PlaybackSession.Selection.nextEpisode].
     */
    private fun nextVideoAfter(selection: PlaybackSession.Selection): NextVideo? =
        selection.nextEpisode
            ?.takeIf { it.isReleased(Instant.now()) }
            ?.let { NextVideo(videoId = it.id, season = it.season, episode = it.episode) }

    /** How far into the video playing the viewer is, or null before it has loaded. */
    private fun progressNow(): WatchProgress? {
        if (!mutableProgressCounts.value) return null
        val state = player.state.value
        val duration = state.durationSeconds ?: return null
        if (!state.isLoaded || duration <= 0) return null
        val item = selection.details.item
        return WatchProgress(
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
    }

    fun saveProgress() {
        val progress = progressNow() ?: return
        val next = nextVideoAfter(selection)
        viewModelScope.launch { runCatching { io { core.saveProgressWithNext(progress, next) } } }
    }

    override fun onCleared() {
        // The scope is already cancelled here, so write synchronously; it is one SQLite row.
        progressNow()?.let { progress ->
            runCatching { core.saveProgressWithNext(progress, nextVideoAfter(selection)) }
        }
        player.release()
        // The screen is gone, so finish on the app's scope: stop the download and trim the cache.
        heldTorrent?.let(::releaseTorrent)
        heldTorrent = null
    }

    /** Lets the torrent go: once no player uses it any more, it stops after a short while. */
    private fun releaseTorrent(hash: String) {
        if (!TorrentUsers.give(hash)) return
        AppGraph.scope.launch {
            delay(TORRENT_STOP_DELAY_MS)
            if (!TorrentUsers.inUse(hash)) runCatching { core.stopTorrent(hash) }
        }
    }
}

/**
 * How many players play each torrent. A player that goes stops its torrent only when no other
 * took it meanwhile: resuming right after leaving, or the next episode of a season pack, plays
 * the same torrent, which stopping would cut.
 */
internal object TorrentUsers {
    private val counts = HashMap<String, Int>()

    @Synchronized
    fun take(hash: String) {
        counts[hash] = (counts[hash] ?: 0) + 1
    }

    /** Lets [hash] go; true when no player plays it any more. */
    @Synchronized
    fun give(hash: String): Boolean {
        val left = (counts[hash] ?: 1) - 1
        if (left <= 0) counts.remove(hash) else counts[hash] = left
        return left <= 0
    }

    @Synchronized
    fun inUse(hash: String): Boolean = (counts[hash] ?: 0) > 0
}

private fun Locale.isO3LanguageOrNull(): String? = runCatching { isO3Language }.getOrNull()?.ifEmpty { null }

/**
 * The torrent this source plays from peers, or null when it plays from an address: a debrid
 * source may name its torrent, but the core plays its address, so there are no peers to show
 * or torrent to stop.
 */
internal fun StreamSource.torrentHash(): String? = infoHash.takeIf { url == null }
