// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.affonsopaulo.stremionow.desktop.mpv.PlaybackState
import io.github.affonsopaulo.stremionow.desktop.window.ResizeEdge
import kotlinx.coroutines.delay
import java.awt.Cursor
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage

/** Controls hide after this long without pointer movement while playing. */
private const val CONTROLS_TIMEOUT_MS = 4_000L

/** Step of the ±10 s buttons and of the Left and Right keys. */
const val SEEK_STEP_SECONDS = 10.0

/**
 * Whether the controls are showing. Pointer movement and shortcuts call [poke], which shows them
 * and restarts the hide timer; the cursor hides along with them.
 *
 * Also tracks whether focus rings should show: only after keyboard navigation (Tab), not after a
 * click, like the web's `:focus-visible`.
 */
@Stable
class ControlsState {
    var isVisible by mutableStateOf(true)
        private set

    var isFocusRingVisible by mutableStateOf(false)
        private set

    internal var lastActivity by mutableLongStateOf(System.nanoTime())
        private set

    fun poke() {
        isVisible = true
        lastActivity = System.nanoTime()
    }

    /** The user moves focus with the keyboard. */
    fun onKeyboardNavigation() {
        isFocusRingVisible = true
        poke()
    }

    internal fun onPointerPress() {
        isFocusRingVisible = false
        poke()
    }

    internal fun hide() {
        isVisible = false
    }
}

/** What the overlay asks of the player and of the window pair. */
interface PlayerActions {
    fun togglePause()

    fun seekBy(seconds: Double)

    fun seekTo(seconds: Double)

    fun toggleFullScreen()

    fun minimize()

    fun close()

    /** Starts moving the windows with the pointer, or resizing them from [edge]. */
    fun beginWindowGesture(edge: ResizeEdge?)

    fun updateWindowGesture()

    /** Ends the window gesture; [cancelled] when Compose dropped it instead of the button being released. */
    fun endWindowGesture(cancelled: Boolean = false)
}

/**
 * The player's interface, drawn over the video in a transparent window: a top bar with back,
 * title and minimize, play/pause and ±10 s in the center, and a seek bar with time and full
 * screen at the bottom. Areas without controls stay transparent, so the video shows through.
 */
@Composable
fun PlayerOverlay(
    title: String,
    detail: String,
    state: PlaybackState,
    isFullScreen: Boolean,
    controls: ControlsState,
    actions: PlayerActions,
) {
    var scrubSeconds by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(controls.lastActivity, state.isPaused, scrubSeconds) {
        if (!state.isPaused && scrubSeconds == null) {
            delay(CONTROLS_TIMEOUT_MS)
            controls.hide()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerHoverIcon(if (controls.isVisible) PointerIcon.Default else HiddenCursor)
            .pointerInput(Unit) {
                // Sees every event before the children handle it, and consumes none. Positions are
                // compared by hand: hover moves on desktop report previousPosition == position.
                awaitPointerEventScope {
                    var lastPosition: Offset? = null
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val position = event.changes.firstOrNull()?.position
                        val moved = event.type == PointerEventType.Move && position != lastPosition
                        when {
                            event.type == PointerEventType.Press -> controls.onPointerPress()
                            moved -> controls.poke()
                        }
                        lastPosition = position
                    }
                }
            },
    ) {
        // The video area: click to play or pause, double-click for full screen, drag to move the window.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { actions.togglePause() },
                        onDoubleTap = { actions.toggleFullScreen() },
                    )
                }.pointerInput(isFullScreen) {
                    if (isFullScreen) return@pointerInput
                    detectDragGestures(
                        onDragStart = { actions.beginWindowGesture(null) },
                        onDrag = { change, _ ->
                            change.consume()
                            actions.updateWindowGesture()
                        },
                        onDragEnd = { actions.endWindowGesture() },
                        onDragCancel = { actions.endWindowGesture(cancelled = true) },
                    )
                },
        )

        when {
            state.error != null -> ErrorMessage(Modifier.align(Alignment.Center))
            !state.isLoaded || state.isBuffering -> Spinner(Modifier.align(Alignment.Center))
        }

        AnimatedVisibility(controls.isVisible, enter = fadeIn(), exit = fadeOut()) {
            Controls(
                title = title,
                detail = detail,
                state = state,
                isFullScreen = isFullScreen,
                showFocusRing = controls.isFocusRingVisible,
                scrubSeconds = scrubSeconds,
                onScrub = { scrubSeconds = it },
                actions = actions,
            )
        }

        if (!isFullScreen) {
            for (edge in ResizeEdge.entries) ResizeHandle(edge, actions)
        }
    }
}

