// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.crash

/**
 * Removes secrets and personal details from text bound for a crash report: stack traces,
 * exception messages and log lines. Reports are redacted when they are written and again when
 * they are shown or sent, so a report saved by an older version gets today's rules too.
 *
 * What goes, in this order:
 * - addresses (`scheme://…`, also percent-encoded, JSON-escaped or without the scheme): only
 *   the scheme and host stay, never credentials, path, query or fragment, where debrid and
 *   addon keys live;
 * - magnet links, `Bearer` and `Basic` credentials, and `key=value` pairs whose key names a
 *   secret (`authKey`, `apikey`, `token`, `password`, …);
 * - email addresses, UUIDs, hex strings of 16 digits or more (the loopback relay's secret and
 *   tokens, torrent info hashes), long random-looking tokens, and IMDb ids (they tell what the
 *   user watches);
 * - IP addresses other than loopback;
 * - paths in the user's home folder and on Android's shared storage.
 *
 * Every pattern is anchored at the start of a run of characters, so the cost stays linear in
 * the input even for hostile text; the tests check a few megabytes of it. Applying the
 * redactor to its own output changes nothing.
 */
object Redactor {
    /** Replacement for the private part of an address, and for secrets in `key=value` pairs. */
    const val REDACTED = "[redacted]"

    /**
     * Redacts [text]. [secrets] are exact values known to be secret (shorter than 6 characters
     * they are ignored, to avoid erasing ordinary words); [homeFolder] is the user's home
     * folder, replaced with `~` wherever it appears.
     */
    fun redact(
        text: String,
        secrets: Collection<String> = emptyList(),
        homeFolder: String? = null,
    ): String {
        var result = text
        for (secret in secrets) {
            if (secret.length >= MIN_SECRET_LENGTH) result = result.replace(secret, "[secret]")
        }
        if (homeFolder != null && homeFolder.length > 3) {
            result = result.replace(homeFolder, "~", ignoreCase = true)
            result = result.replace(homeFolder.replace('\\', '/'), "~", ignoreCase = true)
        }
        result = ENCODED_URL.replace(result) { "${it.groupValues[1]}://$REDACTED" }
        result = URL.replace(result) { redactAddress("${it.groupValues[1]}://", it.groupValues[2], it.groupValues[3]) }
        // `//host/path`, which is also what is left of an address whose scheme was disguised.
        result = SCHEMELESS_URL.replace(result) { redactAddress("//", it.groupValues[1], it.groupValues[2]) }
        result = MAGNET.replace(result, "magnet:$REDACTED")
        result = CREDENTIAL.replace(result) { "${it.groupValues[1]} $REDACTED" }
        result =
            SECRET_PAIR.replace(result) { match ->
                val (quote, name, separator, valueQuote) = match.destructured
                "$quote$name$quote$separator$valueQuote$REDACTED"
            }
        result = EMAIL.replace(result, "[email]")
        result = UUID.replace(result, "[id]")
        result = HEX.replace(result, "[hex]")
        result = TOKEN_RUN.replace(result) { match -> if (looksRandom(match.value)) "[token]" else match.value }
        result = IMDB_ID.replace(result, "tt[id]")
        result = IPV4.replace(result) { match -> if (isLoopback(match.value)) match.value else "[ip]" }
        result = IPV6.replace(result) { match -> if (looksLikeIpv6(match.value)) "[ip]" else match.value }
        result = WINDOWS_HOME.replace(result, "~")
        result = UNIX_HOME.replace(result, "~")
        result = HOME_CONTENT.replace(result, "~$1$REDACTED_PATH")
        result = SHARED_STORAGE.replace(result) { "${it.groupValues[1]}$REDACTED_PATH" }
        return result
    }

    private const val MIN_SECRET_LENGTH = 6
    private const val REDACTED_PATH = "[path]"

    /** Characters that end an address in free text. */
    private const val URL_END = """\s"'<>`\\^{|}"""

    /** `https%3A%2F%2Fhost%2F…`: an address inside a query string or a log of one. */
    private val ENCODED_URL = Regex("""(?i)(?<![a-z0-9+.-])([a-z][a-z0-9+.-]{1,15})%3A%2F%2F[^$URL_END]*+""")

    /**
     * `scheme://authority/rest`. The separator may be JSON-escaped (`https:\/\/`). Group 2 is
     * the authority (credentials, host, port), group 3 everything after it.
     */
    private val URL =
        Regex(
            """(?i)(?<![a-z0-9+.-])([a-z][a-z0-9+.-]{1,15}):(?:\\?/){2}([^/?#$URL_END]*+)""" +
                """((?:\\?[/?#](?:[^$URL_END]|\\/)*+)?)""",
        )

    /**
     * `//authority/rest` without a scheme. The lookbehind lets it follow `:` (a scheme the URL
     * pattern missed, such as one with an invisible character in it) but not a path or a word.
     */
    private val SCHEMELESS_URL = Regex("""(?<![/\\\w])//([^/?#$URL_END]++)((?:[/?#][^$URL_END]*+)?)""")

