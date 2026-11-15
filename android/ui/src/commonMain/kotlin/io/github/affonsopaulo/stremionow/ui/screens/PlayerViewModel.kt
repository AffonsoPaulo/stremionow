// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.affonsopaulo.stremionow.core.Episode
import io.github.affonsopaulo.stremionow.core.NextVideo
import io.github.affonsopaulo.stremionow.core.SourceCheck
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

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

/** Bytes in a megabyte, for the debug log. */
private const val BYTES_PER_MB = 1_048_576L

/** How often a source still being downloaded by its debrid service is checked again. */
private const val PROVIDER_CHECK_MS = 15_000L

/** Checks of a downloading source that may fail in a row before the player gives up on it. */
private const val PROVIDER_CHECK_FAILURES = 5

/** Longest the player waits for a debrid service to download a source. */
private const val PROVIDER_WAIT_MAX_MS = 30 * 60_000L

/** How long a loaded video may take to tell its length; live streams never do. */
private const val LENGTH_WAIT_MS = 10_000L

/** After the last player of a torrent goes, how long before it is stopped, in case another takes it. */
private const val TORRENT_STOP_DELAY_MS = 10_000L

/**
 * Plays [selection] with [source]: asks the core for the address to play, picks subtitles in
 * the viewer's languages and the tracks picked before for the title, and saves progress while
 * playing and when the screen goes away. The platform's player comes from [createPlayer],
 * given the playback preferences.
 *
 * Moving on to another episode happens in this player ([openEpisode]), as in Stremio: the same
 * window, display mode, volume and tracks, the new video in place of the old one. The work of
 * the video left is cancelled, and [turns] keeps whatever of it still finishes from touching the
 * next one: a core call can come back after the video it was for has gone.
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

    /** The [languageKey] of the subtitles wanted, which addons and files write in many ways. */
    private val subtitleKey = subtitleChoice.ifEmpty { null }?.let(::languageKey)

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
                // Every code of the language: mpv compares them as they are, and files write
                // French as fr, fra or fre.
                audioLanguages = languageCodes(audioLanguage),
                // Subtitles start on only when the device language is not English.
                subtitleLanguages = subtitleChoice.ifEmpty { null }?.let(::languageCodes).orEmpty(),
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
    private val mutableProviderGaveUp = MutableStateFlow(false)

    /**
     * Why [prepareFailed], when it is the debrid service: it never got the source ready, or
     * stopped answering about it.
     */
    val providerGaveUp = mutableProviderGaveUp.asStateFlow()
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

    /** Which video's work may still change this player's state; see [VideoTurns]. */
    private val turns = VideoTurns()

    /** The work of the video playing, cancelled when the player moves on to another. */
    private var videoWork: Job? = null

    /**
     * The tracks remembered for the title: read for the first video, changed only by the
     * viewer's own picks, and carried to the next video this player plays.
     */
    private var trackMemory: TrackChoice? = null

    /** The viewer picked an audio track or a subtitle in the video playing, which wins over memory. */
    private var pickedAudio = false
    private var pickedSubtitle = false

    /**
     * The torrent this player asked the core to play for the video playing, if any. The core
     * counts each `playbackUrl` of a torrent and stops it only when every one was matched by a
     * `stopTorrent`, so this player stops exactly what it started, once (see [TorrentStops]).
     */
    private var playedTorrent: String? = null

    init {
        startVideo()
        viewModelScope.launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                if (!player.state.value.isPaused) saveProgress()
            }
        }
    }

    /**
     * Starts everything the current video needs, under [videoWork], and takes its torrent
     * before letting the previous one go, so the next episode of a season pack keeps it.
     */
    private fun startVideo() {
        val selection = selection
        val source = source
        val turn = turns.next()
        pickedAudio = false
        pickedSubtitle = false
        // The previous video's torrent stops a little later: the next episode of a season pack
        // plays from the same one, and the core keeps it while that playback counts.
        releasePlayedTorrent()
        videoWork =
            viewModelScope.launch {
                launch {
                    val watched = orNull { io { core.watchedVideos(selection.details.item.id) } }.orEmpty().toSet()
                    if (turns.isCurrent(turn)) mutableWatched.value = watched
                }
                source.torrentHash()?.let { hash ->
                    launch {
                        while (isActive) {
                            // A blocking call into the engine: off the main thread.
                            val status = io { core.torrentStatus(hash) }
                            if (turns.isCurrent(turn)) mutableTorrent.value = status
                            delay(TORRENT_STATUS_INTERVAL_MS)
                        }
                    }
                }
                launch { prepare(selection, source, turn) }
                launch { checkLength(selection, source, turn) }
                if (settings?.aniskip != false) launch { findSkips(selection, turn) }
                launch { findNextSource(selection, source, turn) }
            }
    }

    private suspend fun prepare(
        selection: PlaybackSession.Selection,
        source: StreamSource,
        turn: Long,
    ) {
        DebugLog.log { "prepare ${selection.videoId}: ${source.addonName} ${describe(source)}" }
        if (source.cached == false && !waitForProvider(source, turn)) {
            if (turns.isCurrent(turn)) {
                mutableProviderGaveUp.value = true
                mutablePreparing.value = false
                mutablePrepareFailed.value = true
            }
            return
        }
        val began = System.currentTimeMillis()
        if (!turns.isCurrent(turn)) return
        // Counted by the core from the call on, whether it succeeds or not: stopped once later.
        source.torrentHash()?.let { playedTorrent = it }
        // Every source plays through the local server, so the player never sees debrid keys.
        val url = orNull({ logWarning("Could not prepare the source", it) }) { core.playbackUrl(source) }
        // A video left meanwhile: its address must not replace the one playing now.
        if (!turns.isCurrent(turn)) return
        DebugLog.log {
            val took = System.currentTimeMillis() - began
            "prepare ${selection.videoId}: ${if (url != null) "ready" else "failed"} after $took ms"
        }
        if (url == null) {
            mutablePreparing.value = false
            mutablePrepareFailed.value = true
            return
        }
        val saved = orNull { io { core.progress(selection.details.item.id) } }
        val remembered = trackMemory ?: orNull { io { core.tracks(selection.details.item.id) } }
        if (!turns.isCurrent(turn)) return
        if (trackMemory == null) trackMemory = remembered
        mutablePreparing.value = false
        val start =
            saved
                ?.takeIf { it.videoId == selection.videoId }
                ?.positionMs
                ?.toDouble()
                ?.div(1000) ?: 0.0
        player.load(url, start)
        addSubtitles(selection, source, turn)
        if (!turns.isCurrent(turn)) return
        val memory = trackMemory
        if (memory != null) applyTracks(memory, turn) else selectSubtitleIfMissing(turn)
    }

    /**
     * Waits while the debrid service downloads a source that was not in its cache, asking now
     * and then whether its address leads to the file yet; asking also starts the download.
     * False when it gives up: the checks kept failing, or the wait grew too long.
     */
    private suspend fun waitForProvider(
        source: StreamSource,
        turn: Long,
    ): Boolean {
        mutableProviderDownloading.value = true
        try {
            val began = System.currentTimeMillis()
            var failuresInARow = 0
            while (true) {
                val asked = System.currentTimeMillis()
                var failure: Exception? = null
                val check: SourceCheck? = orNull({ failure = it }) { core.sourceReady(source) }
                if (!turns.isCurrent(turn)) return false
                failuresInARow = if (check == null) failuresInARow + 1 else 0
                val ready = check?.ready == true
                val failed = check?.failed == true
                val waited = System.currentTimeMillis() - began
                DebugLog.log {
                    val size = check?.sizeBytes?.let { "${it.toLong() / BYTES_PER_MB} MB" } ?: "size unknown"
                    val outcome =
                        failure?.let { "check failed (${it::class.simpleName})" }
                            ?: if (ready) "ready" else "still downloading"
                    "provider check: $outcome, $size, answered in ${System.currentTimeMillis() - asked} ms, " +
                        "waiting ${waited / 1000} s"
                }
                when (providerWait(ready, failuresInARow, waited, failed)) {
                    ProviderWait.Ready -> {
                        return true
                    }

                    ProviderWait.GiveUp -> {
                        DebugLog.log {
                            "provider: given up after ${waited / 1000} s, $failuresInARow failed checks in a row"
                        }
                        return false
                    }

                    ProviderWait.Wait -> {
                        delay(PROVIDER_CHECK_MS)
                    }
                }
            }
        } finally {
            if (turns.isCurrent(turn)) mutableProviderDownloading.value = false
        }
    }

    /**
     * Once the file's length is known, asks about a video much shorter than expected: until
     * the viewer says, its progress does not count, and its source is not remembered.
     */
    private suspend fun checkLength(
        selection: PlaybackSession.Selection,
        source: StreamSource,
        turn: Long,
    ) {
        player.state.first { it.isLoaded }
        val duration =
            withTimeoutOrNull(LENGTH_WAIT_MS) {
                player.state
                    .mapNotNull { state -> state.durationSeconds?.takeIf { state.isLoaded && it > 0 } }
                    .first()
            }
        if (!turns.isCurrent(turn)) return
        if (duration != null && looksIncomplete(duration, selection)) {
            DebugLog.log { "length ${selection.videoId}: ${clockText(duration)} looks incomplete" }
            mutableProgressCounts.value = false
            mutableIncomplete.value = duration
            return
        }
        rememberSource(selection, source)
    }

    /** The viewer's answer about a video much shorter than expected: whether its progress counts. */
    fun answerIncomplete(save: Boolean) {
        mutableIncomplete.value = null
        mutableProgressCounts.value = save
        DebugLog.log { "length ${selection.videoId}: progress ${if (save) "kept" else "not kept"}" }
        if (save) {
            val selection = selection
            val source = source
            viewModelScope.launch { rememberSource(selection, source) }
        }
    }

    /**
     * Picks the tracks of [choice] once the file lists its own, waiting a little for the
     * addons' subtitles when the one picked before is among them. What the viewer picked
     * meanwhile stays.
     */
    private suspend fun applyTracks(
        choice: TrackChoice,
        turn: Long,
    ) {
        val loaded = player.state.first { it.isLoaded && it.tracks.isNotEmpty() }
        val tracks =
            withTimeoutOrNull(SUBTITLE_WAIT_MS) {
                player.state.first { hasWantedSubtitle(it.tracks, choice) }.tracks
            } ?: player.state.value.tracks
                .ifEmpty { loaded.tracks }
        if (!turns.isCurrent(turn)) return
        val pick = pickTracks(tracks, choice)
        if (!pickedAudio) pick.audio?.let(player::selectAudio)
        if (!pickedSubtitle) {
            when (val subtitle = pick.subtitle) {
                is SubtitlePick.Track -> player.selectSubtitle(subtitle.id)
                SubtitlePick.Off -> player.selectSubtitle(null)
                null -> Unit
            }
        }
    }

    /**
     * The viewer picks an audio track, or none: played at once, and remembered for the title,
     * to pick it again next time and in the next episode. Only the viewer's picks are kept: what
     * the player chose by itself, where the track remembered was missing, is not.
     */
    fun selectAudio(trackId: Int?) {
        pickedAudio = true
        keepTracks(withAudioPicked(trackMemory, player.state.value.tracks, trackId))
        player.selectAudio(trackId)
    }

    /** As [selectAudio], for subtitles; null turns them off. */
    fun selectSubtitle(trackId: Int?) {
        pickedSubtitle = true
        keepTracks(withSubtitlePicked(trackMemory, player.state.value.tracks, trackId))
        player.selectSubtitle(trackId)
    }

    private fun keepTracks(choice: TrackChoice?) {
        if (choice == null || choice == trackMemory) return
        trackMemory = choice
        val titleId = selection.details.item.id
        AppGraph.scope.launch { orNull { io { core.rememberTracks(titleId, choice) } } }
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
        turn: Long,
    ) {
        val next = selection.nextEpisode ?: return
        val group = source.bingeGroup ?: return
        val wanted = SourceWanted(source.addonId, bingeGroup = group, addonKey = source.addonKey)
        val match = core.sourceFinder().find(selection.kind, next.id, wanted)
        // Only while this episode is still the one playing: the player may have moved on.
        if (match != null && turns.isCurrent(turn) && PlaybackSession.selection?.videoId == selection.videoId) {
            PlaybackSession.nextUp = PlaybackSession.NextUp(next.id, match)
        }
    }

    /**
     * Moves on to [episode] in this player. Its source is the one already found for the next
     * episode, or else the first of the same binge group from the addon of the source playing,
     * looked up while the loading screen shows. [onNotFound] is called, with the binge group of
     * the source left, when there is none to look for or none was found in a few seconds: the
     * episode's sources show instead.
     *
     * [asNext] is moving on to the next episode (its button, Up Next, the N key): the episode
     * left counts as finished however far the viewer got, as in Stremio, so it is marked watched
     * and Continue watching moves on.
     */
    fun openEpisode(
        episode: Episode,
        asNext: Boolean = false,
        onNotFound: (bingeGroup: String?) -> Unit,
    ) {
        val target = selection.copy(episode = episode)
        if (target.videoId == selection.videoId) return
        val ready = PlaybackSession.nextUp?.takeIf { it.episodeId == episode.id }?.source
        DebugLog.log { "open ${target.videoId}: ${if (ready != null) "source found ahead" else "looking it up"}" }
        val playing = source
        val group = playing.bingeGroup
        // The torrent stays held until the next source is known: the next episode of a season
        // pack plays from it, and stopping it during the lookup would start it over.
        leaveVideo(target, finished = asNext)
        if (ready != null) {
            start(target, ready)
            return
        }
        if (group == null) {
            releasePlayedTorrent()
            onNotFound(null)
            return
        }
        val turn = turns.current
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
                if (!turns.isCurrent(turn)) return@launch
                DebugLog.log { "open ${target.videoId}: ${found?.let(::describe) ?: "not found, showing its sources"}" }
                if (found != null) {
                    start(target, found)
                } else {
                    releasePlayedTorrent()
                    onNotFound(group)
                }
            }
    }

    /**
     * The screen is leaving the player (Back, another source, an episode's sources): nothing
     * more starts, an episode lookup under way included, which would otherwise play its find in
     * a player on its way out, or leave the screen a second time.
     */
    fun leave() {
        turns.next()
        videoWork?.cancel()
    }

    /**
     * Stops the video playing at once and shows [target] loading: saves how far the viewer
     * got (Continue watching moving on when it was finished, or [finished] says it counts as
     * such), and stops its work. Its torrent stays held until the next one is known.
     */
    private fun leaveVideo(
        target: PlaybackSession.Selection,
        finished: Boolean,
    ) {
        progressNow(finished)?.let { progress ->
            val next = nextVideoAfter(selection)
            AppGraph.scope.launch { orNull { io { core.saveProgressWithNext(progress, next) } } }
        }
        turns.next()
        videoWork?.cancel()
        player.stop()
        mutablePreparing.value = true
        mutablePrepareFailed.value = false
        mutableProviderGaveUp.value = false
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
     * Once the video starts and its length is not in doubt, remembers the source however it was
     * picked: it is offered first among this video's sources next time, and "Resume" and Continue
     * watching play it again (ResumePlayback.kt). A source that never started, or played a clip
     * in place of the video ([checkLength]), is not worth resuming with. Only its identity, binge
     * group and addon are kept, never its address.
     */
    private suspend fun rememberSource(
        selection: PlaybackSession.Selection,
        source: StreamSource,
    ) {
        if (source.identity == null) return
        orNull { io { core.rememberLastSource(selection.details.item.id, selection.videoId, source) } }
    }

    /**
     * Asks AniSkip once the file's length is known: AniSkip picks the timings submitted for a
     * file of the same length. Playback never waits for it, and a failure only means no buttons.
     */
    private suspend fun findSkips(
        selection: PlaybackSession.Selection,
        turn: Long,
    ) {
        val duration =
            player.state
                .mapNotNull { state ->
                    state.durationSeconds?.takeIf { state.isLoaded && it > 0 }
                }.first()
        val episode = selection.episode
        val segments =
            orNull {
                core.skipSegments(
                    selection.details.item.id,
                    selection.videoId,
                    episode?.season,
                    episode?.episode,
                    duration,
                )
            }.orEmpty()
        if (turns.isCurrent(turn)) mutableSkips.value = segments.toMarkers()
    }

    /**
     * Adds the source's subtitles and the addons' ones in the viewer's language and English,
     * however each writes the language (`fre`, `fra`, `fr`; `pob` for Brazilian Portuguese).
     * Those written as the viewer's own choice come first ("pt-BR" before European Portuguese).
     */
    private suspend fun addSubtitles(
        selection: PlaybackSession.Selection,
        source: StreamSource,
        turn: Long,
    ) {
        val fromAddons = orNull { core.subtitles(selection.kind, selection.videoId) }.orEmpty()
        val wanted = listOfNotNull(subtitleKey, ENGLISH).distinct()
        (source.subtitles + fromAddons)
            .filter { languageKey(it.lang) in wanted }
            .sortedBy { !displayLanguageTag(it.lang).equals(subtitleChoice, ignoreCase = true) }
            .groupBy { languageKey(it.lang) }
            .flatMap { (_, tracks) -> tracks.distinctBy(SubtitleTrack::url).take(SUBTITLES_PER_LANGUAGE) }
            .groupBy { "${languageKey(it.lang)}/${it.addonName}" }
            .values
            .forEach { tracks ->
                // The panel shows the language already; tell same-language files apart by
                // addon and number.
                tracks.forEachIndexed { index, track ->
                    val title = if (tracks.size > 1) "${track.addonName} ${index + 1}" else track.addonName
                    // Relayed like the video, so subtitle addresses stay out of the player too.
                    val url = orNull { core.relayUrl(track.url) } ?: return@forEachIndexed
                    if (!turns.isCurrent(turn)) return
                    player.addSubtitle(url, title = title, language = track.lang, select = false)
                }
            }
    }

    /**
     * When the file has no subtitle in the viewer's language, picks the first external one in
     * it, so viewers who need subtitles get them without opening the panel.
     */
    private suspend fun selectSubtitleIfMissing(turn: Long) {
        val language = subtitleKey?.takeIf { it != ENGLISH } ?: return
        val state = player.state.first { it.isLoaded && it.tracks.isNotEmpty() }
        if (!turns.isCurrent(turn) || pickedSubtitle) return
        val subtitles = state.tracks.filter { it.type == Track.Type.Subtitle }
        if (subtitles.none { it.isSelected }) {
            subtitles
                .firstOrNull { track -> track.language?.let(::languageKey) == language }
                ?.let { player.selectSubtitle(it.id) }
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

    /**
     * How far into the video playing the viewer is, or null before it has loaded. [finished]
     * puts it at the end, which the core counts as watched.
     */
    private fun progressNow(finished: Boolean = false): WatchProgress? {
        if (!mutableProgressCounts.value) return null
        val state = player.state.value
        val duration = state.durationSeconds ?: return null
        if (!state.isLoaded || duration <= 0) return null
        val item = selection.details.item
        val durationMs = (duration * 1000).toLong()
        return WatchProgress(
            id = item.id,
            kind = item.kind,
            name = item.name,
            poster = item.poster,
            background = item.background,
            videoId = selection.videoId,
            season = selection.episode?.season,
            episode = selection.episode?.episode,
            positionMs =
                (if (finished) durationMs else (state.positionSeconds * 1000).toLong())
                    .coerceAtLeast(0)
                    .toULong(),
            durationMs = durationMs.toULong(),
            updatedAt = 0,
        )
    }

    fun saveProgress() {
        val progress = progressNow() ?: return
        val next = nextVideoAfter(selection)
        viewModelScope.launch { orNull { io { core.saveProgressWithNext(progress, next) } } }
    }

    override fun onCleared() {
        // The scope is already cancelled here, so write synchronously; it is one SQLite row.
        progressNow()?.let { progress ->
            runCatching { core.saveProgressWithNext(progress, nextVideoAfter(selection)) }
        }
        player.release()
        // The screen is gone, so finish on the app's scope: stop the download and trim the cache.
        releasePlayedTorrent()
    }

    private fun releasePlayedTorrent() {
        playedTorrent?.let { TorrentStops.later(core, it) }
        playedTorrent = null
    }
}

/** ISO 639-2 English, always offered besides the viewer's language. */
private const val ENGLISH = "eng"

/** What waiting for a debrid service to download a source does next. */
internal enum class ProviderWait { Ready, Wait, GiveUp }

/**
 * After a check of a downloading source: play once [ready]; give up at once when the addon
 * refused the source for good ([failed], a 4xx), after [PROVIDER_CHECK_FAILURES] failed checks
 * in a row, which a failure that lasts gives within a minute or so, or after
 * [PROVIDER_WAIT_MAX_MS] [waitedMs] in all; else check again later.
 */
internal fun providerWait(
    ready: Boolean,
    failuresInARow: Int,
    waitedMs: Long,
    failed: Boolean = false,
): ProviderWait =
    when {
        ready -> ProviderWait.Ready
        failed -> ProviderWait.GiveUp
        failuresInARow >= PROVIDER_CHECK_FAILURES || waitedMs >= PROVIDER_WAIT_MAX_MS -> ProviderWait.GiveUp
        else -> ProviderWait.Wait
    }

/**
 * Turns of the videos a player plays, one after the other. Work started for a video keeps its
 * turn, and changes the player's state only while that turn is the [current] one: cancelling
 * the work of a video left is not enough, since a call into the core can still come back after
 * it, and that video's address or failure would then replace the next one's.
 */
internal class VideoTurns {
    private val counter = AtomicLong()

    /** The turn of the video playing, or being looked for. */
    val current: Long get() = counter.get()

    /** Starts a new turn: whatever was started before no longer counts. */
    fun next(): Long = counter.incrementAndGet()

    fun isCurrent(turn: Long): Boolean = turn == counter.get()
}

/**
 * Leaves the player once, however many requests arrive: Back pressed during an episode's
 * lookup, then the lookup's "not found", would otherwise navigate twice and close the page
 * under the player too. [onLeave] runs before the first navigation.
 */
internal class LeaveOnce(
    private val onLeave: () -> Unit = {},
) {
    var hasLeft = false
        private set

    fun to(navigate: () -> Unit) {
        if (hasLeft) return
        hasLeft = true
        onLeave()
        navigate()
    }
}

/**
 * Stopping the torrents this app played. The core counts every `playbackUrl` of a torrent and
 * stops it only once each was matched by a `stopTorrent`, so each player stops exactly what it
 * started, once. The stop waits a little: resuming right after leaving, or the next episode of a
 * season pack, starts the same torrent again, and the core keeps it while that playback counts.
 */
internal object TorrentStops {
    fun later(
        core: StremioCore,
        hash: String,
    ) {
        AppGraph.scope.launch {
            delay(TORRENT_STOP_DELAY_MS)
            orNull { core.stopTorrent(hash) }
        }
    }
}

/**
 * The torrent this source plays from peers, or null when it plays from an address: a debrid
 * source may name its torrent, but the core plays its address, so there are no peers to show
 * or torrent to stop.
 */
internal fun StreamSource.torrentHash(): String? = infoHash.takeIf { url == null }
