// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.affonsopaulo.stremionow.core.Episode
import io.github.affonsopaulo.stremionow.player.PlaybackState
import io.github.affonsopaulo.stremionow.player.PlayerError
import io.github.affonsopaulo.stremionow.player.VideoFormat
import io.github.affonsopaulo.stremionow.player.VideoPlayer
import io.github.affonsopaulo.stremionow.ui.components.SnButton
import io.github.affonsopaulo.stremionow.ui.components.SnIconButton
import io.github.affonsopaulo.stremionow.ui.components.SnLoading
import io.github.affonsopaulo.stremionow.ui.components.SnLogoLoader
import io.github.affonsopaulo.stremionow.ui.components.SnSeekBar
import io.github.affonsopaulo.stremionow.ui.components.formatPlaybackTime
import io.github.affonsopaulo.stremionow.ui.components.snClickable
import io.github.affonsopaulo.stremionow.ui.data.PlaybackSession
import io.github.affonsopaulo.stremionow.ui.desktop.display.DesktopDisplays
import io.github.affonsopaulo.stremionow.ui.desktop.display.DisplaySession
import io.github.affonsopaulo.stremionow.ui.desktop.display.HdrChoice
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.screens.CONTROLS_TIMEOUT_MS
import io.github.affonsopaulo.stremionow.ui.screens.EpisodesList
import io.github.affonsopaulo.stremionow.ui.screens.HdrPrompt
import io.github.affonsopaulo.stremionow.ui.screens.Panel
import io.github.affonsopaulo.stremionow.ui.screens.PlaybackError
import io.github.affonsopaulo.stremionow.ui.screens.PlaybackPrompts
import io.github.affonsopaulo.stremionow.ui.screens.PlayerViewModel
import io.github.affonsopaulo.stremionow.ui.screens.PromptControlsClearance
import io.github.affonsopaulo.stremionow.ui.screens.SidePanel
import io.github.affonsopaulo.stremionow.ui.screens.TracksList
import io.github.affonsopaulo.stremionow.ui.screens.torrentStatusText
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons
import io.github.affonsopaulo.stremionow.ui.theme.SnRadius
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Cursor
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage

/** Height kept clear of the bottom controls (timeline and buttons) for prompts above them. */
private val CONTROLS_CLEARANCE = 128.dp

/** How long it fades back in once restored, while the system's restore animation plays. */
private const val FADE_IN_MS = 250

private const val NANOS_PER_MILLI = 1_000_000L

/** Step of the ±10 s buttons and of the arrow keys. */
private const val SEEK_STEP_SECONDS = 10.0

/**
 * What the overlay shows besides the playback state: whether the controls are up, the open
 * panel and the position being scrubbed. Pointer movement and shortcuts call [poke], which
 * shows the controls and restarts the hide timer; the cursor hides along with them.
 */
@Stable
internal class OverlayState {
    var areControlsVisible by mutableStateOf(true)
        private set

    var lastActivity by mutableLongStateOf(System.nanoTime())
        private set

    var panel by mutableStateOf<Panel?>(null)

    var scrubSeconds by mutableStateOf<Double?>(null)

    /** The HDR question is showing. */
    var isAskingHdr by mutableStateOf(false)

    fun poke() {
        areControlsVisible = true
        lastActivity = System.nanoTime()
    }

    fun hide() {
        areControlsVisible = false
    }
}

/**
 * Space, K, Left, Right, J, L, F, N and Esc, wherever the focus is in the overlay. Esc (and the
 * mouse's back button) goes one step back with [playerBack]. N plays the next episode through
 * [onNext], when there is one.
 */
internal fun handlePlayerShortcut(
    event: KeyEvent,
    player: VideoPlayer,
    pair: WindowPair,
    state: OverlayState,
    onBack: () -> Unit,
    onNext: (() -> Unit)?,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    when (event.key) {
        Key.Spacebar, Key.K -> {
            player.togglePause()
        }

        Key.DirectionLeft, Key.J -> {
            player.seekBy(-SEEK_STEP_SECONDS)
        }

        Key.DirectionRight, Key.L -> {
            player.seekBy(SEEK_STEP_SECONDS)
        }

        Key.F -> {
            pair.toggleFullScreen()
        }

        Key.N -> {
            onNext?.invoke() ?: return false
        }

        Key.Escape -> {
            playerBack(pair, state, onBack)
        }

        // Compose moves the focus; the controls only need to be showing.
        Key.Tab -> {
            state.poke()
            return false
        }

        else -> {
            return false
        }
    }
    state.poke()
    return true
}

