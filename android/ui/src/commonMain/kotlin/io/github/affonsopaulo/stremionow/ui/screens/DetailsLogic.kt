// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import io.github.affonsopaulo.stremionow.core.Episode
import io.github.affonsopaulo.stremionow.core.MetaDetails
import io.github.affonsopaulo.stremionow.core.StreamSource
import io.github.affonsopaulo.stremionow.core.WatchProgress
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

// What the details page works out from a title, its progress and its sources, apart from the
// interface so it can be tested.

/** When an episode came out, from the ISO 8601 date or date-time addons send; null if unknown. */
internal fun releaseInstant(released: String?): Instant? {
    val text = released?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(text) }.getOrNull()
        ?: runCatching { LocalDate.parse(text.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull()
}

/**
 * Whether an episode is out at [now]. An episode without a date counts as out: many addons
 * (anime ones especially) give no dates at all, and their episodes are all released.
 */
internal fun Episode.isReleased(now: Instant): Boolean = releaseInstant(released)?.let { !it.isAfter(now) } ?: true

/**
 * A release date in [locale]'s short date format with the full year: 11/09/2009 in Brazil,
 * 9/11/2009 in the US. Never ISO. The day is the one the addon wrote, whatever the time zone:
 * with an offset, its own day, not the one in UTC (late on the 11th at -03:00 is the 12th in
 * UTC).
 */
internal fun formatReleaseDate(
    released: String?,
    locale: Locale,
): String? {
    val text = released?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val day =
        runCatching { OffsetDateTime.parse(text).toLocalDate() }.getOrNull()
            ?: releaseInstant(text)?.atZone(ZoneOffset.UTC)?.toLocalDate()
            ?: return null
    return shortDateFormatter(locale).format(day)
}

private fun shortDateFormatter(locale: Locale): DateTimeFormatter {
    val pattern =
        DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale)
    // Short formats cut the year to two digits in some regions (M/d/yy in the US).
    return DateTimeFormatter.ofPattern(pattern.replace(Regex("y+"), "yyyy"), locale)
}

/**
 * The details as the page shows them. A movie whose addon also lists videos is still a movie,
 * with its sources in place of a list of one "episode": when its only video has the title's
 * own id (as Stremio's clients treat it), or when the title is a movie and no video has a
 * season or an episode number. The page tells a series by its episodes, so these are dropped.
 */
internal fun MetaDetails.asShown(): MetaDetails {
    val onlyItself = episodes.size == 1 && episodes[0].id == item.id
    val unnumberedMovie = item.kind == "movie" && episodes.all { it.season == null && it.episode == null }
    return if (episodes.isNotEmpty() && (onlyItself || unnumberedMovie)) copy(episodes = emptyList()) else this
}

/**
 * The group of an episode in the season selector: its season, or [NO_SEASON] for the
 * episodes the addon gives no season, which are neither season 0's specials nor a season.
 */
internal fun Episode.seasonKey(): Long = season?.toLong() ?: NO_SEASON

/** The season selector's group for episodes without a season number; see [seasonKey]. */
internal const val NO_SEASON = -1L

/**
 * The series' episodes by [seasonKey]: the seasons in order, then the episodes without a
 * season, then the specials. An episode listed twice by its addon shows once.
 */
internal fun seasonGroups(episodes: List<Episode>): Map<Long, List<Episode>> =
    episodes
        .distinctBy { it.id }
        .groupBy { it.seasonKey() }
        .toSortedMap(
            compareBy<Long> {
                when (it) {
                    0L -> 2
                    NO_SEASON -> 1
                    else -> 0
                }
            }.thenBy { it },
        )

/** Whether the Details tab has anything to show: a synopsis, the runtime, genres or people. */
internal fun hasInfo(details: MetaDetails): Boolean =
    details.item.description != null ||
        details.runtime != null ||
        details.item.genres.isNotEmpty() ||
        details.cast.isNotEmpty() ||
        details.directors.isNotEmpty()

/** What "Mark as watched" covers on a series. */
enum class WatchScope { Episode, Season, Series }

/**
 * The videos a "Mark as watched" covers: a movie itself; or, on a series, the released
 * episodes of [season] (a [seasonKey]), or all of them. Episodes not out yet are never
 * included.
 */
internal fun coveredVideos(
    details: MetaDetails,
    scope: WatchScope,
    episode: Episode?,
    season: Long?,
    now: Instant,
): List<String> {
    if (details.episodes.isEmpty()) return listOf(details.item.id)
    val released = details.episodes.filter { it.isReleased(now) }
    return when (scope) {
        WatchScope.Episode -> released.filter { it.id == episode?.id }
        WatchScope.Season -> released.filter { it.seasonKey() == season }
        WatchScope.Series -> released
    }.map(Episode::id)
}

