// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.platform

import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI

internal actual fun copyToClipboard(
    context: PlatformContext,
    label: String,
    text: String,
    sensitive: Boolean,
) {
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
        .onFailure { logWarning("Could not copy to the clipboard", it) }
}

/** No share sheet on the desktop; copying does that job. */
internal actual fun canShareText(context: PlatformContext): Boolean = false

internal actual fun shareText(
    context: PlatformContext,
    subject: String,
    text: String,
): Boolean = false

internal actual fun openWebPage(
    context: PlatformContext,
    url: String,
    allowHttp: Boolean,
): Boolean {
    if (!isWebPage(url, allowHttp)) return false
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) return false
    // Starting the browser can take a moment; keep it off the interface thread.
    AppGraph.scope.launch {
        runCatching { Desktop.getDesktop().browse(uri) }.onFailure { logWarning("Could not open a link", it) }
    }
    return true
}