/** One step back: out of full screen, then out of an open panel, then out of the player. */
internal fun playerBack(
    pair: WindowPair,
    state: OverlayState,
    onBack: () -> Unit,
) {
    when {
        // Dismissing the HDR prompt changes nothing, as when it times out.
        state.isAskingHdr -> state.isAskingHdr = false

        pair.isFullScreen -> pair.exitFullScreen()

        state.panel != null -> state.panel = null

        else -> onBack()
    }
    state.poke()
}

/** Media keys and buttons (see [MediaKeys]): play and pause, next episode, back to the start. */
internal fun handleMediaCommand(
    command: MediaCommand,
    player: VideoPlayer,
    state: OverlayState,
    onNext: (() -> Unit)?,
) {
    when (command) {
        MediaCommand.PlayPause -> player.togglePause()
        MediaCommand.Play -> player.play()
        MediaCommand.Pause, MediaCommand.Stop -> player.pause()
        MediaCommand.Next -> onNext?.invoke()
        MediaCommand.Previous -> player.seekTo(0.0)
    }
    state.poke()
}

/**
 * The player's interface, drawn over the video in a transparent window: back, title and minimize
 * at the top, play/pause and ±10 s in the center, the timeline and the tracks, episodes, change
 * source, next episode and full screen buttons at the bottom. Areas without controls stay
 * transparent, so the video shows through. Click toggles play and double-click full screen. With
 * a borderless video window, dragging the video moves the window, edges resize it, and minimize
 * is a button here; a framed one has the system's title bar and borders for all three.
 */
