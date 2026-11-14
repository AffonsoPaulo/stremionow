// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import io.github.affonsopaulo.stremionow.core.StreamSource
import io.github.affonsopaulo.stremionow.core.WatchProgress
import java.util.Locale
import kotlin.math.floor

// How the sources page searches, filters, sorts and lays out a video's sources, apart from the
// interface so it can be tested.

/** The ways the sources can be narrowed down, in the order their chips show. */
enum class SourceFacet { Resolution, Addon, Hdr, Language, Type }

/** How the sources are ordered. [Best] keeps the core's ranking. */
enum class SourceSort { Best, Size, Seeds, Resolution }

/** How a source plays, for the Type filter. */
enum class SourceType { InApp, Torrent, Outside }

/** What the bottom right of a source's card says. */
enum class SourceStatus { Seeds, Cached, NotCached, Outside, None }

/**
 * The search, the options chosen in each filter and the order of the sources. Options of one
 * filter add up (any of them); filters narrow each other down (all of them).
 */
data class SourceFilters(
    val query: String = "",
    val selected: Map<SourceFacet, Set<String>> = emptyMap(),
    val sort: SourceSort = SourceSort.Best,
) {
    fun selected(facet: SourceFacet): Set<String> = selected[facet].orEmpty()

    /** Some filter has an option chosen; the search does not count. */
    val anySelected: Boolean get() = selected.values.any { it.isNotEmpty() }

    /** Chooses [value] in [facet], or takes it back if it was chosen. */
    fun toggle(
        facet: SourceFacet,
        value: String,
    ): SourceFilters {
        val current = selected(facet)
        val next = if (value in current) current - value else current + value
        return copy(selected = selected + (facet to next))
    }

    /** Every filter cleared; the search and the order stay. */
    fun cleared(): SourceFilters = copy(selected = emptyMap())
}

/** One option of a filter: its value, how many sources it would show, and whether it is chosen. */
data class FacetOption(
    val value: String,
    val count: Int,
    val selected: Boolean,
)

/** The HDR option of sources whose text names no HDR format. */
internal const val SDR = "SDR"

/** Resolutions from the highest, as the core labels them. */
private val ResolutionOrder = listOf("8K", "4K", "1440p", "1080p", "720p", "SD")

/** HDR formats as the core labels them, best first, then [SDR]. */
private val HdrOrder = listOf("DV", "HDR10+", "HDR10", "HLG", SDR)

internal val StreamSource.type: SourceType
    get() =
        when {
            url != null -> SourceType.InApp
            infoHash != null -> SourceType.Torrent
            else -> SourceType.Outside
        }

/** The values a source has for a filter: none when it does not say (no resolution, say). */
internal fun StreamSource.facetValues(facet: SourceFacet): Set<String> =
    when (facet) {
        SourceFacet.Resolution -> setOfNotNull(quality)
        SourceFacet.Addon -> setOf(addonName)
        SourceFacet.Hdr -> if (hdr.isEmpty()) setOf(SDR) else hdr.toSet()
        SourceFacet.Language -> languages.toSet()
        SourceFacet.Type -> setOf(type.name)
    }

/**
 * Whether a source passes [filters]: its name or description has every word of the search,
 * ignoring case and accents, and it has one of the options chosen in each filter. [except]
 * leaves one filter out, to count that filter's own options.
 */
internal fun StreamSource.passes(
    filters: SourceFilters,
    except: SourceFacet? = null,
): Boolean =
    matchesQuery(filters.query, name, description) &&
        SourceFacet.entries.all { facet ->
            val chosen = filters.selected(facet)
            facet == except || chosen.isEmpty() || facetValues(facet).any { it in chosen }
        }

/** The sources that pass [filters], in [SourceFilters.sort]'s order. */
internal fun shownSources(
    sources: List<StreamSource>,
    filters: SourceFilters,
): List<StreamSource> = sortSources(sources.filter { it.passes(filters) }, filters.sort)

/**
 * The options [facet]'s chip offers: each value the sources that pass the search and the other
 * filters have, with how many of them have it. Options no source has are left out, unless
 * chosen, so they can still be taken back.
 */
internal fun facetOptions(
    sources: List<StreamSource>,
    filters: SourceFilters,
    facet: SourceFacet,
): List<FacetOption> {
    val counts =
        sources
            .filter { it.passes(filters, except = facet) }
            .flatMap { it.facetValues(facet) }
            .groupingBy { it }
            .eachCount()
    val chosen = filters.selected(facet)
    val values = (counts.keys + chosen).distinct()
    val ordered =
        when (facet) {
            SourceFacet.Resolution -> {
                values.sortedBy { rank(ResolutionOrder, it) }
            }

            SourceFacet.Hdr -> {
                values.sortedBy { rank(HdrOrder, it) }
            }

            SourceFacet.Type -> {
                values.sortedBy { value -> SourceType.entries.indexOfFirst { it.name == value } }
            }

            // In the order the addons' sources are listed, the best one's first.
            SourceFacet.Addon -> {
                val order = sources.map { it.addonName }.distinct()
                values.sortedBy { rank(order, it) }
            }

            SourceFacet.Language -> {
                values.sortedWith(compareByDescending<String> { counts[it] ?: 0 }.thenBy { it })
            }
        }
    return ordered.map { FacetOption(it, counts[it] ?: 0, it in chosen) }.filter { it.count > 0 || it.selected }
}

