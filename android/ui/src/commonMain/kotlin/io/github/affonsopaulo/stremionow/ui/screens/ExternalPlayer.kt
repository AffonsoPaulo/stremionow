// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import io.github.affonsopaulo.stremionow.core.AppSettings
import io.github.affonsopaulo.stremionow.core.Episode
import io.github.affonsopaulo.stremionow.core.PlayerType
import io.github.affonsopaulo.stremionow.core.StreamSource
import io.github.affonsopaulo.stremionow.ui.components.SnButton
import io.github.affonsopaulo.stremionow.ui.components.SnButtonStyle
import io.github.affonsopaulo.stremionow.ui.components.SnDialogActions
import io.github.affonsopaulo.stremionow.ui.components.SnDialogFrame
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.platform.LocalPlatformContext
import io.github.affonsopaulo.stremionow.ui.platform.externalPlayerSupported
import io.github.affonsopaulo.stremionow.ui.platform.logWarning
import io.github.affonsopaulo.stremionow.ui.platform.openInExternalPlayer
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.github.affonsopaulo.stremionow.ui.theme.SnTypography
import kotlinx.coroutines.launch

/** Whether sources should open in another app: chosen in Settings, and possible here. */
internal fun AppSettings?.playsInOtherApp(): Boolean =
    this?.playerType == PlayerType.EXTERNAL && externalPlayerSupported()

/**
 * The torrent last handed to another app. Nothing tells the app when that app is done with it,
 * so it keeps downloading until the next one is handed over, which stops it, or the app quits.
 */
internal object ExternalPlayback {
    @Volatile var torrent: String? = null
}

private enum class ExternalState { Preparing, Failed, NoApp }

/**
 * Gets [source] ready on the local server and hands its address to another app, with the
 * system's chooser; meanwhile a dialog says so and offers Cancel. The address is always the
 * local server's (`playbackUrl`), so the other app never sees an addon's or debrid's address.
 * [onDone] runs once the other app has it, or the user gave up.
 */
@Composable
internal fun ExternalPlayerDialog(
    source: StreamSource,
    title: String,
    episode: Episode?,
    onDone: () -> Unit,
) {
    val context = LocalPlatformContext.current
    val currentOnDone by rememberUpdatedState(onDone)
    var state by remember(source) { mutableStateOf(ExternalState.Preparing) }
    val chooserTitle = stringResource(Res.string.external_chooser)
    val shownTitle =
        listOfNotNull(
            title,
            episode?.let { e ->
                val season = e.season?.toInt()
                val number = e.episode?.toInt()
                if (season != null && number != null) stringResource(Res.string.episode_short, season, number) else null
            },
        ).joinToString(" · ")
    val core = remember(context) { AppGraph.core(context) }
    LaunchedEffect(source) {
        val hash = source.torrentHash()
        ExternalPlayback.torrent?.takeIf { it != hash }?.let { previous ->
            ExternalPlayback.torrent = null
            AppGraph.scope.launch { runCatching { core.stopTorrent(previous) } }
        }
        val url =
            runCatching { core.playbackUrl(source) }
                .onFailure { logWarning("Could not prepare the source for another app", it) }
                .getOrNull()
        state =
            when {
                url == null -> {
                    ExternalState.Failed
                }

                openInExternalPlayer(context, url, shownTitle, chooserTitle) -> {
                    ExternalPlayback.torrent = hash
                    currentOnDone()
                    return@LaunchedEffect
                }

                else -> {
                    ExternalState.NoApp
                }
            }
    }
    // Only before the other app has it: the torrent started for nothing, so stop it, as the
    // player does when it closes.
    val cancel = {
        source.torrentHash()?.let { hash -> AppGraph.scope.launch { runCatching { core.stopTorrent(hash) } } }
        currentOnDone()
    }
    val buttonFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { buttonFocus.requestFocus() } }
    SnDialogFrame(title = stringResource(Res.string.external_title), onDismiss = cancel) {
        BasicText(
            stringResource(
                when (state) {
                    ExternalState.Preparing -> Res.string.player_preparing
                    ExternalState.Failed -> Res.string.external_failed
                    ExternalState.NoApp -> Res.string.external_no_app
                },
            ),
            style =
                SnTypography.body.copy(
                    color =
                        if (state ==
                            ExternalState.Preparing
                        ) {
                            SnColors.fgSecondary
                        } else {
                            SnColors.danger
                        },
                ),
        )
        SnDialogActions {
            SnButton(
                stringResource(
                    if (state ==
                        ExternalState.Preparing
                    ) {
                        Res.string.action_cancel
                    } else {
                        Res.string.action_close
                    },
                ),
                cancel,
                style = SnButtonStyle.Ghost,
                modifier = Modifier.focusRequester(buttonFocus),
            )
        }
    }
}
