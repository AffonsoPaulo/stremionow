// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import io.github.affonsopaulo.stremionow.core.AddonInfo
import io.github.affonsopaulo.stremionow.core.StreamSource
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.ui.data.io
import kotlinx.coroutines.CancellationException

// Finding a source again from the addon that offered it, without asking every addon: the next
// episode's source while one plays, and the source chosen before when resuming. Addresses are
// never kept (debrid links carry the user's key), so the addon is asked again each time.

/** The core's calls that ask addons for sources, apart so the lookups can be tested. */
internal interface SourceFinder {
    /** Enabled addons that can offer sources for the video, in priority order. */
    suspend fun addons(
        kind: String,
        videoId: String,
    ): List<AddonInfo>

    /** The sources [addon] offers for the video. */
    suspend fun sources(
        addon: AddonInfo,
        kind: String,
        videoId: String,
    ): List<StreamSource>
}

internal fun StremioCore.sourceFinder(): SourceFinder =
    object : SourceFinder {
        override suspend fun addons(
            kind: String,
            videoId: String,
        ) = io { streamAddons(kind, videoId) }

        override suspend fun sources(
            addon: AddonInfo,
            kind: String,
            videoId: String,
        ) = streamsFrom(addon.manifestUrl, kind, videoId)
    }

/**
 * What recognizes a source among those of the addon whose manifest id is [addonId]: the same
 * source by its [identity], else the first one of the same [bingeGroup], as Stremio's next
 * episode does. Either may be missing.
 */
internal data class SourceWanted(
    val addonId: String,
    val identity: String? = null,
    val bingeGroup: String? = null,
)

/**
 * The source of [sources] that [wanted] describes, among those the app plays itself: the one of
 * the same identity, else the first of the same binge group. Identities of web sources are
 * digests of their address, so they stop matching when an addon signs its links anew; the binge
 * group still finds a source like it.
 */
internal fun pickSource(
    sources: List<StreamSource>,
    wanted: SourceWanted,
): StreamSource? {
    val playable = sources.filter { it.playsInApp }
    return wanted.identity?.let { identity -> playable.firstOrNull { it.identity == identity } }
        ?: wanted.bingeGroup?.let { group -> playable.firstOrNull { it.bingeGroup == group } }
}

/**
 * Asks the addon [wanted] names, found again by its manifest id, for the video's sources and
 * picks the one wanted. Null when the addon is no longer installed or enabled, fails, or has
 * none that matches: the caller then shows the video's sources.
 */
internal suspend fun SourceFinder.find(
    kind: String,
    videoId: String,
    wanted: SourceWanted,
): StreamSource? {
    val addon = orNull { addons(kind, videoId) }?.firstOrNull { it.id == wanted.addonId } ?: return null
    val sources = orNull { sources(addon, kind, videoId) } ?: return null
    return pickSource(sources, wanted)
}

/**
 * The result of [block], or null when it fails; cancelling, a time limit included, still
 * cancels. Failures are not logged: an addon's errors can quote its address, which may hold
 * the user's settings or keys.
 */
private suspend fun <T> orNull(block: suspend () -> T): T? =
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
