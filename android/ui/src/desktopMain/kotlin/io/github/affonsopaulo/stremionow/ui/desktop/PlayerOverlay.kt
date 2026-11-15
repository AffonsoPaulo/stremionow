// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.affonsopaulo.stremionow.core.Episode
import io.github.affonsopaulo.stremionow.player.Chapters
import io.github.affonsopaulo.stremionow.player.PlaybackPrompt
import io.github.affonsopaulo.stremionow.player.PlaybackState
import io.github.affonsopaulo.stremionow.player.PlayerError
import io.github.affonsopaulo.stremionow.player.VideoFormat
import io.github.affonsopaulo.stremionow.player.VideoPlayer
import io.github.affonsopaulo.stremionow.ui.components.SnButton
import io.github.affonsopaulo.stremionow.ui.components.SnIconButton
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
import io.github.affonsopaulo.stremionow.ui.screens.OpeningStage
import io.github.affonsopaulo.stremionow.ui.screens.Panel
import io.github.affonsopaulo.stremionow.ui.screens.PlaybackError
import io.github.affonsopaulo.stremionow.ui.screens.PlaybackPrompts
import io.github.affonsopaulo.stremionow.ui.screens.PlayerViewModel
import io.github.affonsopaulo.stremionow.ui.screens.SeekSteps
import io.github.affonsopaulo.stremionow.ui.screens.SidePanel
import io.github.affonsopaulo.stremionow.ui.screens.TracksList
import io.github.affonsopaulo.stremionow.ui.screens.rememberLasting
import io.github.affonsopaulo.stremionow.ui.screens.rememberOpeningFill
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

/** Where the subtitles and audio panel sits: just above the row of buttons. */
private val TRACKS_PANEL_BOTTOM = 100.dp

/** Height kept clear of the bottom controls (timeline and buttons) for prompts above them. */
private val CONTROLS_CLEARANCE = 190.dp

/** How long it fades back in once restored, while the system's restore animation plays. */
private const val FADE_IN_MS = 250

private const val NANOS_PER_MILLI = 1_000_000L

/** How long a wait for data lasts before the loading logo shows over the video. */
private const val WAIT_DELAY_MS = 500L

/** How long the opening logo takes to fade once the video shows. */
private const val OPENING_FADE_MS = 200

/** Height of the shades behind the top and bottom controls. */
private val TOP_SCRIM = 160.dp
private val BOTTOM_SCRIM = 300.dp

/** The play/pause button, larger than the others. */
private val PLAY_BUTTON = 68.dp

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

    /** Where "Skip intro" (recap, credits) on screen would go, for the S key; null when none shows. */
    var skipTarget by mutableStateOf<Double?>(null)

    fun poke() {
        areControlsVisible = true
        lastActivity = System.nanoTime()
    }

    fun hide() {
        areControlsVisible = false
    }
}

/**
 * Space, K, Left, Right, J, L, Up, Down, M, F, N, E, S and Esc, wherever the focus is in the overlay.
 * Left and Right (and J and L) move by [seek]'s step, Shift with Left and Right by its shorter
 * one. S skips the intro, recap or credits whose prompt shows. Esc leaves full screen first unless
 * [escLeavesFullScreen] is off (Settings), in which case it steps back as out of full screen.
 * Up and Down change the volume and M mutes it. Esc (and the
 * mouse's back button) goes one step back with [playerBack]. N plays the next episode through
 * [onNext], when there is one; E opens and closes the episodes, when the title has them.
 */