/** Whether everything [covered] is watched, so the action now marks it unwatched. */
internal fun allWatched(
    covered: List<String>,
    watched: Set<String>,
): Boolean = covered.isNotEmpty() && covered.all { it in watched }

/** Where the main button leads, and how far the user got there. */
data class NextUp(
    /** The episode to watch, for series. */
    val episode: Episode?,
    /** Share of [episode] (or the movie) already watched, when it is in progress. */
    val progress: Float?,
    /** The user is in the middle of the title, so the button resumes rather than starts. */
    val started: Boolean,
)

/**
 * The main button's target. A movie resumes where it stopped. A series resumes the episode in
 * progress, else the first released, unwatched episode after the last one watched; with none
 * watched, or every one, it starts at the first regular episode, skipping specials.
 */
internal fun nextUp(
    details: MetaDetails,
    progress: WatchProgress?,
    watched: Set<String>,
    now: Instant,
): NextUp {
    val fraction = progress?.fraction()
    if (details.episodes.isEmpty()) {
        return NextUp(null, fraction, started = progress != null)
    }
    progress
        ?.let { saved -> details.episodes.firstOrNull { it.id == saved.videoId } }
        ?.let { return NextUp(it, fraction, started = true) }
    val regular = details.episodes.filter { (it.season ?: 0u) > 0u }.ifEmpty { details.episodes }
    val lastWatched = regular.indexOfLast { it.id in watched }
    if (lastWatched >= 0) {
        regular
            .drop(lastWatched + 1)
            .firstOrNull { it.id !in watched && it.isReleased(now) }
            ?.let { return NextUp(it, null, started = true) }
    }
    return NextUp(regular.firstOrNull(), null, started = false)
}

/**
 * Where "Continue watching" moves on to when the episode it is on, [current], gets marked
 * watched, as stremio-core does: the first released episode after it that is not in
 * [watched] (which counts the ones just marked), specials staying apart from the seasons;
 * null when none is left, and the title leaves the row.
 */
internal fun resumeAfterMarking(
    details: MetaDetails,
    current: String,
    watched: Set<String>,
    now: Instant,
): Episode? {
    val episode = details.episodes.firstOrNull { it.id == current } ?: return null
    val special = (episode.season ?: 0u) == 0u
    return details.episodes
        .filter { ((it.season ?: 0u) == 0u) == special }
        .dropWhile { it.id != current }
        .drop(1)
        .firstOrNull { it.id !in watched && it.isReleased(now) }
}

/** How far into its video a saved position is, from 0 to 1; null without a duration. */
internal fun WatchProgress.fraction(): Float? =
    durationMs.takeIf { it > 0u }?.let { (positionMs.toDouble() / it.toDouble()).toFloat().coerceIn(0f, 1f) }

/** Whether the app plays the source itself rather than handing it to another app. */
internal val StreamSource.playsInApp: Boolean get() = url != null || infoHash != null

/** Sources the app can use: web addresses and torrents play in the app, links open elsewhere. */
internal fun StreamSource.isUsable(): Boolean = url != null || infoHash != null || externalUrl != null || ytId != null

/** Whether a source in 4K (or more) that plays in the app was found: only then is it known. */
internal fun hasPlayable4k(sources: List<StreamSource>): Boolean =
    sources.any { it.playsInApp && (it.quality == "4K" || it.quality == "8K") }

/** The page a source opens outside the app: its link, or its YouTube video. */
internal fun StreamSource.outsideLink(): String? = externalUrl ?: ytId?.let(::youtubeLink)

internal fun youtubeLink(id: String): String = "https://www.youtube.com/watch?v=$id"

/**
 * The letter standing for a streaming service while its icon loads, or when it has none: the
 * first letter of the link's site name (`www.netflix.com` gives N), else of [fallback].
 */
internal fun serviceInitial(
    link: String?,
    fallback: String,
): String {
    val host =
        link
            ?.substringAfter("://", "")
            ?.substringBefore('/')
            ?.substringBefore(':')
            ?.lowercase(Locale.ROOT)
            .orEmpty()
    val labels = host.split('.').filter { it.isNotEmpty() }
    val name = labels.dropWhile { it == "www" || it == "m" || it == "tv" }.firstOrNull() ?: fallback
    return name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
}

/**
 * The lines of a source's description worth reading: its title and file name. The line of
 * seeds, size and provider that many addons add with emoji is dropped, since the details
 * shown under it say the same.
 */
internal fun descriptionLines(description: String): List<String> =
    description
        .lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && StatsMarkers.none(it::contains) }
        .toList()

private val StatsMarkers = listOf("👤", "💾", "⚙")
