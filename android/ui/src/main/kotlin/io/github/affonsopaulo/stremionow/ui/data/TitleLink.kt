// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data

import android.net.Uri

/**
 * Links that open a title in the app, `stremionow://title/<kind>/<id>`, used by the TV home
 * screen's "Continue watching" row. Anything may send the activity such a link, so only well
 * formed ones are accepted.
 */
object TitleLink {
    private const val SCHEME = "stremionow"
    private const val HOST = "title"
    private val kindPattern = Regex("[a-z]{1,16}")
    private val idPattern = Regex("[A-Za-z0-9:._-]{1,128}")

    data class Target(
        val kind: String,
        val id: String,
    )

    fun build(
        kind: String,
        id: String,
    ): Uri =
        Uri
            .Builder()
            .scheme(SCHEME)
            .authority(HOST)
            .appendPath(kind)
            .appendPath(id)
            .build()

    /** The title a link points to, or null for anything else. */
    fun parse(uri: Uri?): Target? {
        if (uri == null || uri.scheme != SCHEME || uri.host != HOST) return null
        val segments = uri.pathSegments
        if (segments.size != 2) return null
        val (kind, id) = segments
        return Target(kind, id).takeIf { kindPattern.matches(kind) && idPattern.matches(id) }
    }
}
