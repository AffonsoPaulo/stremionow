// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.player

import java.util.Locale

/** A chapter of the current file, as listed in its container. */
data class Chapter(
    val title: String,
    val startSeconds: Double,
)

/** A stretch of the video the viewer may want to skip. */
data class Segment(
    val startSeconds: Double,
    val endSeconds: Double,
) {
    operator fun contains(seconds: Double): Boolean = seconds >= startSeconds && seconds < endSeconds
}

/**
 * Finds intros and credits from chapter names. Releases of series often mark them ("Intro",
 * "Opening", "OP", "Credits", "Ending", "ED", in several languages); files without such
 * chapters simply offer no skip.
 */
object Chapters {
    /** Intros longer than this are probably a mislabeled chapter, not an opening. */
    private const val MAX_INTRO_SECONDS = 4 * 60.0

    private val introNames =
        setOf("intro", "introduction", "opening", "op", "opening credits", "abertura", "apertura", "générique")
    private val creditsNames =
        setOf("credits", "ending", "ed", "outro", "end credits", "closing credits", "créditos", "encerramento")

    /** The intro, if a chapter is named like one and is of a plausible length. */
    fun intro(
        chapters: List<Chapter>,
        durationSeconds: Double?,
    ): Segment? =
        segments(chapters, durationSeconds)
            .firstOrNull { (chapter, segment) ->
                chapter.title.normalized() in introNames &&
                    segment.endSeconds - segment.startSeconds <= MAX_INTRO_SECONDS
            }?.second

    /** Where the end credits start, if a chapter in the second half is named like them. */
    fun creditsStart(
        chapters: List<Chapter>,
        durationSeconds: Double?,
    ): Double? {
        val duration = durationSeconds ?: return null
        return segments(chapters, duration)
            .lastOrNull { (chapter, segment) ->
                chapter.title.normalized() in creditsNames && segment.startSeconds > duration / 2
            }?.second
            ?.startSeconds
    }

    /** Parses mpv's `chapter-list` property (JSON with `title` and `time`). */
    fun parse(json: String): List<Chapter> =
        runCatching {
            val array = org.json.JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val time =
                    item.optDouble("time", Double.NaN).takeIf { it.isFinite() && it >= 0 } ?: return@mapNotNull null
                Chapter(item.optString("title", ""), time)
            }
        }.getOrDefault(emptyList())

    /** Each chapter with the span it covers, up to the next chapter or the end. */
    private fun segments(
        chapters: List<Chapter>,
        durationSeconds: Double?,
    ): List<Pair<Chapter, Segment>> {
        val sorted = chapters.sortedBy { it.startSeconds }
        return sorted.mapIndexedNotNull { index, chapter ->
            val end = sorted.getOrNull(index + 1)?.startSeconds ?: durationSeconds ?: return@mapIndexedNotNull null
            if (end <= chapter.startSeconds) null else chapter to Segment(chapter.startSeconds, end)
        }
    }

    /** Lowercase without surrounding punctuation, so "OP:" and " Opening " match. */
    private fun String.normalized(): String =
        lowercase(Locale.ROOT).trim().trim { !it.isLetterOrDigit() }.replace(Regex("\\s+"), " ")
}
