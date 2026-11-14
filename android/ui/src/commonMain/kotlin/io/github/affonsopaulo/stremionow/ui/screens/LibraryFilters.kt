// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import io.github.affonsopaulo.stremionow.core.LibraryItem
import java.text.Collator
import java.util.Locale

/** The type menu of "My list", as on Stremio's library. */
enum class LibraryType { All, Movies, Series }

/**
 * The pills of "My list", the same as the sorts of Stremio's library (stremio-core
 * `library_with_filters::Sort`). The first four order the list; [Watched] and [NotWatched]
 * keep only the titles watched, or not, newest watch first.
 */
enum class LibrarySort { LastWatched, AToZ, ZToA, MostWatched, Watched, NotWatched }

/**
 * Collation of the user's language for sorting names: accents and case do not count, so
 * "Élite" sits with the E's.
 */
fun nameCollator(locale: Locale = Locale.getDefault()): Comparator<String> {
    val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
    return Comparator { a, b -> collator.compare(a, b) }
}

/**
 * The titles of "My list" to show for a [type] and a [sort], in order.
 *
 * - Last watched: the latest watch first (stremio-core: `lastWatched`, newest first); titles
 *   never watched come after, the most recently added first.
 * - A–Z and Z–A: by name, compared with [names] (stremio-core compares lower-cased names; the
 *   user's collation also sorts accented names as they expect).
 * - Most watched: the most times watched to the end first (stremio-core: `timesWatched`).
 * - Watched and Not watched: only the titles that are, or are not, watched (stremio-core's
 *   rule: watched at least once, here also any video marked watched), latest watch first.
 *
 * Ties fall back to the last watch, then the date added, then the id, so the order never
 * jumps between loads.
 */
fun arrangeLibrary(
    items: List<LibraryItem>,
    type: LibraryType,
    sort: LibrarySort,
    names: Comparator<String> = nameCollator(),
): List<LibraryItem> {
    val ofType =
        when (type) {
            LibraryType.All -> items
            LibraryType.Movies -> items.filter { it.kind == "movie" }
            LibraryType.Series -> items.filter { it.kind == "series" }
        }
    val shown =
        when (sort) {
            LibrarySort.Watched -> ofType.filter { it.watched }
            LibrarySort.NotWatched -> ofType.filterNot { it.watched }
            else -> ofType
        }
    val byName = compareBy(names) { item: LibraryItem -> item.name }
    val order: Comparator<LibraryItem> =
        when (sort) {
            LibrarySort.AToZ -> byName.then(ByRecency)
            LibrarySort.ZToA -> byName.reversed().then(ByRecency)
            LibrarySort.MostWatched -> compareByDescending<LibraryItem> { it.timesWatched }.then(ByRecency)
            LibrarySort.LastWatched, LibrarySort.Watched, LibrarySort.NotWatched -> ByRecency
        }
    return shown.sortedWith(order)
}

/** The latest watch first, never watched last; then the most recently added; then by id. */
private val ByRecency: Comparator<LibraryItem> =
    compareByDescending<LibraryItem, Long?>(nullsFirst()) { it.lastWatchedAt }
        .thenByDescending { it.addedAt }
        .thenBy { it.id }
