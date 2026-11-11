// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data

import android.net.Uri

/** The link that opens the title [kind]/[id] in the app. */
fun TitleLink.build(
    kind: String,
    id: String,
): Uri =
    Uri
        .Builder()
        .scheme(TitleLink.SCHEME)
        .authority(TitleLink.HOST)
        .appendPath(kind)
        .appendPath(id)
        .build()

/** The title a link points to, or null for anything else. */
fun TitleLink.parse(uri: Uri?): TitleLink.Target? {
    if (uri == null || uri.scheme != TitleLink.SCHEME || uri.host != TitleLink.HOST) return null
    val segments = uri.pathSegments
    if (segments.size != 2) return null
    val (kind, id) = segments
    return target(kind, id)
}
