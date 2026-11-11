// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.affonsopaulo.stremionow.core.Episode
import io.github.affonsopaulo.stremionow.core.StreamSource
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.core.SubtitleTrack
import io.github.affonsopaulo.stremionow.core.TorrentPhase
import io.github.affonsopaulo.stremionow.core.TorrentProgress
import io.github.affonsopaulo.stremionow.core.WatchProgress
import io.github.affonsopaulo.stremionow.player.MpvPlayer
import io.github.affonsopaulo.stremionow.player.PlaybackState
import io.github.affonsopaulo.stremionow.player.PlayerOptions
import io.github.affonsopaulo.stremionow.player.PlayerSurface
import io.github.affonsopaulo.stremionow.player.Track
import io.github.affonsopaulo.stremionow.ui.R
import io.github.affonsopaulo.stremionow.ui.components.SnButton
import io.github.affonsopaulo.stremionow.ui.components.SnButtonStyle
import io.github.affonsopaulo.stremionow.ui.components.SnIconButton
import io.github.affonsopaulo.stremionow.ui.components.SnLoading
import io.github.affonsopaulo.stremionow.ui.components.SnLogoLoader
import io.github.affonsopaulo.stremionow.ui.components.SnSeekBar
import io.github.affonsopaulo.stremionow.ui.components.formatPlaybackTime
import io.github.affonsopaulo.stremionow.ui.components.snClickable
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.PlaybackSession
import io.github.affonsopaulo.stremionow.ui.data.io
import io.github.affonsopaulo.stremionow.ui.theme.FormFactor
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/** How often the torrent state is read for the loading screen. */
private const val TORRENT_STATUS_INTERVAL_MS = 1_000L

/** How often progress is written while playing. */
private const val PROGRESS_INTERVAL_MS = 10_000L

/** Controls hide after this long without input while playing. */
private const val CONTROLS_TIMEOUT_MS = 4_000L

/** External subtitles kept per language, so the list stays usable. */
private const val SUBTITLES_PER_LANGUAGE = 3

class PlayerViewModel(
    context: Context,
    private val core: StremioCore,
    val selection: PlaybackSession.Selection,
    private val source: StreamSource,
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

    val player: MpvPlayer =
        MpvPlayer.create(
            context,
            PlayerOptions(
                audioLanguages = listOf(audioLanguage),
                // Subtitles start on only when the device language is not English.
                subtitleLanguages = listOfNotNull(subtitleChoice.ifEmpty { null }),
                verboseLogs = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
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

    init {
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
                    .onFailure { android.util.Log.w("StremioNow", "Could not prepare the source", it) }
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
        viewModelScope.launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                if (!player.state.value.isPaused) saveProgress()
            }
        }
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
        viewModelScope.launch { runCatching { io { core.saveProgress(progress) } } }
    }

    override fun onCleared() {
        val state = player.state.value
        val duration = state.durationSeconds
        if (state.isLoaded && duration != null && duration > 0) {
            val item = selection.details.item
            // The scope is already cancelled here, so write synchronously; it is one SQLite row.
            runCatching {
                core.saveProgress(
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
                )
            }
        }
        player.release()
        // The screen is gone, so finish on the app's scope: stop the download and trim the cache.
        source.infoHash?.let { hash -> AppGraph.scope.launch { runCatching { core.stopTorrent(hash) } } }
    }
}

private fun Locale.isO3LanguageOrNull(): String? = runCatching { isO3Language }.getOrNull()?.ifEmpty { null }

private enum class Panel { Tracks, Episodes }

