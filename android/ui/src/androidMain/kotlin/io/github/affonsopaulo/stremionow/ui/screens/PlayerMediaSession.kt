// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import io.github.affonsopaulo.stremionow.player.MpvPlayer

/**
 * Publishes playback to Android while the player is open, so headset and Bluetooth buttons,
 * TV remotes' media keys, Google Assistant and the system media controls can drive it.
 * Fast-forward and rewind move by [seekStepSeconds], the skip buttons' step.
 */
@Composable
fun PlayerMediaSession(
    player: MpvPlayer,
    title: String,
    subtitle: String?,
    positionSeconds: Double,
    durationSeconds: Double?,
    paused: Boolean,
    hasNext: Boolean,
    onNext: () -> Unit,
    seekStepSeconds: Double,
) {
    val step by rememberUpdatedState(seekStepSeconds)
    val context = LocalContext.current
    val session = remember { MediaSession(context, "StremioNow") }
    DisposableEffect(session) {
        session.setCallback(
            object : MediaSession.Callback() {
                override fun onPlay() = player.play()

                override fun onPause() = player.pause()

                override fun onSeekTo(position: Long) = player.seekTo(position / 1000.0)

                override fun onFastForward() = player.seekBy(step)

                override fun onRewind() = player.seekBy(-step)

                override fun onSkipToNext() = onNext()
            },
        )
        session.isActive = true
        onDispose {
            session.isActive = false
            session.release()
        }
    }
    LaunchedEffect(session, title, subtitle, durationSeconds) {
        session.setMetadata(
            MediaMetadata
                .Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .apply { subtitle?.let { putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, it) } }
                .putLong(MediaMetadata.METADATA_KEY_DURATION, ((durationSeconds ?: 0.0) * 1000).toLong())
                .build(),
        )
    }
    // Position only changes the published state every few seconds; others extrapolate it.
    val coarsePosition = (positionSeconds / POSITION_STEP_SECONDS).toLong()
    LaunchedEffect(session, paused, coarsePosition, hasNext) {
        var actions =
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND
        if (hasNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        session.setPlaybackState(
            PlaybackState
                .Builder()
                .setActions(actions)
                .setState(
                    if (paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING,
                    (positionSeconds * 1000).toLong(),
                    if (paused) 0f else 1f,
                ).build(),
        )
    }
}

private const val POSITION_STEP_SECONDS = 5.0
