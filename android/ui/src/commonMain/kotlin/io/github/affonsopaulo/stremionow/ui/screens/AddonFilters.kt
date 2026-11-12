// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import java.text.Normalizer

/** Kinds of content the addon filter offers, in the order the chips show them. */
enum class AddonType { Movies, Series, Anime, Channels, Tv, Other }

/** The filter kind of a content type an addon declares, such as `movie` or `Podcasts`. */
internal fun addonType(kind: String): AddonType =
    when (kind.trim().lowercase()) {
        "movie", "movies" -> AddonType.Movies
        "series" -> AddonType.Series
        "anime" -> AddonType.Anime
        "channel", "channels" -> AddonType.Channels
        "tv" -> AddonType.Tv
        else -> AddonType.Other
    }

/** The filter kinds of an addon's declared types; an addon that declares none counts as Other. */
internal fun addonTypes(types: List<String>): Set<AddonType> =
    if (types.isEmpty()) setOf(AddonType.Other) else types.mapTo(sortedSetOf()) { addonType(it) }

/**
 * The kinds worth offering as filters for addons with these declared types: those present, in
 * chip order. Empty when there is nothing to choose between.
 */
internal fun typeOptions(typesOfEach: List<List<String>>): List<AddonType> {
    val present = typesOfEach.flatMapTo(sortedSetOf()) { addonTypes(it) }
    return if (present.size < 2) emptyList() else present.toList()
}

/**
 * Whether an addon matches a search: every word of [query] appears somewhere in its name,
 * description or id, ignoring case and accents. A blank query matches everything.
 */
internal fun matchesQuery(
    query: String,
    vararg fields: String,
): Boolean {
    val words = normalized(query).split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val haystack = fields.joinToString(" ") { normalized(it) }
    return words.all { it in haystack }
}

private val Marks = Regex("\\p{Mn}+")
private val Spaces = Regex("\\s+")

private fun normalized(text: String): String =
    Normalizer
        .normalize(text, Normalizer.Form.NFD)
        .replace(Marks, "")
        .replace(Spaces, " ")
        .lowercase()
        .trim()
