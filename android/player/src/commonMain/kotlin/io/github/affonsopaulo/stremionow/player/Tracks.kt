// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.player

import org.json.JSONArray

/**
 * Parses mpv's `track-list` property. `org.json` is part of Android; the desktop target adds
 * the same library as a dependency.
 */
internal fun parseTracks(array: JSONArray): List<Track> =
    buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val type =
                when (item.optString("type")) {
                    "video" -> Track.Type.Video
                    "audio" -> Track.Type.Audio
                    "sub" -> Track.Type.Subtitle
                    else -> continue
                }
            add(
                Track(
                    id = item.optInt("id"),
                    type = type,
                    title = item.optString("title").ifEmpty { null },
                    language = item.optString("lang").ifEmpty { null },
                    codec = item.optString("codec").ifEmpty { null },
                    isSelected = item.optBoolean("selected"),
                    isExternal = item.optBoolean("external"),
                ),
            )
        }
    }