/**
 * Full-screen player with HBO Max–style controls: title at the top, play and ten-second
 * skips in the middle, the timeline and the audio/subtitles, episodes, change-source and
 * next-episode actions at the bottom. Controls fade out while playing and come back on any
 * tap or key press.
 */
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    onChangeSource: () -> Unit,
    onOpenEpisode: (Episode) -> Unit,
) {
    val selection = PlaybackSession.selection
    val source = PlaybackSession.source
    if (selection == null || (source?.url == null && source?.infoHash == null)) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val context = LocalContext.current
    val viewModel =
        viewModel(key = "player-${selection.videoId}-${source.url.hashCode()}") {
            PlayerViewModel(context.applicationContext, AppGraph.core(context), selection, source)
        }
    val state by viewModel.player.state.collectAsStateWithLifecycle()
    val preparing by viewModel.preparing.collectAsStateWithLifecycle()
    val torrent by viewModel.torrent.collectAsStateWithLifecycle()
    val prepareFailed by viewModel.prepareFailed.collectAsStateWithLifecycle()
    var controlsVisible by remember { mutableStateOf(true) }
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var scrubSeconds by remember { mutableStateOf<Double?>(null) }
    val poke = {
        controlsVisible = true
        lastInput = System.currentTimeMillis()
    }

    ImmersivePlayback()
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        viewModel.player.pause()
        viewModel.saveProgress()
    }
    LaunchedEffect(lastInput, state.isPaused, panel, scrubSeconds) {
        if (!state.isPaused && panel == null && scrubSeconds == null) {
            delay(CONTROLS_TIMEOUT_MS)
            controlsVisible = false
        }
    }
    BackHandler(enabled = panel != null) { panel = null }
    val next = selection.nextEpisode

    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.MediaPlayPause, Key.Spacebar -> {
                        viewModel.player.togglePause()
                        poke()
                        true
                    }

                    Key.MediaPlay -> {
                        viewModel.player.play()
                        true
                    }

                    Key.MediaPause -> {
                        viewModel.player.pause()
                        poke()
                        true
                    }

                    Key.MediaFastForward -> {
                        viewModel.player.seekBy(10.0)
                        true
                    }

                    Key.MediaRewind -> {
                        viewModel.player.seekBy(-10.0)
                        true
                    }

                    else -> {
                        // The first press only brings the controls back, so it never acts blindly.
                        val wasHidden = !controlsVisible
                        poke()
                        wasHidden && event.key != Key.Back
                    }
                }
            }.focusable()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (controlsVisible && panel == null) controlsVisible = false else poke()
            },
    ) {
        PlayerSurface(viewModel.player, Modifier.fillMaxSize())

        if (!prepareFailed && state.error == null) {
            if (preparing || !state.isLoaded) {
                // Until the first frame: the title's logo fills while the source gets ready.
                SnLogoLoader(
                    logo = selection.details.item.logo,
                    title = selection.details.item.name,
                    progress = null,
                    status = torrent?.let { torrentStatusText(it) },
                    label = stringResource(R.string.player_preparing),
                    modifier = Modifier.align(Alignment.Center).padding(SnSpacing.space8),
                )
            } else if (state.isBuffering) {
                SnLoading(Modifier.align(Alignment.Center))
            }
        }

        AnimatedVisibility(controlsVisible && state.error == null, enter = fadeIn(), exit = fadeOut()) {
            Controls(
                selection = selection,
                state = state,
                scrubSeconds = scrubSeconds,
                hasNext = next != null,
                onBack = onBack,
                onTogglePause = {
                    viewModel.player.togglePause()
                    poke()
                },
                onSeekBy = {
                    viewModel.player.seekBy(it)
                    poke()
                },
                onSeekTo = {
                    viewModel.player.seekTo(it)
                    poke()
                },
                onScrub = {
                    scrubSeconds = it
                    poke()
                },
                onTracks = { panel = Panel.Tracks },
                onEpisodes = { panel = Panel.Episodes },
                onChangeSource = onChangeSource,
                onNext = { next?.let(onOpenEpisode) },
            )
        }

        if (state.error != null || prepareFailed) {
            PlaybackError(onChangeSource = onChangeSource, onBack = onBack, modifier = Modifier.align(Alignment.Center))
        } else if (state.isEnded && next != null) {
            SnButton(
                text = stringResource(R.string.player_next),
                icon = SnIcons.Next,
                onClick = { onOpenEpisode(next) },
                modifier = Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(SnSpacing.space8),
            )
        }

        when (panel) {
            Panel.Tracks -> {
                SidePanel(stringResource(R.string.player_tracks), onClose = { panel = null }) {
                    TracksList(
                        tracks = state.tracks,
                        onSelectAudio = viewModel.player::selectAudio,
                        onSelectSubtitle = viewModel.player::selectSubtitle,
                    )
                }
            }

            Panel.Episodes -> {
                SidePanel(stringResource(R.string.player_episodes), onClose = { panel = null }) {
                    EpisodesList(selection, onOpenEpisode)
                }
            }

            null -> {}
        }
    }
}

/** Hides the system bars, keeps the screen on and turns phones sideways while playing. */
@Composable
private fun ImmersivePlayback() {
    val view = LocalView.current
    val phone = SnLayout.formFactor == FormFactor.Phone
    DisposableEffect(view, phone) {
        val activity = view.context as? Activity
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        val previousOrientation = activity?.requestedOrientation
        if (phone) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
            if (phone && previousOrientation != null) activity.requestedOrientation = previousOrientation
        }
    }
}

