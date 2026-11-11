// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.runtime.Composable
import io.github.affonsopaulo.stremionow.core.Episode

/**
 * Plays the source picked in [io.github.affonsopaulo.stremionow.ui.data.PlaybackSession] with
 * HBO Max–style controls. Android draws the video and the controls in the app's window; the
 * desktop opens its two-window player (docs/adr/0005-desktop-ui.md). Both share
 * [PlayerViewModel] and the parts in PlayerParts.kt.
 *
 * [onBack] leaves the player, [onChangeSource] returns to the source picker and [onOpenEpisode]
 * moves on to another episode of the same series.
 */
@Composable
expect fun PlayerScreen(
    onBack: () -> Unit,
    onChangeSource: () -> Unit,
    onOpenEpisode: (Episode) -> Unit,
)
