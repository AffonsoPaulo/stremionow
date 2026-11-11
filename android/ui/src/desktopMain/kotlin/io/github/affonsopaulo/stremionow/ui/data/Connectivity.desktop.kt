// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data

import io.github.affonsopaulo.stremionow.ui.platform.PlatformContext

actual object Connectivity {
    /** Computers rarely sit on a metered link, and the desktop has no portable way to tell; never block. */
    actual fun onMobileData(context: PlatformContext): Boolean = false
}
