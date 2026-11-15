// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import io.github.affonsopaulo.stremionow.core.AddonInfo
import io.github.affonsopaulo.stremionow.core.StreamSource
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.ui.data.io
import io.github.affonsopaulo.stremionow.ui.platform.DebugLog
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
 * episode does. Either may be missing. [addonKey] names the install of that addon (two
 * installs of Torrentio, with and without a debrid service, share the id), when known.
 */
internal data class SourceWanted(
    val addonId: String,
    val identity: String? = null,
    val bingeGroup: String? = null,
    val addonKey: String? = null,
)

/**
 * The source of [sources] that [wanted] describes, among those the app plays itself: the one of
 * the same identity, else the first of the same binge group. Identities of web sources are
 * digests of their address, so they stop matching when an addon signs its links anew; the binge
 * group still finds a source like it.
 *
 * Among those of the binge group, one already in its debrid service's cache comes first: debrid
 * addons give the ones still to download the same group, further down. One of those is taken
 * only when there is nothing else, and the player then waits for the provider (PlayerViewModel).
 */
internal fun pickSource(
    sources: List<StreamSource>,
    wanted: SourceWanted,
): StreamSource? {
    val playable = sources.filter { it.playsInApp }
    return wanted.identity?.let { identity -> playable.firstOrNull { it.identity == identity } }
        ?: wanted.bingeGroup?.let { group ->
            val sameGroup = playable.filter { it.bingeGroup == group }
            sameGroup.firstOrNull { it.cached != false } ?: sameGroup.firstOrNull()
        }
}

/**
 * The addon of [installed] that [wanted] names: the install of [SourceWanted.addonKey], else
 * the only one with its manifest id. Null when the install is gone and several share the id:
 * guessing could ask an install without the debrid service, whose torrents may have no peers.
 */
internal fun pickAddon(
    installed: List<AddonInfo>,
    wanted: SourceWanted,
): AddonInfo? =
    wanted.addonKey?.let { key -> installed.firstOrNull { it.key == key } }
        ?: installed.filter { it.id == wanted.addonId }.singleOrNull()

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
    val installed = orNull { addons(kind, videoId) } ?: return null
    val addon = pickAddon(installed, wanted)
    if (addon == null) {
        DebugLog.log { "lookup $videoId: no install of ${wanted.addonId} (key ${wanted.addonKey?.take(8)})" }
        return null
    }
    val sources = orNull { sources(addon, kind, videoId) }
    if (sources == null) {
        DebugLog.log { "lookup $videoId: ${addon.name} failed" }
        return null
    }
    val picked = pickSource(sources, wanted)
    DebugLog.log {
        val group = sources.count { it.bingeGroup != null && it.bingeGroup == wanted.bingeGroup }
        "lookup $videoId: ${addon.name} sent ${sources.size} sources, $group of the group; " +
            "picked ${picked?.let(::describe) ?: "none"}"
    }
    return picked
}

/**
 * The result of [block], or null when it fails, after telling [onFailure]; cancelling, a time
 * limit included, still cancels, where `runCatching` would take it for a failure and let the
 * work of a video already left carry on. Failures are not logged here: an addon's errors can
 * quote its address, which may hold the user's settings or keys.
 */
internal suspend fun <T> orNull(
    onFailure: (Exception) -> Unit = {},
    block: suspend () -> T,
): T? =
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        onFailure(failure)
        null
    }

/**
 * A source as the debug log names it: its first line, how it plays and whether it is in its
 * debrid service's cache. Never its address.
 */
internal fun describe(source: StreamSource): String {
    val kind =
        when {
            source.url != null && source.infoHash != null -> "debrid (names its torrent)"
            source.url != null -> "address"
            source.infoHash != null -> "torrent"
            else -> "outside"
        }
    val cache =
        when (source.cached) {
            true -> ", cached"
            false -> ", not cached"
            null -> ""
        }
    return "\"${source.name.lineSequence().firstOrNull().orEmpty()}\" ($kind$cache, ${source.quality ?: "?"})"
}