@Composable
internal fun PlayerOverlay(
    viewModel: PlayerViewModel<VideoPlayer>,
    pair: WindowPair,
    state: OverlayState,
    display: DisplayChoices?,
    onBack: () -> Unit,
    onChangeSource: () -> Unit,
    onOpenEpisode: (Episode) -> Unit,
) {
    val player = viewModel.player
    val selection = viewModel.selection
    // Plain collection: this window's lifecycle is the app window's, which is hidden meanwhile.
    val playback by player.state.collectAsState()
    val preparing by viewModel.preparing.collectAsState()
    val torrent by viewModel.torrent.collectAsState()
    val prepareFailed by viewModel.prepareFailed.collectAsState()
    val skips by viewModel.skips.collectAsState()
    val promptLift by animateDpAsState(
        if (state.areControlsVisible) PromptControlsClearance else 0.dp,
        label = "prompt-lift",
    )
    val failed = prepareFailed || playback.error != null
    val next = selection.nextEpisode
    // Out before the pair minimizes, back in when it is restored.
    val overlayAlpha by animateFloatAsState(
        targetValue = if (pair.isFadingOut) 0f else 1f,
        animationSpec = tween(if (pair.isFadingOut) OVERLAY_FADE_OUT_MS else FADE_IN_MS),
        label = "overlay-fade",
    )

    // Once the first frame tells what the video is: match the display, or ask about HDR. Playback
    // goes on meanwhile, so the question never delays the start.
    val video = playback.video
    val scope = rememberCoroutineScope()
    LaunchedEffect(video, display) {
        if (video != null && display != null) state.isAskingHdr = display.onVideo(video)
    }

    LaunchedEffect(state.lastActivity, playback.isPaused, state.scrubSeconds, state.panel) {
        if (!playback.isPaused && state.scrubSeconds == null && state.panel == null) {
            delay(CONTROLS_TIMEOUT_MS)
            state.hide()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = overlayAlpha }
            .pointerHoverIcon(if (state.areControlsVisible || failed) PointerIcon.Default else HiddenCursor)
            .pointerInput(Unit) {
                // Sees every event before the children handle it, and consumes none. Positions are
                // compared by hand: hover moves on desktop report previousPosition == position.
                awaitPointerEventScope {
                    var lastPosition: Offset? = null
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val position = event.changes.firstOrNull()?.position
                        if (event.type == PointerEventType.Press ||
                            (event.type == PointerEventType.Move && position != lastPosition)
                        ) {
                            state.poke()
                        }
                        lastPosition = position
                    }
                }
            },
    ) {
        VideoArea(player, pair)

        if (!failed) {
            if (preparing || !playback.isLoaded) {
                // Until the first frame: the title's logo fills while the source gets ready.
                SnLogoLoader(
                    logo = selection.details.item.logo,
                    title = selection.details.item.name,
                    progress = null,
                    status = torrent?.let { torrentStatusText(it) },
                    label = stringResource(Res.string.player_preparing),
                    modifier = Modifier.align(Alignment.Center).padding(SnSpacing.space8),
                )
            } else if (playback.isBuffering) {
                SnLoading(Modifier.align(Alignment.Center))
            }
        }

        AnimatedVisibility(state.areControlsVisible && !failed, enter = fadeIn(), exit = fadeOut()) {
            Controls(
                selection = selection,
                playback = playback,
                scrubSeconds = state.scrubSeconds,
                isFullScreen = pair.isFullScreen,
                hasNext = next != null,
                onBack = onBack,
                onMinimize = if (pair.framed) null else pair::minimize,
                onToggleFullScreen = pair::toggleFullScreen,
                onTogglePause = player::togglePause,
                onSeekBy = player::seekBy,
                onSeekTo = player::seekTo,
                onScrub = { state.scrubSeconds = it },
                onTracks = { state.panel = Panel.Tracks },
                onEpisodes = { state.panel = Panel.Episodes },
                onChangeSource = onChangeSource,
                onNext = { next?.let(onOpenEpisode) },
            )
        }

        if (playback.error == PlayerError.PlayerUnavailable) {
            PlayerUnavailable(onBack = onBack, modifier = Modifier.align(Alignment.Center))
        } else if (failed) {
            PlaybackError(onChangeSource = onChangeSource, onBack = onBack, modifier = Modifier.align(Alignment.Center))
        } else if (state.isAskingHdr && display != null) {
            val answer = { choice: HdrChoice ->
                state.isAskingHdr = false
                scope.launch { display.answer(choice) }
                Unit
            }
            HdrPrompt(
                onTurnOn = { answer(HdrChoice.OnlyThisTitle) },
                onAlways = { answer(HdrChoice.Always) },
                onTimeout = { answer(HdrChoice.NotNow) },
                // Above the timeline and buttons while they show, in the corner otherwise.
                modifier =
                    Modifier.align(Alignment.BottomEnd).padding(
                        end = SnSpacing.space8,
                        bottom = if (state.areControlsVisible) CONTROLS_CLEARANCE else SnSpacing.space8,
                    ),
            )
        } else if (playback.isLoaded) {
            PlaybackPrompts(
                state = playback,
                skips = skips,
                next = next,
                onSeekTo = player::seekTo,
                onOpenEpisode = onOpenEpisode,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(SnSpacing.space8)
                        .padding(bottom = promptLift),
            )
        }

        when (state.panel) {
            Panel.Tracks -> {
                SidePanel(stringResource(Res.string.player_tracks), onClose = { state.panel = null }) {
                    TracksList(
                        tracks = playback.tracks,
                        onSelectAudio = player::selectAudio,
                        onSelectSubtitle = player::selectSubtitle,
                    )
                }
            }

            Panel.Episodes -> {
                SidePanel(stringResource(Res.string.player_episodes), onClose = { state.panel = null }) {
                    EpisodesList(selection, onOpenEpisode)
                }
            }

            null -> {}
        }

        if (!pair.isFullScreen && !pair.framed) {
            for (edge in ResizeEdge.entries) ResizeHandle(edge, pair)
        }
    }
}

/**
 * The player's link to [DisplaySession]: runs its blocking calls on the display queue, finds the
 * monitor the [video] window is on and tells mpv, through [onHdr], whether it may output HDR.
 */
internal class DisplayChoices(
    private val session: DisplaySession,
    private val video: java.awt.Window,
    private val onHdr: (Boolean) -> Unit,
) {
    /** See [DisplaySession.onVideo]; true when the user should be asked about HDR. */
    suspend fun onVideo(format: VideoFormat): Boolean {
        val target =
            DesktopDisplays.control?.let { control ->
                withContext(DesktopDisplays.dispatcher) { control.displayOf(video) }
            }
        val ask = withContext(DesktopDisplays.dispatcher) { session.onVideo(format, target) }
        applyHdr()
        return ask
    }

    suspend fun answer(choice: HdrChoice) {
        withContext(DesktopDisplays.dispatcher) { session.answer(choice) }
        applyHdr()
    }

    private fun applyHdr() = onHdr(session.isHdrActive)
}

