// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.platform

import java.net.URI
import java.net.URISyntaxException

/**
 * [url] as a [URI] for the browser, or null unless it is an http(s) address with a host.
 *
 * Addons' links are often not strictly valid: Torrentio's configure link separates its options
 * with `|`, which [URI] refuses. Characters a URI may not hold are percent-encoded (as UTF-8),
 * and everything else, existing `%XX` escapes included, stays as it was, so the browser gets the
 * same address.
 */
internal fun webUri(url: String): URI? {
    val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
    if (scheme != "http" && scheme != "https") return null
    val uri =
        try {
            URI(encodeIllegal(url))
        } catch (_: URISyntaxException) {
            return null
        }
    return uri.takeIf { it.host != null }
}

/** [url] with the characters a URI may not hold percent-encoded; see [webUri]. */
internal fun encodeIllegal(url: String): String {
    val authorityStart = url.indexOf("://").let { if (it < 0) 0 else it + 3 }
    val authorityEnd =
        url.indexOfAny(charArrayOf('/', '?', '#'), authorityStart).let { if (it < 0) url.length else it }
    var fragment = false
    return buildString {
        var index = 0
        while (index < url.length) {
            val char = url[index]
            val keep =
                when {
                    char == '%' -> isEscape(url, index)

                    // Brackets only belong around an IPv6 host.
                    char == '[' || char == ']' -> index in authorityStart until authorityEnd

                    // A second '#' cannot be part of the fragment.
                    char == '#' -> !fragment.also { fragment = true }

                    else -> char.code < 0x80 && (char.isLetterOrDigit() || char in LEGAL)
                }
            if (keep) {
                append(char)
                index++
            } else {
                // A whole character (two for those outside the Basic Multilingual Plane), as UTF-8.
                val end = if (char.isHighSurrogate() && index + 1 < url.length) index + 2 else index + 1
                for (byte in url.substring(index, end).encodeToByteArray()) {
                    val code = byte.toInt() and 0xFF
                    append('%').append(HEX[code shr 4]).append(HEX[code and 0xF])
                }
                index = end
            }
        }
    }
}

private fun isEscape(
    text: String,
    index: Int,
): Boolean = index + 2 < text.length && text[index + 1].isHexDigit() && text[index + 2].isHexDigit()

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/** What RFC 3986 allows besides letters, digits, '%', '#' and brackets. */
private const val LEGAL = "-._~:/?@!$&'()*+,;="

private const val HEX = "0123456789ABCDEF"
