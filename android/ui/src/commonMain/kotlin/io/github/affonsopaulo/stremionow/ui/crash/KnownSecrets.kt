// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.crash

/**
 * Secrets this process knows, for crash reports to remove wherever they appear, on top of the
 * [Redactor]'s patterns: the Stremio session key and the keys in installed addons' addresses.
 * Kept in memory as they pass by, so a crash never has to ask the core or the secret store,
 * either of which may be what failed.
 */
object KnownSecrets {
    @Volatile private var values: Set<String> = emptySet()

    /** What is known now; never blocks. */
    fun snapshot(): Set<String> = values

    fun add(secret: String) {
        if (secret.length < MIN_LENGTH) return
        synchronized(this) { values = values + secret }
    }

    /** The parts of [manifestUrls] that look like keys (see [keyLikeParts]). */
    fun addAddressKeys(manifestUrls: List<String>) {
        val keys = manifestUrls.flatMap(::keyLikeParts)
        if (keys.isEmpty()) return
        synchronized(this) { values = values + keys }
    }

    private const val MIN_LENGTH = 8
}

/**
 * The parts of an addon's address that look like keys: in its path and query, split at the
 * separators addons use for options (`/`, `|`, `&`, `;`, `,`, and `%7C`), the values of
 * `name=value` options made of 8 or more letters, digits, `-` and `_` with both letters and digits,
 * such as Torrentio's `realdebrid=KEY`. Option words (`sort=qualitysize`) have no digits.
 */
internal fun keyLikeParts(url: String): List<String> {
    val afterScheme = url.substringAfter("://", missingDelimiterValue = url)
    return afterScheme
        .substringAfter('/', missingDelimiterValue = "")
        .substringBefore('#')
        .replace("%7C", "|", ignoreCase = true)
        .split('/', '|', '&', ';', ',', '?')
        .map { it.substringAfter('=') }
        .filter { part ->
            part.length >= 8 &&
                part.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' } &&
                part.any(Char::isDigit) &&
                part.any(Char::isLetter)
        }.distinct()
}