@Composable
private fun Controls(
    selection: PlaybackSession.Selection,
    state: PlaybackState,
    scrubSeconds: Double?,
    hasNext: Boolean,
    onBack: () -> Unit,
    onTogglePause: () -> Unit,
    onSeekBy: (Double) -> Unit,
    onSeekTo: (Double) -> Unit,
    onScrub: (Double?) -> Unit,
    onTracks: () -> Unit,
    onEpisodes: () -> Unit,
    onChangeSource: () -> Unit,
    onNext: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to SnColors.scrim,
                    0.25f to Color.Transparent,
                    0.65f to Color.Transparent,
                    1f to SnColors.scrim,
                ),
            ).safeDrawingPadding()
            .padding(horizontal = SnLayout.gutter, vertical = SnSpacing.space4),
    ) {
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
        ) {
            SnIconButton(SnIcons.ChevronLeft, stringResource(R.string.nav_back), onBack)
            Column(Modifier.weight(1f)) {
                BasicText(
                    selection.details.item.name,
                    modifier = Modifier.semantics { heading() },
                    style = SnTypography.headingMd.copy(color = SnColors.fg),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                selection.episode?.let { episode ->
                    val season = episode.season?.toInt()
                    val number = episode.episode?.toInt()
                    val label =
                        if (season != null && number != null) {
                            "${stringResource(R.string.episode_short, season, number)} · ${episode.title}"
                        } else {
                            episode.title
                        }
                    BasicText(label, style = SnTypography.body.copy(color = SnColors.fgSecondary), maxLines = 1)
                }
            }
        }

        Row(
            Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space10),
        ) {
            SnIconButton(SnIcons.Back10, stringResource(R.string.player_back10), { onSeekBy(-10.0) })
            val playLabel = stringResource(if (state.isPaused) R.string.player_play else R.string.player_pause)
            Box(
                Modifier
                    .size(72.dp)
                    .semantics { contentDescription = playLabel }
                    .snClickable(RoundedCornerShape(SnRadius.radiusFull), onClick = onTogglePause)
                    .background(SnColors.overlayLight, RoundedCornerShape(SnRadius.radiusFull)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    if (state.isPaused) SnIcons.Play else SnIcons.Pause,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    colorFilter = ColorFilter.tint(SnColors.fg),
                )
            }
            SnIconButton(SnIcons.Forward10, stringResource(R.string.player_forward10), { onSeekBy(10.0) })
        }

        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SnSpacing.space2),
        ) {
            val duration = state.durationSeconds ?: 0.0
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
            ) {
                SnSeekBar(
                    positionSeconds = state.positionSeconds,
                    durationSeconds = duration,
                    bufferedSeconds = state.bufferedAheadSeconds,
                    label = stringResource(R.string.player_timeline),
                    onSeek = onSeekTo,
                    onScrub = onScrub,
                    modifier = Modifier.weight(1f),
                )
                BasicText(
                    "${formatPlaybackTime(scrubSeconds ?: state.positionSeconds)} / ${formatPlaybackTime(duration)}",
                    style = SnTypography.label.copy(color = SnColors.fg),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SnSpacing.space2),
            ) {
                SnIconButton(SnIcons.Subtitles, stringResource(R.string.player_tracks), onTracks)
                if (selection.details.episodes.isNotEmpty()) {
                    SnIconButton(SnIcons.Episodes, stringResource(R.string.player_episodes), onEpisodes)
                }
                SnIconButton(SnIcons.Layers, stringResource(R.string.player_change_source), onChangeSource)
                Box(Modifier.weight(1f))
                if (hasNext) SnIconButton(SnIcons.Next, stringResource(R.string.player_next), onNext)
            }
        }
    }
}

@Composable
private fun PlaybackError(
    onChangeSource: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.widthIn(max = 480.dp).padding(SnSpacing.space6),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space3),
    ) {
        BasicText(stringResource(R.string.player_error_title), style = SnTypography.headingMd.copy(color = SnColors.fg))
        BasicText(
            stringResource(R.string.player_error_body),
            style = SnTypography.body.copy(color = SnColors.fgSecondary),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(SnSpacing.space2)) {
            SnButton(stringResource(R.string.nav_back), onBack, style = SnButtonStyle.Ghost)
            SnButton(stringResource(R.string.player_choose_other), onChangeSource)
        }
    }
}