    /** Keeps [prefix] and the host of [authority]; [after] (path, query, fragment) is replaced. */
    private fun redactAddress(
        prefix: String,
        authority: String,
        after: String,
    ): String {
        var rest = after
        // An address redacted before keeps its placeholder, so redacting twice changes nothing.
        val redactedBefore = rest.startsWith("/$REDACTED") && rest.removePrefix("/$REDACTED").all { it in SENTENCE_END }
        if (redactedBefore) rest = rest.removePrefix("/$REDACTED")
        // Sentence punctuation right after an address belongs to the sentence.
        val whole = authority + rest
        val kept = whole.trimEnd { it in SENTENCE_END }
        val trailing = whole.substring(kept.length)
        // Credentials go; so does anything after a colon that is not a port number.
        val hostAndPort = kept.take(authority.length).substringAfterLast('@')
        val port = hostAndPort.substringAfterLast(':', missingDelimiterValue = "")
        val host =
            if (port.isEmpty() || port.all(Char::isDigit) || hostAndPort.endsWith(']')) {
                hostAndPort
            } else {
                hostAndPort.substringBefore(':')
            }
        rest = kept.drop(authority.length)
        val path = if (!redactedBefore && (rest.isEmpty() || rest == "/" || rest == "\\/")) "" else "/$REDACTED"
        return "$prefix$host$path$trailing"
    }

    private const val SENTENCE_END = ".,;:!?)]}"

    private val MAGNET = Regex("""(?i)(?<![a-z0-9])magnet:\?[^$URL_END]*+""")

    private val CREDENTIAL = Regex("""(?i)\b(bearer|basic)\s++[A-Za-z0-9._~+/=-]{8,}+""")

    /**
     * `name=value`, `name: value`, `"name":"value"`: group 1 the quote around the name, 2 the
     * name, 3 the separator, 4 the quote opening the value. A value already redacted is left
     * alone, so redacting twice gives the same text.
     */
    private val SECRET_PAIR =
        Regex(
            """(?i)((?:\\?["'])?)\b(auth[_-]?key|api[_-]?key|access[_-]?token|refresh[_-]?token|""" +
                """id[_-]?token|auth[_-]?token|token|password|passwd|pwd|secret|client[_-]?secret|""" +
                """session(?:[_-]?id)?|sid|key|auth|authorization|""" +
                """cookie|signature|sig)\1(\s*+(?:[:=]|%3[Dd])\s*+)((?:\\?["'])?)(?!\[redacted])[^\s"'&,;}\])\\]++""",
        )

    private val EMAIL =
        Regex("""(?<![\p{L}\p{N}._%+-])[\p{L}\p{N}._%+-]++@[\p{L}\p{N}-]++(?:\.[\p{L}\p{N}-]++)*\.\p{L}{2,}+""")

    private val UUID =
        Regex(
            """(?<![0-9A-Za-z])[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?![0-9A-Za-z])""",
        )

    private val HEX = Regex("""(?<![0-9A-Za-z])[0-9a-fA-F]{16,}+(?![0-9A-Za-z])""")

    /** Runs of key-like characters; [looksRandom] decides which are tokens. */
    private val TOKEN_RUN = Regex("""(?<![A-Za-z0-9_+/=-])[A-Za-z0-9_+/=-]{20,}+""")

    /**
     * A token mixes letters and digits, or is a long run of capitals and digits (Real-Debrid's
     * keys). Identifiers in stack traces have neither shape, and paths split at their slashes.
     */
    private fun looksRandom(run: String): Boolean {
        val segments = run.split('/').filter { it.length >= 20 }
        if (segments.isEmpty()) return false
        return segments.any { segment ->
            val digits = segment.any(Char::isDigit)
            val letters = segment.any(Char::isLetter)
            (digits && letters) || (segment.length >= 32 && segment.all { it in 'A'..'Z' || it in '0'..'9' })
        }
    }

    private val IMDB_ID = Regex("""(?<![A-Za-z0-9])tt\d{6,10}+(?![0-9])""")

    private val IPV4 =
        Regex(
            """(?<![0-9.])(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?![0-9]|\.\d)""",
        )

    private fun isLoopback(address: String) = address.startsWith("127.") || address == "0.0.0.0"

    /** Hex groups and colons; [looksLikeIpv6] tells addresses from times such as `12:00:01`. */
    private val IPV6 = Regex("""(?<![0-9A-Za-z:.])(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?![0-9A-Za-z:])""")

    private fun looksLikeIpv6(candidate: String): Boolean {
        val colons = candidate.count { it == ':' }
        val compressed = "::" in candidate && candidate.any { it != ':' }
        return (compressed || colons >= 4) && candidate != "::1"
    }

    /** `C:\Users\name`, `\\?\C:\Users\name`, `C:/Users/name`. */
    private val WINDOWS_HOME =
        Regex("""(?i)(?<![A-Za-z])[A-Z]:[\\/]{1,2}(?:Users|Documents and Settings)[\\/]{1,2}[^\\/\s"'<>:|*?]++""")

    /** `/home/name`, `/Users/name` (macOS), `/root`. */
    private val UNIX_HOME = Regex("""(?<![\w.~-])(?:/home/[^/\s"'<>:]++|/Users/[^/\s"'<>:]++|/root(?=/|\s|$))""")

    /**
     * What follows `~` stays only inside the app and tool folders; anything else may be the
     * user's own files, so the path is cut up to where it visibly ends (a quote, ` (`, or the
     * end of the line, since folder names can contain spaces).
     */
    private val HOME_CONTENT =
        Regex(
            """(?m)~([\\/])""" +
                """(?!(?:AppData|Library|\.local|\.config|\.cache|\.cargo|\.rustup|\.gradle|\.m2|\.android)[\\/])""" +
                """[^\r\n"'<>|]*?(?=\s\(|["'<>|]|\r|$)""",
        )

    private val SHARED_STORAGE = Regex("""((?:/storage/|/sdcard/|/mnt/media_rw/|/mnt/user/\d+/))[^\s"'<>|]++""")
}
