// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data

/**
 * Links that open a title in the app, `stremionow://title/<kind>/<id>`, used by the TV home
 * screen's "Continue watching" row. Anything may send the app such a link, so only well formed
 * ones are accepted. Android builds and parses them as `Uri`s (`TitleLink.build`, `TitleLink.parse`).
 */
object TitleLink {
    internal const val SCHEME = "stremionow"
    internal const val HOST = "title"
    private val kindPattern = Regex("[a-z]{1,16}")
    private val idPattern = Regex("[A-Za-z0-9:._-]{1,128}")

    data class Target(
        val kind: String,
        val id: String,
    )

    /** The title for [kind] and [id], or null unless both are well formed. */
    fun target(
        kind: String,
        id: String,
    ): Target? = Target(kind, id).takeIf { kindPattern.matches(kind) && idPattern.matches(id) }
}
