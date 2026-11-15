// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.affonsopaulo.stremionow.core.Episode
import io.github.affonsopaulo.stremionow.player.MpvPlayer
import io.github.affonsopaulo.stremionow.player.PlaybackState
import io.github.affonsopaulo.stremionow.player.PlayerSurface
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
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.theme.FormFactor
import io.github.affonsopaulo.stremionow.ui.theme.LocalIsTv
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnLayout
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography
import kotlinx.coroutines.delay

/**
 * Full-screen player with HBO Max–style controls: title at the top, play and the skips back
 * and forward (Settings' seek step) in the middle, the timeline and the audio/subtitles, episodes, change-source and
 * next-episode actions at the bottom. Controls fade out while playing and come back on any
 * tap or key press.
 */
@Composable
actual fun PlayerScreen(
    onBack: () -> Unit,
    onChangeSource: () -> Unit,
    onOpenEpisode: (Episode) -> Unit,
) {
    // What this player opened with. Moving on to the next episode clears the session's source
    // before the player has finished leaving; read live, that made the leaving player press
    // Back and drop the new episode's source list, landing on the title page.
    val selection = remember { PlaybackSession.selection }
    val source = remember { PlaybackSession.source }
    if (selection == null || (source?.url == null && source?.infoHash == null)) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val context = LocalContext.current
    val viewModel =
        viewModel(key = "player-${selection.videoId}-${source.url.hashCode()}") {
            val appContext = context.applicationContext
            val debuggable = appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            PlayerViewModel(AppGraph.core(context), selection, source) { options ->
                MpvPlayer.create(appContext, options.copy(verboseLogs = debuggable))
            }
        }
    // The video playing: another episode plays in this player, in place of the one it opened
    // with; only when its source cannot be found does the title's page show its sources.
    val playing = viewModel.selection
    val openEpisode: (Episode) -> Unit = { episode -> viewModel.openEpisode(episode) { onOpenEpisode(episode) } }
    val state by viewModel.player.state.collectAsStateWithLifecycle()
    val preparing by viewModel.preparing.collectAsStateWithLifecycle()
    val torrent by viewModel.torrent.collectAsStateWithLifecycle()
    val prepareFailed by viewModel.prepareFailed.collectAsStateWithLifecycle()
    val skips by viewModel.skips.collectAsStateWithLifecycle()
    val providerDownloading by viewModel.providerDownloading.collectAsStateWithLifecycle()
    val incomplete by viewModel.incomplete.collectAsStateWithLifecycle()
    val progressCounts by viewModel.progressCounts.collectAsStateWithLifecycle()
    // How far the skip buttons and the remote's fast-forward and rewind keys move.
    val seekStep = viewModel.seekSteps.step.toDouble()
    var controlsVisible by remember { mutableStateOf(true) }
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var scrubSeconds by remember { mutableStateOf<Double?>(null) }
    val poke = {
        controlsVisible = true
        lastInput = System.currentTimeMillis()
    }

    ImmersivePlayback()
    PlayerMediaSession(
        player = viewModel.player,
        title = playing.details.item.name,
        subtitle = playing.episode?.title,
        positionSeconds = state.positionSeconds,
        durationSeconds = state.durationSeconds,
        paused = state.isPaused,
        hasNext = playing.nextEpisode != null,
        onNext = { playing.nextEpisode?.let(openEpisode) },
        seekStepSeconds = seekStep,
    )
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
    // A panel open on the episode left closes with it.
    LaunchedEffect(playing.videoId) { panel = null }
    val next = playing.nextEpisode

    val rootFocus = remember { FocusRequester() }
    // Whenever nothing in the player has the focus (at first, or after the focused control or
    // prompt went away), the player itself takes it, so the remote keeps working.
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(hasFocus) { if (!hasFocus) runCatching { rootFocus.requestFocus() } }
    var promptFocused by remember { mutableStateOf(false) }
    val promptLift by animateDpAsState(if (controlsVisible) PromptControlsClearance else 0.dp, label = "prompt-lift")
    val isTv = LocalIsTv.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onFocusChanged { hasFocus = it.hasFocus }
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
                        viewModel.player.seekBy(seekStep)
                        true
                    }

                    Key.MediaRewind -> {
                        viewModel.player.seekBy(-seekStep)
                        true
                    }

                    else -> {
                        if (!controlsVisible && promptFocused && event.key in activationKeys) {
                            // OK on a skip or up-next prompt acts at once: the prompt shows what
                            // it does, and the controls stay out of the way.
                            return@onPreviewKeyEvent false
                        }
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
            if (providerDownloading) {
                ProviderDownloading(
                    logo = playing.details.item.logo,
                    title = playing.details.item.name,
                    onChangeSource = onChangeSource,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else if (preparing || !state.isLoaded) {
                // Until the first frame: the title's logo fills while the source gets ready.
                SnLogoLoader(
                    logo = playing.details.item.logo,
                    title = playing.details.item.name,
                    progress = null,
                    status = torrent?.let { torrentStatusText(it) },
                    label = stringResource(Res.string.player_preparing),
                    modifier = Modifier.align(Alignment.Center).padding(SnSpacing.space8),
                )
            } else if (state.isBuffering) {
                SnLoading(Modifier.align(Alignment.Center))
            }
        }

        AnimatedVisibility(controlsVisible && state.error == null, enter = fadeIn(), exit = fadeOut()) {
            Controls(
                selection = playing,
                state = state,
                scrubSeconds = scrubSeconds,
                hasNext = next != null,
                seekStep = viewModel.seekSteps.step,
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
                onNext = { next?.let(openEpisode) },
            )
        }

        if (state.error != null || prepareFailed) {
            PlaybackError(onChangeSource = onChangeSource, onBack = onBack, modifier = Modifier.align(Alignment.Center))
        } else if (incomplete != null) {
            IncompleteVideoPrompt(
                durationSeconds = incomplete ?: 0.0,
                onDiscard = { viewModel.answerIncomplete(save = false) },
                onSave = { viewModel.answerIncomplete(save = true) },
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .safeDrawingPadding()
                        .padding(SnSpacing.space8)
                        .padding(bottom = promptLift),
            )
        } else if (state.isLoaded) {
            PlaybackPrompts(
                state = state,
                skips = skips,
                // A video whose progress does not count (much shorter than expected) leads on to nothing.
                next = next.takeIf { progressCounts },
                onSeekTo = viewModel.player::seekTo,
                onOpenEpisode = openEpisode,
                upNext = viewModel.upNext,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .safeDrawingPadding()
                        .padding(SnSpacing.space8)
                        .padding(bottom = promptLift)
                        .onFocusChanged { promptFocused = it.hasFocus },
                // On TV, a prompt that appears while the controls are hidden takes the focus, so
                // OK skips. With the controls up, the viewer is using them; it stays reachable.
                takeFocus = isTv && !controlsVisible && panel == null,
            )
        }

        when (panel) {
            Panel.Tracks -> {
                SidePanel(stringResource(Res.string.player_tracks), onClose = { panel = null }) {
                    TracksList(
                        tracks = state.tracks,
                        onSelectAudio = viewModel.player::selectAudio,
                        onSelectSubtitle = viewModel.player::selectSubtitle,
                    )
                }
            }

            Panel.Episodes -> {
                SidePanel(stringResource(Res.string.player_episodes), onClose = { panel = null }) {
                    EpisodesList(playing, openEpisode)
                }
            }

            // Speed and picture are the desktop's for now.
            Panel.Playback, null -> {}
        }
    }
}

