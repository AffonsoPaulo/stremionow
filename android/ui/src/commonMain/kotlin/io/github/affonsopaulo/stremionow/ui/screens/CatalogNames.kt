// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.runtime.Composable
import io.github.affonsopaulo.stremionow.core.CatalogInfo
import io.github.affonsopaulo.stremionow.core.CatalogRole
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource

/*
 * Addons name their catalogs for their own lists ("Popular", "New", "Featured"), which says
 * little on a home screen that mixes movies and series from several addons. Catalogs the core
 * recognizes get a clear, translated name; the others keep their addon's name for them, with
 * the addon named apart, in a quieter label.
 */

/** A row's heading and its quieter label, such as the addon it comes from. */
data class RowName(
    val title: String,
    val caption: String? = null,
)

/**
 * Name of a catalog's row: "Trending movies" for Cinemeta's popular movies, or the catalog's
 * own name with "by" and its addon in the caption. When [sameNameElsewhere], another row has
 * the same name (an addon's "Popular" movies and series), so the caption adds the type too.
 * A catalog named only by a technical id ("top", "year") gets a plain name instead.
 */
@Composable
fun catalogRowName(
    catalog: CatalogInfo,
    sameNameElsewhere: Boolean = false,
): RowName {
    knownCatalogTitle(catalog)?.let { title ->
        // Cinemeta's best rated rows read as its own picks, so they credit it.
        val caption = if (catalog.role == CatalogRole.FEATURED) addonCredit(catalog) else null
        return RowName(title, caption)
    }
    val kindCaption = if (sameNameElsewhere) kindLabel(catalog.kind) else null
    when (technicalName(catalog)) {
        TechnicalName.POPULAR -> {
            return RowName(stringResource(Res.string.row_popular_on, catalog.addonName), kindCaption)
        }

        TechnicalName.NEW -> {
            return RowName(stringResource(Res.string.row_new_on, catalog.addonName), kindCaption)
        }

        TechnicalName.OTHER -> {
            // Often the addon's own name squashed ("publicdomainmovies"): the addon names it.
            val squashed = { text: String -> text.filter { it.isLetterOrDigit() }.lowercase() }
            if (catalog.name == catalog.catalogId || squashed(catalog.name) == squashed(catalog.addonName)) {
                return RowName(catalog.addonName, kindCaption ?: kindLabel(catalog.kind))
            }
            return RowName(readableName(catalog), addonCredit(catalog, sameNameElsewhere))
        }

        null -> {
            return RowName(catalog.name, addonCredit(catalog, sameNameElsewhere))
        }
    }
}

/** "by Cinemeta", or "Series · by Torrentio" when the type tells two rows apart. */
@Composable
private fun addonCredit(
    catalog: CatalogInfo,
    withKind: Boolean = false,
): String {
    val credit = stringResource(Res.string.row_caption_by, catalog.addonName)
    return if (withKind) stringResource(Res.string.row_caption_kind_by, kindLabel(catalog.kind), credit) else credit
}

private enum class TechnicalName { POPULAR, NEW, OTHER }

/**
 * Whether an addon left its catalog a technical name: none at all (the id stands in), or a
 * lowercase word such as "top" or "year". Popular and new lists get a sentence of their own.
 */
private fun technicalName(catalog: CatalogInfo): TechnicalName? {
    val name = catalog.name.trim()
    val technical =
        name.isEmpty() ||
            name == catalog.catalogId ||
            (name.none { it.isWhitespace() || it.isUpperCase() } && name.any { it.isLetter() })
    if (!technical) return null
    return when (name.lowercase().ifEmpty { catalog.catalogId.lowercase() }) {
        "top", "popular", "trending", "hot" -> TechnicalName.POPULAR
        "year", "new", "latest", "recent", "last" -> TechnicalName.NEW
        else -> TechnicalName.OTHER
    }
}

/** A technical name made readable: separators become spaces and the first letter a capital. */
private fun readableName(catalog: CatalogInfo): String =
    catalog.name
        .ifBlank { catalog.catalogId }
        .replace('_', ' ')
        .replace('-', ' ')
        .replace('.', ' ')
        .trim()
        .replaceFirstChar { it.uppercaseChar() }

/** Short name of a catalog among others of one type, as in the filters of Movies and Series. */
@Composable
fun catalogShortName(catalog: CatalogInfo): String =
    when (catalog.role) {
        CatalogRole.TRENDING -> stringResource(Res.string.catalog_trending)
        CatalogRole.NEW_RELEASES -> stringResource(Res.string.catalog_new_releases)
        CatalogRole.FEATURED -> stringResource(Res.string.catalog_featured)
        CatalogRole.OTHER -> catalog.name
    }

/** The translated title of a catalog the core recognizes, for movies and series; else null. */
@Composable
private fun knownCatalogTitle(catalog: CatalogInfo): String? {
    val movies = catalog.kind == "movie"
    if (!movies && catalog.kind != "series") return null
    return when (catalog.role) {
        CatalogRole.TRENDING -> {
            stringResource(if (movies) Res.string.home_trending_movies else Res.string.home_trending_series)
        }

        CatalogRole.NEW_RELEASES -> {
            stringResource(if (movies) Res.string.home_new_movies else Res.string.home_new_series)
        }

        CatalogRole.FEATURED -> {
            stringResource(if (movies) Res.string.home_featured_movies else Res.string.home_featured_series)
        }

        CatalogRole.OTHER -> {
            null
        }
    }
}
