// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.runtime.Composable
import io.github.affonsopaulo.stremionow.core.MetaItem
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.stringResource

/** One-line description of a title for cards: type and year. */
@Composable
fun MetaItem.cardSubtitle(): String {
    val type =
        when (kind) {
            "movie" -> stringResource(Res.string.type_movie)
            "series" -> stringResource(Res.string.type_series)
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
        imdbRating?.let { stringResource(Res.string.imdb_rating, it) },
        genres.take(3).joinToString(", ").ifEmpty { null },
    ).joinToString(" · ")

/** Plural name of a content type, for section titles and filters. Unknown types show as sent. */
@Composable
fun kindLabel(kind: String): String =
    when (kind) {
        "movie" -> stringResource(Res.string.kind_movies)
        "series" -> stringResource(Res.string.kind_series)
        "channel" -> stringResource(Res.string.kind_channels)
        "tv" -> stringResource(Res.string.kind_tv)
        else -> kind.replaceFirstChar { it.uppercaseChar() }
    }