/** Where [value] stands in [order]; values it does not list come after. */
private fun rank(
    order: List<String>,
    value: String,
): Int = order.indexOf(value).let { if (it < 0) order.size else it }

/** How high a resolution label is, 8K highest; null for none. */
internal fun resolutionRank(quality: String?): Int? =
    quality?.let { label -> ResolutionOrder.indexOf(label).takeIf { it >= 0 }?.let { ResolutionOrder.size - it } }

/**
 * The sources in [sort]'s order. Best keeps the core's ranking; the others put the largest,
 * best seeded or highest resolution first, sources that do not say last, and keep the ranking
 * between equals.
 */
internal fun sortSources(
    sources: List<StreamSource>,
    sort: SourceSort,
): List<StreamSource> =
    when (sort) {
        SourceSort.Best -> sources
        SourceSort.Size -> sources.sortedWith(compareBy(nullsLast(reverseOrder())) { it.sizeBytes })
        SourceSort.Seeds -> sources.sortedWith(compareBy(nullsLast(reverseOrder())) { it.seeders })
        SourceSort.Resolution -> sources.sortedWith(compareBy(nullsLast(reverseOrder())) { resolutionRank(it.quality) })
    }

/** The source the app recommends: the first of the ranking, if the app plays it itself. */
internal fun bestSource(ranked: List<StreamSource>): StreamSource? = ranked.firstOrNull()?.takeIf { it.playsInApp }

/** The sources shown: the one to continue with, when it is among them, then the rest. */
data class SourcesLayout(
    val continueWith: StreamSource?,
    val grid: List<StreamSource>,
)

/**
 * Pins the source last played for the video ([played], its identity) first when it is among
 * [shown]; the grid then has every other source, in order.
 */
internal fun sourcesLayout(
    shown: List<StreamSource>,
    played: String?,
): SourcesLayout {
    val pinned = played?.let { identity -> shown.firstOrNull { it.identity == identity } }
    return SourcesLayout(pinned, if (pinned == null) shown else shown.filter { it !== pinned })
}

/**
 * Columns of the sources grid in [widthDp]: as many as fit with cards at least [MIN_SOURCE_CARD_DP]
 * wide and [gapDp] between them, up to four; a phone always has one.
 */
internal fun sourceGridColumns(
    widthDp: Float,
    gapDp: Float,
    phone: Boolean,
): Int {
    if (phone) return 1
    return floor((widthDp + gapDp) / (MIN_SOURCE_CARD_DP + gapDp)).toInt().coerceIn(1, MAX_SOURCE_COLUMNS)
}

/** Narrowest a source card gets in the grid. */
internal const val MIN_SOURCE_CARD_DP = 320f
private const val MAX_SOURCE_COLUMNS = 4

/**
 * A source's name for its card: the title and file name lines of its description, or else the
 * addon's own label without its first line (the addon's name, shown apart).
 */
internal fun sourceTitle(source: StreamSource): String =
    descriptionLines(source.description)
        .ifEmpty { descriptionLines(source.name).drop(1) }
        .ifEmpty { descriptionLines(source.name) }
        .joinToString("\n")

/** One line of what a source is: size, HDR, audio, codec, kind of release and release group. */
internal fun sourceDetailsLine(source: StreamSource): String =
    buildList {
        source.size?.let(::add)
        addAll(source.hdr)
        addAll(source.audio)
        source.codec?.let(::add)
        source.releaseQuality?.let(::add)
        source.releaseGroup?.let(::add)
    }.distinct().joinToString(" · ")

/**
 * What a source's card says at its bottom right: that it opens in another app; for a debrid
 * link, whether the service has it ready, when the addon says; otherwise a torrent's seeds.
 */
internal fun sourceStatus(source: StreamSource): SourceStatus =
    when {
        !source.playsInApp -> SourceStatus.Outside
        source.url != null && source.cached == true -> SourceStatus.Cached
        source.url != null && source.cached == false -> SourceStatus.NotCached
        source.seeders != null -> SourceStatus.Seeds
        else -> SourceStatus.None
    }

/** Minutes left of a video in progress, at least one; null without a duration. */
internal fun minutesLeft(progress: WatchProgress): Long? =
    progress.durationMs
        .takeIf { it > 0u }
        ?.let { duration ->
            val left = if (progress.positionMs >= duration) 0uL else duration - progress.positionMs
            (left.toLong() / 60_000).coerceAtLeast(1)
        }

/** A language's name in [locale] from its ISO 639 code ("it" is "Italiano" in Italian); the code if unknown. */
internal fun languageName(
    code: String,
    locale: Locale,
): String {
    val name = Locale.forLanguageTag(code).getDisplayLanguage(locale)
    return if (name.isBlank() || name.equals(code, ignoreCase = true)) {
        code.uppercase(Locale.ROOT)
    } else {
        name.replaceFirstChar { it.titlecase(locale) }
    }
}