internal fun handlePlayerShortcut(
    event: KeyEvent,
    player: VideoPlayer,
    pair: WindowPair,
    state: OverlayState,
    onBack: () -> Unit,
    onNext: (() -> Unit)?,
    hasEpisodes: Boolean = false,
    seek: SeekSteps = SeekSteps.DEFAULT,
    escLeavesFullScreen: Boolean = true,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    when (event.key) {
        Key.Spacebar, Key.K -> {
            player.togglePause()
        }

        Key.DirectionLeft -> {
            player.seekBy(-seek.forArrow(event.isShiftPressed))
        }

        Key.DirectionRight -> {
            player.seekBy(seek.forArrow(event.isShiftPressed))
        }

        Key.J -> {
            player.seekBy(-seek.forArrow(shift = false))
        }

        Key.L -> {
            player.seekBy(seek.forArrow(shift = false))
        }

        Key.F -> {
            pair.toggleFullScreen()
        }

        Key.N -> {
            onNext?.invoke() ?: return false
        }

        Key.DirectionUp -> {
            SessionVolume.set(player, player.state.value.volume + VOLUME_STEP)
        }

        Key.DirectionDown -> {
            SessionVolume.set(player, player.state.value.volume - VOLUME_STEP)
        }

        Key.M -> {
            SessionVolume.setMuted(player, !player.state.value.isMuted)
        }

        Key.S -> {
            val target = state.skipTarget ?: return false
            player.seekTo(target)
        }

        Key.E -> {
            if (!hasEpisodes) return false
            state.panel = if (state.panel == Panel.Episodes) null else Panel.Episodes
        }

        Key.Escape -> {
            playerBack(pair, state, onBack, leavesFullScreen = escLeavesFullScreen)
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

/** What one step back does in the player, in this order. */
internal enum class BackStep { DismissHdr, LeaveFullScreen, ClosePanel, Leave }

/**
 * The next step back: the HDR question, then full screen (unless [leavesFullScreen] is off),
 * then an open panel, then the player itself.
 */
internal fun backStep(
    askingHdr: Boolean,
    fullScreen: Boolean,
    panelOpen: Boolean,
    leavesFullScreen: Boolean = true,
): BackStep =
    when {
        askingHdr -> BackStep.DismissHdr
        fullScreen && leavesFullScreen -> BackStep.LeaveFullScreen
        panelOpen -> BackStep.ClosePanel
        else -> BackStep.Leave
    }

/**
 * One step back (see [backStep]). The mouse's back button always leaves full screen first;
 * Esc does unless Settings says otherwise ([leavesFullScreen]).
 */
internal fun playerBack(
    pair: WindowPair,
    state: OverlayState,
    onBack: () -> Unit,
    leavesFullScreen: Boolean = true,
) {
    when (backStep(state.isAskingHdr, pair.isFullScreen, state.panel != null, leavesFullScreen)) {
        // Dismissing the HDR prompt changes nothing, as when it times out.
        BackStep.DismissHdr -> state.isAskingHdr = false

        BackStep.LeaveFullScreen -> pair.exitFullScreen()

        BackStep.ClosePanel -> state.panel = null

        BackStep.Leave -> onBack()
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
 * at the top, play/pause and the skips back and forward in the center, the timeline and the
 * tracks, episodes, change source, next episode and full screen buttons at the bottom. Areas
 * without controls stay transparent, so the video shows through. Click toggles play and
 * double-click full screen. With
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
    val watched by viewModel.watched.collectAsState()
    val promptLift by animateDpAsState(
        if (state.areControlsVisible) CONTROLS_CLEARANCE - SnSpacing.space8 else 0.dp,
        label = "prompt-lift",
    )
    val failed = prepareFailed || playback.error != null
    val next = selection.nextEpisode
    // The prompt on screen now, so S can skip what it offers to skip.
    val markers =
        remember(playback.chapters, playback.durationSeconds, skips) {
            Chapters.markers(playback.chapters, playback.durationSeconds).orElse(skips)
        }
    val prompt =
        PlaybackPrompt.at(markers, playback.positionSeconds, playback.durationSeconds, next != null, playback.isEnded)
    SideEffect { state.skipTarget = (prompt as? PlaybackPrompt.Skip)?.toSeconds }
    // The intro, recap and credits, from the file's chapters or else AniSkip, marked on the timeline.
    val highlights =
        remember(playback.chapters, playback.durationSeconds, skips) {
            val markers = Chapters.markers(playback.chapters, playback.durationSeconds).orElse(skips)
            listOfNotNull(markers.recap, markers.intro, markers.credits).map { it.startSeconds..it.endSeconds }
        }
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
            val opening = preparing || !playback.isLoaded
            // Waiting for data after the start (a stall, a seek outside what is buffered), long
            // enough to notice: seeks inside the buffer take up to half a second.
            val waiting = rememberLasting(!opening && (playback.isBuffering || playback.isSeeking), WAIT_DELAY_MS)
            // Until the first frame: the title's logo fills from left to right as opening goes
            // on, and completes as the video appears.
            AnimatedVisibility(
                visible = opening,
                modifier = Modifier.align(Alignment.Center),
                enter = EnterTransition.None,
                exit = fadeOut(tween(OPENING_FADE_MS)),
            ) {
                SnLogoLoader(
                    logo = selection.details.item.logo,
                    title = selection.details.item.name,
                    progress = rememberOpeningFill(OpeningStage.of(preparing, playback.isLoaded)),
                    status = torrent?.let { torrentStatusText(it) },
                    label = stringResource(Res.string.player_preparing),
                    modifier = Modifier.padding(SnSpacing.space8),
                )
            }
            // The same logo over the last frame, dimmed: filled by what mpv reports while it
            // waits for data, or moving on its own during a seek, which reports nothing.
            AnimatedVisibility(visible = waiting, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(SnColors.scrim), contentAlignment = Alignment.Center) {
                    val moving = rememberOpeningFill(OpeningStage.OPENING)
                    SnLogoLoader(
                        logo = selection.details.item.logo,
                        title = selection.details.item.name,
                        progress = playback.bufferingProgress?.takeIf { playback.isBuffering } ?: moving,
                        status = null,
                        label = stringResource(Res.string.player_waiting),
                        modifier = Modifier.padding(SnSpacing.space8),
                    )
                }
            }
        }

        AnimatedVisibility(
            state.areControlsVisible && !failed && state.panel != Panel.Episodes,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Controls(
                player = player,
                selection = selection,
                playback = playback,
                scrubSeconds = state.scrubSeconds,
                highlights = highlights,
                isFullScreen = pair.isFullScreen,
                hasNext = next != null,
                seekStep = viewModel.seekSteps.step,
                onBack = onBack,
                onMinimize = if (pair.framed) null else pair::minimize,
                onToggleFullScreen = pair::toggleFullScreen,
                onTogglePause = player::togglePause,
                onSeekBy = player::seekBy,
                onSeekTo = player::seekTo,
                onScrub = { state.scrubSeconds = it },
                tracksOpen = state.panel == Panel.Tracks,
                playbackOpen = state.panel == Panel.Playback,
                onPlayback = { state.panel = if (state.panel == Panel.Playback) null else Panel.Playback },
                onTracks = { state.panel = if (state.panel == Panel.Tracks) null else Panel.Tracks },
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
                upNext = viewModel.upNext,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(SnSpacing.space8)
                        .padding(bottom = promptLift),
            )
        }

        when (state.panel) {
            Panel.Tracks -> {
                // A click anywhere else closes the panel, rather than pausing the video.
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures { state.panel = null } },
                )
                TracksPanel(
                    player = player,
                    playback = playback,
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = SnSpacing.space8, bottom = TRACKS_PANEL_BOTTOM),
                )
            }

            Panel.Playback -> {
                // A click anywhere else closes the panel, rather than pausing the video.
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures { state.panel = null } },
                )
                PlaybackPanel(
                    player = player,
                    playback = playback,
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = SnSpacing.space8, bottom = TRACKS_PANEL_BOTTOM),
                )
            }

            Panel.Episodes -> {
                // A click above the strip closes it, rather than pausing the video.
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures { state.panel = null } },
                )
                EpisodesStrip(
                    selection = selection,
                    playback = playback,
                    watched = watched,
                    onOpenEpisode = onOpenEpisode,
                    onClose = { state.panel = null },
                    modifier = Modifier.align(Alignment.BottomStart),
                )
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