@Composable
private fun SidePanel(
    title: String,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(SnColors.scrimSoft)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose,
            ),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .width(380.dp)
                .background(SnColors.surfaceRaised)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .safeDrawingPadding()
                .padding(SnSpacing.space4),
            verticalArrangement = Arrangement.spacedBy(SnSpacing.space3),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    title,
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = SnTypography.sectionTitle.copy(color = SnColors.fg),
                )
                SnIconButton(SnIcons.Close, stringResource(R.string.action_close), onClose)
            }
            content()
        }
    }
}

@Composable
private fun TracksList(
    tracks: List<Track>,
    onSelectAudio: (Int?) -> Unit,
    onSelectSubtitle: (Int?) -> Unit,
) {
    val audio = tracks.filter { it.type == Track.Type.Audio }
    val subtitles = tracks.filter { it.type == Track.Type.Subtitle }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(SnSpacing.space1)) {
        if (audio.size > 1) {
            item { PanelHeading(stringResource(R.string.player_audio)) }
            items(audio, key = { "a${it.id}" }) { track ->
                PanelOption(trackLabel(track), track.isSelected) { onSelectAudio(track.id) }
            }
        }
        item { PanelHeading(stringResource(R.string.player_subtitles)) }
        item(key = "off") {
            PanelOption(
                stringResource(R.string.player_subtitles_off),
                subtitles.none { it.isSelected },
            ) { onSelectSubtitle(null) }
        }
        items(subtitles, key = { "s${it.id}" }) { track ->
            PanelOption(trackLabel(track), track.isSelected) { onSelectSubtitle(track.id) }
        }
    }
}

@Composable
private fun trackLabel(track: Track): String {
    val language =
        track.language?.let { code ->
            Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault()).takeIf { it.isNotBlank() && it != code }
                ?: code
        }
    return listOfNotNull(language, track.title?.takeIf { it.isNotBlank() && it != track.language })
        .joinToString(" · ")
        .ifEmpty { stringResource(R.string.player_track_number, track.id) }
}

@Composable
private fun EpisodesList(
    selection: PlaybackSession.Selection,
    onOpenEpisode: (Episode) -> Unit,
) {
    val season = selection.episode?.season
    val episodes = selection.details.episodes.filter { it.season == season }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(SnSpacing.space1)) {
        items(episodes, key = { it.id }) { episode ->
            val number = episode.episode?.toInt()
            val label =
                if (number !=
                    null
                ) {
                    stringResource(R.string.episode_numbered, number, episode.title)
                } else {
                    episode.title
                }
            PanelOption(label, episode.id == selection.videoId) {
                if (episode.id != selection.videoId) onOpenEpisode(episode)
            }
        }
    }
}

@Composable
private fun PanelHeading(text: String) {
    BasicText(
        text.uppercase(),
        modifier = Modifier.padding(top = SnSpacing.space3, bottom = SnSpacing.space1).semantics { heading() },
        style = SnTypography.overline.copy(color = SnColors.fgMuted),
    )
}

@Composable
private fun PanelOption(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(SnRadius.radiusMd)
    Row(
        Modifier
            .fillMaxWidth()
            .snClickable(shape, onClick = onClick)
            .background(if (selected) SnColors.surfaceSelected else Color.Transparent, shape)
            .padding(horizontal = SnSpacing.space3, vertical = SnSpacing.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
    ) {
        BasicText(
            text,
            modifier = Modifier.weight(1f),
            style = SnTypography.body.copy(color = if (selected) SnColors.fg else SnColors.fgSecondary),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (selected) {
            Image(
                SnIcons.Check,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                colorFilter = ColorFilter.tint(SnColors.accent),
            )
        }
    }
}

/** "Looking for peers…", "Checking downloaded data…" or "12 peers · 2.4 MB/s". */
@Composable
private fun torrentStatusText(progress: TorrentProgress): String {
    val context = LocalContext.current
    return when (progress.phase) {
        TorrentPhase.METADATA -> {
            stringResource(R.string.torrent_finding_peers)
        }

        TorrentPhase.CHECKING -> {
            stringResource(R.string.torrent_checking)
        }

        TorrentPhase.FAILED -> {
            stringResource(R.string.player_error_title)
        }

        else -> {
            val peers = progress.peers.toInt()
            val speed = Formatter.formatShortFileSize(context, progress.downloadSpeed.toLong())
            pluralStringResource(R.plurals.torrent_peers, peers, peers, speed)
        }
    }
}
