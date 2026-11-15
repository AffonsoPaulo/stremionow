// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import io.github.affonsopaulo.stremionow.core.TrackChoice
import io.github.affonsopaulo.stremionow.player.Track
import java.util.Locale

// The audio and subtitle tracks picked for a title are picked again when it is resumed or
// moves on to its next episode, as Stremio's player does. Files number their tracks each in
// their own way, so tracks are told by language, and by title among those of one language.

/** The tracks selected in [tracks], as a [TrackChoice]; null before the file lists any. */
internal fun choiceOf(tracks: List<Track>): TrackChoice? {
    if (tracks.none { it.type == Track.Type.Audio || it.type == Track.Type.Subtitle }) return null
    val audio = tracks.firstOrNull { it.type == Track.Type.Audio && it.isSelected }
    val subtitle = tracks.firstOrNull { it.type == Track.Type.Subtitle && it.isSelected }
    return TrackChoice(
        audioLanguage = audio?.language,
        audioTitle = audio?.title,
        subtitlesOff = subtitle == null,
        subtitleLanguage = subtitle?.language,
        subtitleTitle = subtitle?.title,
    )
}

/** What to select so that [tracks] match a [TrackChoice]. */
internal data class TrackPick(
    /** The audio track to select, or null to leave it as it is. */
    val audio: Int?,
    /** The subtitle to select; null leaves it as it is. */
    val subtitle: SubtitlePick?,
)

/** A subtitle to select, or none at all. */
internal sealed interface SubtitlePick {
    data class Track(
        val id: Int,
    ) : SubtitlePick

    data object Off : SubtitlePick
}

/**
 * How to pick [choice] again among [tracks]: the track of the same language and title, else
 * the first of the same language; what has no match stays as the player picked it.
 */
internal fun pickTracks(
    tracks: List<Track>,
    choice: TrackChoice,
): TrackPick {
    val audio =
        choice.audioLanguage?.let { language ->
            tracks.match(Track.Type.Audio, language, choice.audioTitle)?.takeUnless { it.isSelected }?.id
        }
    val subtitle =
        when {
            choice.subtitlesOff -> {
                SubtitlePick.Off.takeIf { tracks.any { it.type == Track.Type.Subtitle && it.isSelected } }
            }

            choice.subtitleLanguage != null -> {
                tracks
                    .match(Track.Type.Subtitle, choice.subtitleLanguage!!, choice.subtitleTitle)
                    ?.takeUnless { it.isSelected }
                    ?.let { SubtitlePick.Track(it.id) }
            }

            else -> {
                null
            }
        }
    return TrackPick(audio, subtitle)
}

/** Whether [tracks] hold a subtitle that [choice] would pick, for waiting on external ones. */
internal fun hasWantedSubtitle(
    tracks: List<Track>,
    choice: TrackChoice,
): Boolean =
    choice.subtitlesOff ||
        choice.subtitleLanguage?.let { tracks.match(Track.Type.Subtitle, it, choice.subtitleTitle) != null } ?: true

private fun List<Track>.match(
    type: Track.Type,
    language: String,
    title: String?,
): Track? {
    val wanted = languageKey(language)
    val sameLanguage = filter { it.type == type && it.language?.let(::languageKey) == wanted }
    return sameLanguage.firstOrNull { title != null && it.title == title } ?: sameLanguage.firstOrNull()
}

/**
 * One key for the ways files and addons name a language: `pt`, `por` and `pt-BR` are all
 * Portuguese. Codes Java does not know (`pob`, OpenSubtitles' Brazilian Portuguese) stay as
 * they are.
 */
internal fun languageKey(code: String): String {
    val tag = code.trim().replace('_', '-')
    LANGUAGE_ALIASES[tag.lowercase(Locale.ROOT)]?.let { return it }
    val locale = Locale.forLanguageTag(tag)
    val iso3 = runCatching { locale.isO3Language }.getOrNull().orEmpty()
    return iso3.ifEmpty { tag.lowercase(Locale.ROOT) }
}

/** Codes subtitle sites use that are no language tag: Brazilian Portuguese. */
private val LANGUAGE_ALIASES = mapOf("pob" to "por", "pb" to "por")