/**
 * The controls, laid out as in the design canvas: Back at the top; at the bottom the title and
 * episode, the timeline between the current time and the length, then a row with the episodes
 * and next episode on the left, skip back, play/pause and skip forward in the centre, and change
 * source, volume, subtitles and audio, playback (speed and picture) and full screen on the right.
 */
@Composable
private fun Controls(
    player: VideoPlayer,
    selection: PlaybackSession.Selection,
    playback: PlaybackState,
    scrubSeconds: Double?,
    highlights: List<ClosedFloatingPointRange<Double>>,
    isFullScreen: Boolean,
    hasNext: Boolean,
    /** Seconds the skip buttons move. */
    seekStep: Int,
    onBack: () -> Unit,
    /** Null when the window's title bar has the minimize button. */
    onMinimize: (() -> Unit)?,
    onToggleFullScreen: () -> Unit,
    onTogglePause: () -> Unit,
    onSeekBy: (Double) -> Unit,
    onSeekTo: (Double) -> Unit,
    onScrub: (Double?) -> Unit,
    tracksOpen: Boolean,
    onTracks: () -> Unit,
    playbackOpen: Boolean,
    onPlayback: () -> Unit,
    onEpisodes: () -> Unit,
    onChangeSource: () -> Unit,
    onNext: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // Keep text and icons legible over bright video; the middle stays clear.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(TOP_SCRIM)
                .background(Brush.verticalGradient(listOf(SnColors.scrim, Color.Transparent))),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(BOTTOM_SCRIM)
                .background(Brush.verticalGradient(listOf(Color.Transparent, SnColors.scrim))),
        )

        Row(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(horizontal = SnSpacing.space8, vertical = SnSpacing.space6),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SnSpacing.space3),
        ) {
            val backLabel = stringResource(Res.string.nav_back)
            WithTooltip(backLabel, stringResource(Res.string.player_key_escape), TooltipSide.Below) {
                SnIconButton(SnIcons.ChevronLeft, backLabel, onBack)
            }
            Box(Modifier.weight(1f))
            if (onMinimize != null) {
                val minimizeLabel = stringResource(Res.string.player_minimize)
                WithTooltip(minimizeLabel, side = TooltipSide.Below) {
                    SnIconButton(SnIcons.Minus, minimizeLabel, onMinimize)
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = SnSpacing.space8, end = SnSpacing.space8, bottom = SnSpacing.space6),
            verticalArrangement = Arrangement.spacedBy(SnSpacing.space3),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(SnSpacing.space1)) {
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
                            "${stringResource(Res.string.episode_short, season, number)}: ${episode.title}"
                        } else {
                            episode.title
                        }
                    BasicText(
                        label,
                        style = SnTypography.bodyLg.copy(color = SnColors.fgSecondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            val duration = playback.durationSeconds ?: 0.0
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SnSpacing.space4),
            ) {
                val timeStyle = SnTypography.label.copy(color = SnColors.fg, fontFeatureSettings = "tnum")
                // Both times take the width of the longest time this video can show, so the
                // timeline between them never moves or resizes as the digits change: a click
                // would otherwise land a few pixels away from where the thumb ends up.
                val timeWidth = rememberWidestTimeWidth(duration, timeStyle)
                BasicText(
                    formatPlaybackTime(scrubSeconds ?: playback.positionSeconds),
                    modifier = Modifier.width(timeWidth),
                    style = timeStyle.copy(textAlign = TextAlign.Center),
                )
                SnSeekBar(
                    positionSeconds = playback.positionSeconds,
                    durationSeconds = duration,
                    bufferedSeconds = playback.bufferedAheadSeconds,
                    label = stringResource(Res.string.player_timeline),
                    onSeek = onSeekTo,
                    onScrub = onScrub,
                    highlights = highlights,
                    showsHoverTime = true,
                    modifier = Modifier.weight(1f),
                )
                BasicText(
                    formatPlaybackTime(duration),
                    modifier = Modifier.width(timeWidth),
                    style = timeStyle.copy(textAlign = TextAlign.Center),
                )
            }

            Box(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.align(Alignment.CenterStart),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SnSpacing.space2),
                ) {
                    if (selection.details.episodes.isNotEmpty()) {
                        val episodesLabel = stringResource(Res.string.player_episodes)
                        WithTooltip(episodesLabel, stringResource(Res.string.player_key_episodes)) {
                            SnIconButton(SnIcons.Episodes, episodesLabel, onEpisodes)
                        }
                    }
                    if (hasNext) {
                        val nextLabel = stringResource(Res.string.player_next)
                        WithTooltip(nextLabel, stringResource(Res.string.player_key_next)) {
                            SnIconButton(SnIcons.Next, nextLabel, onNext)
                        }
                    }
                }

                Row(
                    Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SnSpacing.space6),
                ) {
                    val backLabel = stringResource(Res.string.player_back_seconds, seekStep)
                    WithTooltip(backLabel, stringResource(Res.string.player_key_left)) {
                        SnIconButton(SnIcons.Back10, backLabel, { onSeekBy(-seekStep.toDouble()) })
                    }
                    val playLabel =
                        stringResource(if (playback.isPaused) Res.string.player_play else Res.string.player_pause)
                    WithTooltip(playLabel, stringResource(Res.string.player_key_space)) {
                        Box(
                            Modifier
                                .size(PLAY_BUTTON)
                                .semantics { contentDescription = playLabel }
                                .snClickable(RoundedCornerShape(SnRadius.radiusFull), onClick = onTogglePause)
                                .background(SnColors.fg, RoundedCornerShape(SnRadius.radiusFull)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                if (playback.isPaused) SnIcons.Play else SnIcons.Pause,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                colorFilter = ColorFilter.tint(SnColors.bg),
                            )
                        }
                    }
                    val forwardLabel = stringResource(Res.string.player_forward_seconds, seekStep)
                    WithTooltip(forwardLabel, stringResource(Res.string.player_key_right)) {
                        SnIconButton(SnIcons.Forward10, forwardLabel, { onSeekBy(seekStep.toDouble()) })
                    }
                }

                Row(
                    Modifier.align(Alignment.CenterEnd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SnSpacing.space2),
                ) {
                    val sourceLabel = stringResource(Res.string.player_change_source)
                    WithTooltip(sourceLabel) { SnIconButton(SnIcons.Layers, sourceLabel, onChangeSource) }
                    VolumeButton(player, playback)
                    val tracksLabel = stringResource(Res.string.player_tracks)
                    WithTooltip(tracksLabel) {
                        SnIconButton(
                            SnIcons.Subtitles,
                            tracksLabel,
                            onTracks,
                            background = if (tracksOpen) SnColors.overlayLight else Color.Transparent,
                        )
                    }
                    val playbackLabel = stringResource(Res.string.player_playback)
                    WithTooltip(playbackLabel) {
                        SnIconButton(
                            SnIcons.Gear,
                            playbackLabel,
                            onPlayback,
                            background = if (playbackOpen) SnColors.overlayLight else Color.Transparent,
                        )
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
}

/**
 * The width of the widest time [formatPlaybackTime] gives for a video of [durationSeconds], in
 * [style]: its pattern with every digit set to the font's widest digit.
 */
@Composable
private fun rememberWidestTimeWidth(
    durationSeconds: Double,
    style: TextStyle,
): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(durationSeconds, style, density) {
        val pattern = formatPlaybackTime(durationSeconds)
        val widest =
            (0..9).maxOf { digit ->
                measurer.measure(pattern.replace(Regex("[0-9]"), digit.toString()), style).size.width
            }
        with(density) { widest.toDp() }
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