@Composable
private fun Controls(
    title: String,
    detail: String,
    state: PlaybackState,
    isFullScreen: Boolean,
    showFocusRing: Boolean,
    scrubSeconds: Double?,
    onScrub: (Double?) -> Unit,
    actions: PlayerActions,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                // Keeps text and icons legible over bright video; the middle stays clear.
                Brush.verticalGradient(
                    0f to Colors.scrim,
                    0.25f to Color.Transparent,
                    0.65f to Color.Transparent,
                    1f to Colors.scrim,
                ),
            ).padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconButton(Icons.ChevronLeft, "Back", actions::close, showFocusRing)
            Column(Modifier.weight(1f)) {
                BasicText(
                    title,
                    modifier = Modifier.semantics { heading() },
                    style = TextStyle(color = Colors.fg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                BasicText(detail, style = TextStyle(color = Colors.fgSecondary, fontSize = 13.sp), maxLines = 1)
            }
            IconButton(Icons.Minus, "Minimize", actions::minimize, showFocusRing)
        }

        // Without a playable file only the top bar stays, so the error message is not covered.
        if (state.error == null) {
            Row(
                Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(40.dp),
            ) {
                IconButton(
                    Icons.Back10,
                    "Back 10 seconds (Left)",
                    { actions.seekBy(-SEEK_STEP_SECONDS) },
                    showFocusRing,
                )
                IconButton(
                    icon = if (state.isPaused) Icons.Play else Icons.Pause,
                    label = if (state.isPaused) "Play (Space)" else "Pause (Space)",
                    onClick = actions::togglePause,
                    showFocusRing = showFocusRing,
                    size = 72.dp,
                    iconSize = 32.dp,
                    background = Colors.overlayLight,
                )
                IconButton(
                    Icons.Forward10,
                    "Forward 10 seconds (Right)",
                    { actions.seekBy(SEEK_STEP_SECONDS) },
                    showFocusRing,
                )
            }

            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val duration = state.durationSeconds ?: 0.0
                SeekBar(
                    positionSeconds = state.positionSeconds,
                    durationSeconds = duration,
                    onSeek = actions::seekTo,
                    onScrub = onScrub,
                    modifier = Modifier.weight(1f),
                )
                BasicText(
                    "${formatPlaybackTime(scrubSeconds ?: state.positionSeconds)} / ${formatPlaybackTime(duration)}",
                    style = TextStyle(color = Colors.fg, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                )
                IconButton(
                    icon = if (isFullScreen) Icons.Minimize else Icons.Maximize,
                    label = if (isFullScreen) "Exit full screen (F)" else "Full screen (F)",
                    onClick = actions::toggleFullScreen,
                    showFocusRing = showFocusRing,
                )
            }
        }
    }
}

/** A round icon button with hover feedback, and a focus ring when [showFocusRing] allows it. */
@Composable
private fun IconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    showFocusRing: Boolean,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    background: Color = Color.Transparent,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            .size(size)
            .background(if (hovered) Colors.overlayLight.compositeOver(background) else background, CircleShape)
            .border(2.dp, if (focused && showFocusRing) Colors.accent else Color.Transparent, CircleShape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            icon,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            colorFilter = ColorFilter.tint(Colors.fg),
        )
    }
}

/** An invisible strip along one edge (or a square at one corner) that resizes both windows. */
@Composable
private fun BoxScope.ResizeHandle(
    edge: ResizeEdge,
    actions: PlayerActions,
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
                    onStart = { actions.beginWindowGesture(edge) },
                    onMove = actions::updateWindowGesture,
                    onEnd = { cancelled -> actions.endWindowGesture(cancelled) },
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

@Composable
private fun Spinner(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing), RepeatMode.Restart),
        label = "rotation",
    )
    Canvas(modifier.size(48.dp).semantics { contentDescription = "Loading" }) {
        val stroke = 4.dp.toPx()
        drawArc(
            color = Colors.fg,
            startAngle = rotation,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun ErrorMessage(modifier: Modifier = Modifier) {
    Column(
        modifier.widthIn(max = 480.dp).background(Colors.scrim, RoundedCornerShape(12.dp)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            "This video can't be played",
            style = TextStyle(color = Colors.fg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
        )
        // mpv's reason is technical ("loading failed"); it goes to the log, not to the screen.
        BasicText(
            "The address could not be opened. Check the connection, or go back and choose another source.",
            style = TextStyle(color = Colors.fgSecondary, fontSize = 14.sp, textAlign = TextAlign.Center),
        )
    }
}

/** An invisible cursor, shown while the controls are hidden. */
private val HiddenCursor: PointerIcon by lazy {
    val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(image, Point(0, 0), "hidden"))
}
