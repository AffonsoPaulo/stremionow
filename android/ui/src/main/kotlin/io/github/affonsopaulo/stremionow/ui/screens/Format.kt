// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.affonsopaulo.stremionow.core.MetaItem
import io.github.affonsopaulo.stremionow.ui.R

/** One-line description of a title for cards: type and year. */
@Composable
fun MetaItem.cardSubtitle(): String {
    val type =
        when (kind) {
            "movie" -> stringResource(R.string.type_movie)
            "series" -> stringResource(R.string.type_series)
            else -> null
        }
    return listOfNotNull(type, releaseInfo).joinToString(" · ")
}

/** Year, rating and genres for the hero and details screens. */
@Composable
fun MetaItem.metaLine(runtime: String? = null): String =
    listOfNotNull(
        releaseInfo,
        runtime,
        imdbRating?.let { stringResource(R.string.imdb_rating, it) },
        genres.take(3).joinToString(", ").ifEmpty { null },
    ).joinToString(" · ")

/** Plural name of a content type, for section titles and filters. Unknown types show as sent. */
@Composable
fun kindLabel(kind: String): String =
    when (kind) {
        "movie" -> stringResource(R.string.kind_movies)
        "series" -> stringResource(R.string.kind_series)
        "channel" -> stringResource(R.string.kind_channels)
        "tv" -> stringResource(R.string.kind_tv)
        else -> kind.replaceFirstChar { it.uppercaseChar() }
    }