/** libmpv could not be loaded: say so, and offer the way back (another source would not help). */
@Composable
private fun PlayerUnavailable(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.widthIn(max = 480.dp).padding(SnSpacing.space6),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SnSpacing.space3),
    ) {
        BasicText(
            stringResource(Res.string.player_unavailable_title),
            modifier = Modifier.semantics { heading() },
            style = SnTypography.headingMd.copy(color = SnColors.fg, textAlign = TextAlign.Center),
        )
        BasicText(
            stringResource(Res.string.player_unavailable_body),
            style = SnTypography.body.copy(color = SnColors.fgSecondary, textAlign = TextAlign.Center),
        )
        SnButton(stringResource(Res.string.nav_back), onBack)
    }
}

/**
 * The video itself: click to play or pause, double-click for full screen, and, with a borderless
 * video window, drag to move the window.
 *
 * A click plays or pauses at once, rather than after waiting to see whether a second click
 * follows; the second click of a double-click undoes it, so a double-click only changes full
 * screen.
 */
@Composable
private fun VideoArea(
    player: VideoPlayer,
    pair: WindowPair,
) {
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                var lastTap = 0L
                detectTapGestures(
                    onTap = {
                        val now = System.nanoTime()
                        player.togglePause()
                        if (now - lastTap <= viewConfiguration.doubleTapTimeoutMillis * NANOS_PER_MILLI) {
                            player.togglePause()
                            pair.toggleFullScreen()
                            lastTap = 0L
                        } else {
                            lastTap = now
                        }
                    },
                )
            }.pointerInput(pair.isFullScreen) {
                if (pair.isFullScreen || pair.framed) return@pointerInput
                detectDragGestures(
                    onDragStart = { pair.beginGesture(null) },
                    onDrag = { change, _ ->
                        change.consume()
                        pair.updateGesture()
                    },
                    onDragEnd = { pair.endGesture() },
                    onDragCancel = { pair.endGesture(cancelled = true) },
                )
            },
    )
}

@Composable
private fun Controls(
    selection: PlaybackSession.Selection,
    playback: PlaybackState,
    scrubSeconds: Double?,
    isFullScreen: Boolean,
    hasNext: Boolean,
    onBack: () -> Unit,
    /** Null when the window's title bar has the minimize button. */
    onMinimize: (() -> Unit)?,
    onToggleFullScreen: () -> Unit,
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
                // Keeps text and icons legible over bright video; the middle stays clear.
                Brush.verticalGradient(
                    0f to SnColors.scrim,
                    0.25f to Color.Transparent,
                    0.65f to Color.Transparent,
                    1f to SnColors.scrim,
                ),
            ).padding(horizontal = SnSpacing.space6, vertical = SnSpacing.space4),
    ) {
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
        ) {
            val backLabel = stringResource(Res.string.nav_back)
            WithTooltip(backLabel, stringResource(Res.string.player_key_escape), TooltipSide.Below) {
                SnIconButton(SnIcons.ChevronLeft, backLabel, onBack)
            }
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
            if (onMinimize != null) {
                val minimizeLabel = stringResource(Res.string.player_minimize)
                WithTooltip(minimizeLabel, side = TooltipSide.Below) {
                    SnIconButton(SnIcons.Minus, minimizeLabel, onMinimize)
                }
            }
        }

        Row(
            Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space10),
        ) {
            val back10Label = stringResource(Res.string.player_back10)
            WithTooltip(back10Label, stringResource(Res.string.player_key_left)) {
                SnIconButton(SnIcons.Back10, back10Label, { onSeekBy(-SEEK_STEP_SECONDS) })
            }
            val playLabel = stringResource(if (playback.isPaused) Res.string.player_play else Res.string.player_pause)
            WithTooltip(playLabel, stringResource(Res.string.player_key_space)) {
                Box(
                    Modifier
                        .size(72.dp)
                        .semantics { contentDescription = playLabel }
                        .snClickable(RoundedCornerShape(SnRadius.radiusFull), onClick = onTogglePause)
                        .background(SnColors.overlayLight, RoundedCornerShape(SnRadius.radiusFull)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        if (playback.isPaused) SnIcons.Play else SnIcons.Pause,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        colorFilter = ColorFilter.tint(SnColors.fg),
                    )
                }
            }
            val forward10Label = stringResource(Res.string.player_forward10)
            WithTooltip(forward10Label, stringResource(Res.string.player_key_right)) {
                SnIconButton(SnIcons.Forward10, forward10Label, { onSeekBy(SEEK_STEP_SECONDS) })
            }
        }

        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SnSpacing.space2),
        ) {
            val duration = playback.durationSeconds ?: 0.0
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
            ) {
                SnSeekBar(
                    positionSeconds = playback.positionSeconds,
                    durationSeconds = duration,
                    bufferedSeconds = playback.bufferedAheadSeconds,
                    label = stringResource(Res.string.player_timeline),
                    onSeek = onSeekTo,
                    onScrub = onScrub,
                    modifier = Modifier.weight(1f),
                )
                BasicText(
                    "${formatPlaybackTime(scrubSeconds ?: playback.positionSeconds)} / ${formatPlaybackTime(duration)}",
                    style = SnTypography.label.copy(color = SnColors.fg),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SnSpacing.space2),
            ) {
                val tracksLabel = stringResource(Res.string.player_tracks)
                WithTooltip(tracksLabel) { SnIconButton(SnIcons.Subtitles, tracksLabel, onTracks) }
                if (selection.details.episodes.isNotEmpty()) {
                    val episodesLabel = stringResource(Res.string.player_episodes)
                    WithTooltip(episodesLabel) { SnIconButton(SnIcons.Episodes, episodesLabel, onEpisodes) }
                }
                val sourceLabel = stringResource(Res.string.player_change_source)
                WithTooltip(sourceLabel) { SnIconButton(SnIcons.Layers, sourceLabel, onChangeSource) }
                Box(Modifier.weight(1f))
                if (hasNext) {
                    val nextLabel = stringResource(Res.string.player_next)
                    WithTooltip(nextLabel, stringResource(Res.string.player_key_next)) {
                        SnIconButton(SnIcons.Next, nextLabel, onNext)
                    }
                }
                val fullScreenLabel =
                    stringResource(
                        if (isFullScreen) Res.string.player_exit_full_screen else Res.string.player_full_screen,
                    )
                WithTooltip(fullScreenLabel, stringResource(Res.string.player_key_full_screen)) {
                    SnIconButton(
                        icon = if (isFullScreen) SnIcons.ExitFullScreen else SnIcons.FullScreen,
                        label = fullScreenLabel,
                        onClick = onToggleFullScreen,
                    )
                }
            }
        }
    }
}

