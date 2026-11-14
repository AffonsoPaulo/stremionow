// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/** A piece of text: plain, or a country's flag written as an emoji. */
internal sealed interface FlagTextPart {
    data class Plain(
        val text: String,
    ) : FlagTextPart

    /** [code] is the country's two-letter code, lowercase ("gb", "br"). */
    data class Flag(
        val code: String,
    ) : FlagTextPart
}

private const val REGIONAL_A = 0x1F1E6
private const val REGIONAL_Z = 0x1F1FF

private fun isRegional(codePoint: Int) = codePoint in REGIONAL_A..REGIONAL_Z

/**
 * Splits [text] around flag emojis: two regional indicator symbols in a row, which spell a
 * country's code. A lone regional indicator stays plain text.
 */
internal fun splitFlags(text: String): List<FlagTextPart> {
    val parts = mutableListOf<FlagTextPart>()
    val plain = StringBuilder()
    var index = 0
    while (index < text.length) {
        val first = text.codePointAt(index)
        val firstLength = Character.charCount(first)
        val second = if (index + firstLength < text.length) text.codePointAt(index + firstLength) else -1
        if (isRegional(first) && isRegional(second)) {
            if (plain.isNotEmpty()) {
                parts += FlagTextPart.Plain(plain.toString())
                plain.clear()
            }
            val code = charArrayOf('a' + (first - REGIONAL_A), 'a' + (second - REGIONAL_A)).concatToString()
            parts += FlagTextPart.Flag(code)
            index += firstLength + Character.charCount(second)
        } else {
            plain.appendCodePoint(first)
            index += firstLength
        }
    }
    if (plain.isNotEmpty()) parts += FlagTextPart.Plain(plain.toString())
    return parts
}

/**
 * The picture of country [code]'s flag, where the system's emoji font has none (Windows draws
 * flag emojis as two letters); null where the font draws flags itself, or for an unknown code.
 */
@Composable
internal expect fun rememberFlagPainter(code: String): Painter?

/**
 * Text whose flag emojis show as flags everywhere: as pictures inline with the text where the
 * system cannot draw them (see [rememberFlagPainter]), as the emojis elsewhere.
 */
@Composable
fun SnFlagText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val parts = splitFlags(text)
    val flags = parts.filterIsInstance<FlagTextPart.Flag>().map { it.code }.distinct()
    val painters: Map<String, Painter> =
        buildMap { for (code in flags) rememberFlagPainter(code)?.let { put(code, it) } }
    if (painters.isEmpty()) {
        BasicText(text, modifier, style, minLines = minLines, maxLines = maxLines, overflow = overflow)
        return
    }
    val annotated =
        buildAnnotatedString {
            for (part in parts) {
                when (part) {
                    is FlagTextPart.Plain -> {
                        append(part.text)
                    }

                    is FlagTextPart.Flag -> {
                        if (part.code in painters) {
                            appendInlineContent("flag:${part.code}", part.code.uppercase())
                        } else {
                            append(part.code.uppercase())
                        }
                    }
                }
            }
        }
    val inline =
        painters.mapKeys { "flag:${it.key}" }.mapValues { (_, painter) ->
            InlineTextContent(Placeholder(1.33.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
                Image(
                    painter,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(2.dp)),
                    // The bundled flags have no size of their own, only a 4:3 view box, which the
                    // painter would fit into a square; the placeholder is 4:3 already.
                    contentScale = ContentScale.FillBounds,
                )
            }
        }
    BasicText(
        annotated,
        modifier,
        style,
        minLines = minLines,
        maxLines = maxLines,
        overflow = overflow,
        inlineContent = inline,
    )
}