/** Keys that activate the focused element: a remote's OK and the keyboard's Enter. */
private val activationKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

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
    /** Seconds the skip buttons move. */
    seekStep: Int,
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
            SnIconButton(SnIcons.ChevronLeft, stringResource(Res.string.nav_back), onBack)
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
                            "${stringResource(Res.string.episode_short, season, number)} · ${episode.title}"
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
            SnIconButton(
                SnIcons.Back10,
                stringResource(Res.string.player_back_seconds, seekStep),
                { onSeekBy(-seekStep.toDouble()) },
            )
            val playLabel = stringResource(if (state.isPaused) Res.string.player_play else Res.string.player_pause)
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
            SnIconButton(
                SnIcons.Forward10,
                stringResource(Res.string.player_forward_seconds, seekStep),
                { onSeekBy(seekStep.toDouble()) },
            )
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
                    label = stringResource(Res.string.player_timeline),
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
                SnIconButton(SnIcons.Subtitles, stringResource(Res.string.player_tracks), onTracks)
                if (selection.details.episodes.isNotEmpty()) {
                    SnIconButton(SnIcons.Episodes, stringResource(Res.string.player_episodes), onEpisodes)
                }
                SnIconButton(SnIcons.Layers, stringResource(Res.string.player_change_source), onChangeSource)
                Box(Modifier.weight(1f))
                if (hasNext) SnIconButton(SnIcons.Next, stringResource(Res.string.player_next), onNext)
            }
        }
    }
}