/** An invisible strip along one edge (or a square at one corner) that resizes both windows. */
@Composable
private fun BoxScope.ResizeHandle(
    edge: ResizeEdge,
    pair: WindowPair,
) {
    val thickness = 6.dp
    val corner = 14.dp
    val placement =
        when (edge) {
            ResizeEdge.Left -> {
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(vertical = corner)
                    .width(thickness)
                    .fillMaxHeight()
            }

            ResizeEdge.Right -> {
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(vertical = corner)
                    .width(thickness)
                    .fillMaxHeight()
            }

            ResizeEdge.Top -> {
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = corner)
                    .height(thickness)
                    .fillMaxWidth()
            }

            ResizeEdge.Bottom -> {
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = corner)
                    .height(thickness)
                    .fillMaxWidth()
            }

            ResizeEdge.TopLeft -> {
                Modifier.align(Alignment.TopStart).size(corner)
            }

            ResizeEdge.TopRight -> {
                Modifier.align(Alignment.TopEnd).size(corner)
            }

            ResizeEdge.BottomLeft -> {
                Modifier.align(Alignment.BottomStart).size(corner)
            }

            ResizeEdge.BottomRight -> {
                Modifier.align(Alignment.BottomEnd).size(corner)
            }
        }
    Box(
        placement
            .pointerHoverIcon(PointerIcon(Cursor(edge.cursorType())))
            .pointerInput(edge) {
                trackPrimaryDrag(
                    onStart = { pair.beginGesture(edge) },
                    onMove = pair::updateGesture,
                    onEnd = { cancelled -> pair.endGesture(cancelled) },
                )
            },
    )
}

private fun ResizeEdge.cursorType(): Int =
    when (this) {
        ResizeEdge.Left -> Cursor.W_RESIZE_CURSOR
        ResizeEdge.Right -> Cursor.E_RESIZE_CURSOR
        ResizeEdge.Top -> Cursor.N_RESIZE_CURSOR
        ResizeEdge.Bottom -> Cursor.S_RESIZE_CURSOR
        ResizeEdge.TopLeft -> Cursor.NW_RESIZE_CURSOR
        ResizeEdge.TopRight -> Cursor.NE_RESIZE_CURSOR
        ResizeEdge.BottomLeft -> Cursor.SW_RESIZE_CURSOR
        ResizeEdge.BottomRight -> Cursor.SE_RESIZE_CURSOR
    }

/** An invisible cursor, shown while the controls are hidden. */
private val HiddenCursor: PointerIcon by lazy {
    val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(image, Point(0, 0), "hidden"))
}
