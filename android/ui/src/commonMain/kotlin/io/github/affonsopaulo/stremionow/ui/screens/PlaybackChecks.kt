// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import io.github.affonsopaulo.stremionow.ui.data.PlaybackSession

// A source can lead to something other than the video: debrid addons answer with a short clip
// saying the file is being downloaded, and other addons have their own. Played to its end, such
// a clip would count as the episode watched and move Continue watching on. A video much shorter
// than expected is asked about before its progress counts.

/** Shortest a film or an episode can be before it looks like something else. */
internal const val MIN_FULL_VIDEO_SECONDS = 180.0

/** A video shorter than this share of the length the title gives looks like something else. */
internal const val MIN_SHARE_OF_RUNTIME = 0.5

/** Kinds whose videos are films or episodes, with a length to compare with. */
private val FULL_LENGTH_KINDS = setOf("movie", "series", "anime")

/**
 * Whether a video of [durationSeconds] looks too short to be [selection]'s film or episode:
 * under three minutes, or under half the length the title's metadata gives, when it gives one.
 * Other kinds (channels' videos) can be short and are never asked about.
 */
internal fun looksIncomplete(
    durationSeconds: Double,
    selection: PlaybackSession.Selection,
): Boolean {
    if (selection.kind !in FULL_LENGTH_KINDS) return false
    val expectedMinutes =
        selection.episode?.runtimeMinutes?.toInt()
            ?: selection.details.runtime
                ?.let(::runtimeMinutes)
                .takeIf { selection.episode == null }
    val shortOfRuntime =
        expectedMinutes != null && expectedMinutes > 0 && durationSeconds < expectedMinutes * 60 * MIN_SHARE_OF_RUNTIME
    return durationSeconds < MIN_FULL_VIDEO_SECONDS || shortOfRuntime
}

/**
 * Minutes in a runtime as addons write it: "142 min", "2h 22min", "2h", "1:42". Null when it
 * holds no number.
 */
internal fun runtimeMinutes(text: String): Int? {
    Regex("""^\s*(\d+):(\d{2})""").find(text)?.let { match ->
        return match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
    }
    val hours =
        Regex("""(\d+)\s*h""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.toInt()
    val minutes =
        Regex("""(\d+)\s*m""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.toInt()
            ?: Regex("""^\s*(\d+)\s*$""")
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.toInt()
    if (hours == null && minutes == null) return null
    return (hours ?: 0) * 60 + (minutes ?: 0)
}

/** "0:30", "1:02:05": how long a video is, for the incomplete video question. */
internal fun clockText(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val rest = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
}
