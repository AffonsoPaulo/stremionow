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
 * Finds intros, recaps and credits from chapter names. Releases of series often mark them
 * ("Intro", "Opening", "OP", "Recap", "Credits", "Ending", "ED", in several languages); files
 * without such chapters simply offer no skip.
 */
object Chapters {
    /** Intros and recaps longer than this are probably a mislabeled chapter. */
    private const val MAX_INTRO_SECONDS = 4 * 60.0

    private val introNames =
        setOf("intro", "introduction", "opening", "op", "opening credits", "abertura", "apertura", "générique")
    private val recapNames =
        setOf("recap", "previously", "previously on", "résumé", "resumo", "recapitulação", "riassunto")
    private val creditsNames =
        setOf("credits", "ending", "ed", "outro", "end credits", "closing credits", "créditos", "encerramento")

    /** Where the intro, recap and end credits are, as far as the chapter names tell. */
    fun markers(
        chapters: List<Chapter>,
        durationSeconds: Double?,
    ): Markers =
        Markers(
            intro = intro(chapters, durationSeconds),
            recap = recap(chapters, durationSeconds),
            credits = credits(chapters, durationSeconds),
        )

    /** The intro, if a chapter is named like one and is of a plausible length. */
    fun intro(
        chapters: List<Chapter>,
        durationSeconds: Double?,
    ): Segment? = shortNamed(chapters, durationSeconds, introNames)

    /** The recap of earlier episodes, if a chapter is named like one and is of a plausible length. */
    fun recap(
        chapters: List<Chapter>,
        durationSeconds: Double?,
    ): Segment? = shortNamed(chapters, durationSeconds, recapNames)

    /** The end credits, if a chapter in the second half is named like them. */
    fun credits(
        chapters: List<Chapter>,
        durationSeconds: Double?,
    ): Segment? {
        val duration = durationSeconds ?: return null
        return segments(chapters, duration)
            .lastOrNull { (chapter, segment) ->
                chapter.title.normalized() in creditsNames && segment.startSeconds > duration / 2
            }?.second
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

    private fun shortNamed(
        chapters: List<Chapter>,
        durationSeconds: Double?,
        names: Set<String>,
    ): Segment? =
        segments(chapters, durationSeconds)
            .firstOrNull { (chapter, segment) ->
                chapter.title.normalized() in names && segment.endSeconds - segment.startSeconds <= MAX_INTRO_SECONDS
            }?.second

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
