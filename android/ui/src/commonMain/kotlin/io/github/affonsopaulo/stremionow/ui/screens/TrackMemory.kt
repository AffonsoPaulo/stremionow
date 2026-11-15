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
 * What to remember of the viewer picking the audio track [trackId] among [tracks]: [memory], the
 * tracks remembered for the title, with only its audio changed. Its subtitle stays as it was
 * remembered, even when this file could not show it; with nothing remembered yet, it is the one
 * showing.
 */
internal fun withAudioPicked(
    memory: TrackChoice?,
    tracks: List<Track>,
    trackId: Int?,
): TrackChoice? {
    val picked = choiceOf(tracks.picking(Track.Type.Audio, trackId)) ?: return memory
    return memory?.copy(audioLanguage = picked.audioLanguage, audioTitle = picked.audioTitle) ?: picked
}

/** As [withAudioPicked], for the viewer picking the subtitle [trackId], or none with null. */
internal fun withSubtitlePicked(
    memory: TrackChoice?,
    tracks: List<Track>,
    trackId: Int?,
): TrackChoice? {
    val picked = choiceOf(tracks.picking(Track.Type.Subtitle, trackId)) ?: return memory
    return memory?.copy(
        subtitlesOff = picked.subtitlesOff,
        subtitleLanguage = picked.subtitleLanguage,
        subtitleTitle = picked.subtitleTitle,
    ) ?: picked
}

/** These tracks once [trackId] is the one selected of its [type], or none of it with null. */
private fun List<Track>.picking(
    type: Track.Type,
    trackId: Int?,
): List<Track> = map { if (it.type == type) it.copy(isSelected = it.id == trackId) else it }

/**
 * One key for the ways files and addons name a language: `pt`, `por`, `pt-BR` and `pob` are all
 * Portuguese; `fr`, `fra` and `fre` all French. The key is the ISO 639-2 terminology code
 * (`fra`, `deu`), which Java gives for two-letter codes, while Matroska files and OpenSubtitles
 * use the bibliographic one (`fre`, `ger`). English names ("French") and the language's own
 * ("Français") are understood too. Anything else stays as it is, in lowercase, so two tracks of
 * a language no list knows still match each other.
 */
internal fun languageKey(code: String): String {
    val tag = code.trim().replace('_', '-')
    val lower = tag.lowercase(Locale.ROOT)
    LANGUAGE_ALIASES[lower]?.let { return it }
    val iso3 =
        if (LANGUAGE_CODE.matches(tag)) {
            runCatching { Locale.forLanguageTag(tag).isO3Language }.getOrNull().orEmpty().lowercase(Locale.ROOT)
        } else {
            ""
        }
    if (iso3.isNotEmpty()) return BIBLIOGRAPHIC_TO_TERMINOLOGY[iso3] ?: LANGUAGE_ALIASES[iso3] ?: iso3
    return LANGUAGE_NAMES[lower] ?: lower
}

/**
 * A language tag for showing a track's language by name: the two-letter code of [code]'s
 * language when it has one (`fre` → `fr`), so Java can name it, `pt-BR` for Brazilian
 * Portuguese, else [code] itself.
 */
internal fun displayLanguageTag(code: String): String {
    val lower = code.trim().replace('_', '-').lowercase(Locale.ROOT)
    if (lower in BRAZILIAN_PORTUGUESE) return "pt-BR"
    if (lower.contains('-')) return code
    return TWO_LETTER_CODES[languageKey(code)] ?: code
}

/**
 * [code] and the other codes of its language, for mpv's lists of preferred languages, which
 * compare codes as they are: "fr" gives fr, fra and fre; "pt-BR" gives pt-BR, pob, pt and por.
 */
internal fun languageCodes(code: String): List<String> {
    val key = languageKey(code)
    val brazilian = code.trim().replace('_', '-').lowercase(Locale.ROOT) in BRAZILIAN_PORTUGUESE
    return listOfNotNull(
        code,
        "pob".takeIf { brazilian },
        TWO_LETTER_CODES[key],
        key,
        TERMINOLOGY_TO_BIBLIOGRAPHIC[key],
    ).distinct()
}

/** A language code, alone or with a region or script ("pt-BR", "zh-Hant"), as opposed to a name. */
private val LANGUAGE_CODE = Regex("""^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$""")

/** OpenSubtitles' codes for Brazilian Portuguese. */
private val BRAZILIAN_PORTUGUESE = setOf("pob", "pb", "pt-br")

/**
 * Codes subtitle sites use that are no ISO 639 code, or one Java does not map: OpenSubtitles'
 * Brazilian Portuguese, Chinese variants, and the old codes for Serbian and Croatian.
 */
private val LANGUAGE_ALIASES =
    mapOf(
        "pob" to "por",
        "pb" to "por",
        "zht" to "zho",
        "zhe" to "zho",
        "ze" to "zho",
        "scc" to "srp",
        "scr" to "hrv",
        "mol" to "ron",
    )

/** The twenty ISO 639-2 languages with a bibliographic code apart from the terminology one. */
private val BIBLIOGRAPHIC_TO_TERMINOLOGY =
    mapOf(
        "alb" to "sqi",
        "arm" to "hye",
        "baq" to "eus",
        "bur" to "mya",
        "chi" to "zho",
        "cze" to "ces",
        "dut" to "nld",
        "fre" to "fra",
        "geo" to "kat",
        "ger" to "deu",
        "gre" to "ell",
        "ice" to "isl",
        "mac" to "mkd",
        "mao" to "mri",
        "may" to "msa",
        "per" to "fas",
        "rum" to "ron",
        "slo" to "slk",
        "tib" to "bod",
        "wel" to "cym",
    )

private val TERMINOLOGY_TO_BIBLIOGRAPHIC = BIBLIOGRAPHIC_TO_TERMINOLOGY.entries.associate { (b, t) -> t to b }

/** Every language Java knows by a two-letter code, under its [languageKey]. */
private val TWO_LETTER_CODES: Map<String, String> by lazy {
    Locale.getISOLanguages().associateBy { code -> languageKey(code) }
}

/** Every language Java knows, by its English name and its own, in lowercase, to its key. */
private val LANGUAGE_NAMES: Map<String, String> by lazy {
    buildMap {
        for (code in Locale.getISOLanguages()) {
            val locale = Locale.forLanguageTag(code)
            val key =
                runCatching { locale.isO3Language }.getOrNull()?.lowercase(Locale.ROOT)?.ifEmpty { null } ?: continue
            val normalized = BIBLIOGRAPHIC_TO_TERMINOLOGY[key] ?: LANGUAGE_ALIASES[key] ?: key
            for (name in listOf(locale.getDisplayLanguage(Locale.ENGLISH), locale.getDisplayLanguage(locale))) {
                val lower = name.lowercase(Locale.ROOT)
                if (lower.isNotBlank() && lower != code) putIfAbsent(lower, normalized)
            }
        }
    }
}
