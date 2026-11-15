// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import io.github.affonsopaulo.stremionow.ui.l10n.DesktopStrings
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import java.awt.Image
import javax.swing.JOptionPane

/**
 * Errors the desktop app shows in a system dialog before its window exists, so that a failed
 * start does not end without a word. The text is the app's own, in the interface language; if
 * even the string tables cannot be read, a short English and Portuguese text stands in.
 */
object StartupErrors {
    /** Something the app ships with (a native library, a resource) is missing or damaged. */
    fun showStartFailed(icons: List<Image>) {
        show(
            icons,
            title = text(Res.string.startup_error_title.key) ?: FALLBACK_TITLE,
            body = text(Res.string.startup_error_body.key) ?: FALLBACK_BODY,
        )
    }

    /** The core could not open its data in [folder]. */
    fun showCoreUnavailable(
        icons: List<Image>,
        folder: String,
    ) {
        show(
            icons,
            title = text(Res.string.core_unavailable_title.key) ?: FALLBACK_TITLE,
            body = text(Res.string.core_unavailable_body.key) ?: FALLBACK_CORE_BODY,
            detail = folder,
        )
    }

    private fun text(key: String): String? =
        runCatching { DesktopStrings.string(key) }.getOrNull()?.takeIf { it != key }

    /** Shows [title], [body] and, on a line of its own and never broken, [detail]. */
    private fun show(
        icons: List<Image>,
        title: String,
        body: String,
        detail: String? = null,
    ) {
        // Also on the console, for a start from a terminal or where no dialog can show.
        System.err.println(listOfNotNull(title, body, detail).joinToString("\n"))
        runCatching {
            // One line per item: JOptionPane does not wrap text by itself.
            val message = (listOf(title) + wrap(body) + listOfNotNull(detail)).toTypedArray<Any>()
            val dialog = JOptionPane(message, JOptionPane.ERROR_MESSAGE).createDialog(APP_NAME)
            dialog.setIconImages(icons)
            dialog.isVisible = true
            dialog.dispose()
        }
    }

    /** Breaks [text] into lines of at most [WIDTH] characters at spaces, unless one word is longer. */
    internal fun wrap(text: String): List<String> {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        for (word in text.split(' ')) {
            if (line.isNotEmpty() && line.length + 1 + word.length > WIDTH) {
                lines += line.toString()
                line.clear()
            }
            if (line.isNotEmpty()) line.append(' ')
            line.append(word)
        }
        if (line.isNotEmpty()) lines += line.toString()
        return lines
    }

    private const val APP_NAME = "StremioNow"
    private const val WIDTH = 64
    private const val FALLBACK_TITLE = "StremioNow couldn't start / Não foi possível iniciar o StremioNow"
    private const val FALLBACK_BODY =
        "Part of the app is missing or damaged; installing it again usually fixes this. / " +
            "Uma parte do app está faltando ou danificada; instalar de novo costuma resolver."
    private const val FALLBACK_CORE_BODY =
        "StremioNow can't open its data folder. / O StremioNow não consegue abrir a pasta dos seus dados."
}
