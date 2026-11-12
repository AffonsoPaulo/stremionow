// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import io.github.affonsopaulo.stremionow.ui.theme.SnColors
import io.nayuki.qrcodegen.QrCode
import kotlin.math.floor

/** Modules of light border every side needs for a reader to find the code. */
private const val QUIET_ZONE = 4

/**
 * Whether [text] fits in a QR code. The largest code holds about 2,950 bytes at the lowest
 * error correction; a long configured addon link can exceed that.
 */
fun fitsQrCode(text: String): Boolean = encode(text) != null

private fun encode(text: String): QrCode? = runCatching { QrCode.encodeText(text, QrCode.Ecc.LOW) }.getOrNull()

/**
 * [text] as a QR code, dark modules on white with the quiet zone included, at the lowest error
 * correction so a long link stays as sparse as possible. A phone's camera opens the link, which
 * is how a TV, usually without a browser, hands a page to the user. Draws nothing when the text
 * does not fit (see [fitsQrCode]).
 */
@Composable
fun SnQrCode(
    text: String,
    size: Dp,
    description: String,
    modifier: Modifier = Modifier,
) {
    val code = remember(text) { encode(text) } ?: return
    Canvas(modifier.size(size).semantics { contentDescription = description }) {
        drawRect(SnColors.inverse)
        val modules = code.size + QUIET_ZONE * 2
        // Whole pixels per module keep the edges crisp; the code is centered in what is left.
        val cell = floor(minOf(this.size.width, this.size.height) / modules).coerceAtLeast(1f)
        val origin = (minOf(this.size.width, this.size.height) - cell * modules) / 2 + cell * QUIET_ZONE
        for (y in 0 until code.size) {
            for (x in 0 until code.size) {
                if (code.getModule(x, y)) {
                    drawRect(
                        color = SnColors.bg,
                        topLeft = Offset(origin + x * cell, origin + y * cell),
                        size = Size(cell, cell),
                    )
                }
            }
        }
    }
}
