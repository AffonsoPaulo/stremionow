// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.affonsopaulo.stremionow.core.Episode
import io.github.affonsopaulo.stremionow.core.LastSource
import io.github.affonsopaulo.stremionow.core.MetaDetails
import io.github.affonsopaulo.stremionow.core.StreamSource
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.core.WatchProgress
import io.github.affonsopaulo.stremionow.ui.components.SnLogoLoader
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.Connectivity
import io.github.affonsopaulo.stremionow.ui.data.PlaybackSession
import io.github.affonsopaulo.stremionow.ui.data.io
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource
import io.github.affonsopaulo.stremionow.ui.platform.LocalPlatformContext
import io.github.affonsopaulo.stremionow.ui.theme.SnSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// "Resume" and the Continue watching cards go straight to the player with the source chosen
// before, as the official app does. The app never keeps addresses (debrid links carry the
// user's key), so the source is looked up again from its addon while the player's loading
// screen shows; when it cannot be found in a few seconds, the video's sources show instead.

/** How long the addon of the source chosen before has to answer before its list shows. */
internal const val RESUME_TIMEOUT_MS = 6_000L

/**
 * What finds [videoId]'s source from the one played last: the same source for the same video;
 * for another one (Continue watching moved on to the next episode once the last one was
 * finished), the first of the same binge group, as the next episode does. Null when nothing
 * could match: another video and no binge group.
 */
internal fun LastSource.wantedFor(videoId: String): SourceWanted? =
    when {
        this.videoId == videoId -> SourceWanted(addonId, identity, bingeGroup, addonKey)
        bingeGroup != null -> SourceWanted(addonId, bingeGroup = bingeGroup, addonKey = addonKey)
        else -> null
    }

/**
 * A resume of [episode] (null for a movie) from the title's page, or null when the source
 * chosen before could not find it, in which case the page shows its sources as before.
 */
internal fun resumeRequest(
    details: MetaDetails,
    episode: Episode?,
    last: LastSource?,
): PlaybackSession.Resume? {
    val videoId = episode?.id ?: details.item.id
    if (last?.wantedFor(videoId) == null) return null
    return PlaybackSession.Resume(details.item.kind, details.item.id, details.item.name, videoId, last, details)
}

/** A resume from a Continue watching card, or null when the card opens the title's page. */
internal fun resumeRequest(
    progress: WatchProgress,
    last: LastSource?,
): PlaybackSession.Resume? {
    if (last?.wantedFor(progress.videoId) == null) return null
    return PlaybackSession.Resume(progress.kind, progress.id, progress.name, progress.videoId, last, null)
}

/** How a resume ends. */
internal sealed interface ResumeResult {
    /** The source was found again: play it. */
    data class Play(
        val selection: PlaybackSession.Selection,
        val source: StreamSource,
    ) : ResumeResult

    /**
     * Show [selection]'s sources on the title's page, or only the page when the video could not
     * be told (the title's details failed, or the episode is gone).
     */
    data class Sources(
        val selection: PlaybackSession.Selection?,
    ) : ResumeResult
}

/**
 * Works out a resume: the title's details when [request] does not carry them, the video in
 * them, then its source from the addon of the source chosen before ([SourceWanted]). Anything
 * missing, an addon that fails or takes longer than [timeoutMs], and a torrent while
 * [torrentsAllowed] says no ("Only on Wi-Fi", whose question the source list asks) end with
 * the video's sources. It never fails.
 */
internal suspend fun resolveResume(
    request: PlaybackSession.Resume,
    finder: SourceFinder,
    loadDetails: suspend () -> MetaDetails,
    torrentsAllowed: suspend () -> Boolean,
    timeoutMs: Long = RESUME_TIMEOUT_MS,
): ResumeResult {
    val details =
        request.details ?: withTimeoutOrNull(timeoutMs) {
            try {
                loadDetails()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        } ?: return ResumeResult.Sources(null)
    val selection =
        if (details.episodes.isEmpty()) {
            PlaybackSession.Selection(details, null).takeIf { request.videoId == details.item.id }
        } else {
            details.episodes
                .firstOrNull { it.id == request.videoId }
                ?.let { PlaybackSession.Selection(details, it) }
        } ?: return ResumeResult.Sources(null)
    val wanted = request.last.wantedFor(request.videoId) ?: return ResumeResult.Sources(selection)
    val source =
        withTimeoutOrNull(timeoutMs) { finder.find(selection.kind, request.videoId, wanted) }
            ?: return ResumeResult.Sources(selection)
    if (source.url == null && source.infoHash != null && !torrentsAllowed()) return ResumeResult.Sources(selection)
    return ResumeResult.Play(selection, source)
}

/**
 * Resolves one [request] for the player's route; it lives with the route, so it survives the
 * phone turning. [onMobileData] tells whether the device is on mobile data now.
 */
class ResumeViewModel internal constructor(
    private val core: StremioCore,
    private val request: PlaybackSession.Resume,
    private val onMobileData: () -> Boolean,
) : ViewModel() {
    private val mutableDetails = MutableStateFlow(request.details)

    /** The title's details once known, for the logo on the loading screen. */
    val details = mutableDetails.asStateFlow()
    private val mutableResult = MutableStateFlow<ResumeResult?>(null)

    /** How the resume ended; null while it is looking. */
    internal val result = mutableResult.asStateFlow()

    init {
        viewModelScope.launch {
            mutableResult.value =
                resolveResume(
                    request = request,
                    finder = core.sourceFinder(),
                    loadDetails = { core.meta(request.kind, request.titleId).also { mutableDetails.value = it } },
                    torrentsAllowed = {
                        val wifiOnly = runCatching { io { core.settings().torrentWifiOnly } }.getOrDefault(false)
                        !(wifiOnly && onMobileData())
                    },
                )
        }
    }

    override fun onCleared() {
        // Left before it ended (Back): nothing is left to resume.
        if (PlaybackSession.resume === request) PlaybackSession.resume = null
    }
}

/**
 * The player's loading screen while [request]'s source is looked up again: the title's logo
 * filling, as when a source gets ready. Once found, the source goes into [PlaybackSession] and
 * [onPlay] shows the player; otherwise the title's page is asked to show the video's sources
 * and [onSources] returns to it.
 */
@Composable
fun ResumingPlayer(
    request: PlaybackSession.Resume,
    onPlay: () -> Unit,
    onSources: () -> Unit,
) {
    val context = LocalPlatformContext.current
    val viewModel =
        viewModel(key = "resume-${request.titleId}-${request.videoId}") {
            ResumeViewModel(AppGraph.core(context), request) { Connectivity.onMobileData(context) }
        }
    val details by viewModel.details.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    LaunchedEffect(result) {
        when (val ended = result) {
            null -> {}

            is ResumeResult.Play -> {
                PlaybackSession.select(ended.selection)
                PlaybackSession.source = ended.source
                PlaybackSession.alternatives = emptyList()
                onPlay()
            }

            is ResumeResult.Sources -> {
                PlaybackSession.resume = null
                ended.selection?.let {
                    PlaybackSession.select(it)
                    PlaybackSession.returnToSources()
                }
                onSources()
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        SnLogoLoader(
            logo = details?.item?.logo,
            title = details?.item?.name ?: request.name,
            progress = null,
            status = stringResource(Res.string.player_resuming),
            label = stringResource(Res.string.player_preparing),
            modifier = Modifier.align(Alignment.Center).padding(SnSpacing.space8),
        )
    }
}
