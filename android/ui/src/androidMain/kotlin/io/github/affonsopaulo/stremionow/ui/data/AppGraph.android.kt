// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data

import io.github.affonsopaulo.stremionow.ui.platform.PlatformContext
import java.io.File

internal actual fun coreDataDirectory(context: PlatformContext): File =
    File(context.applicationContext.noBackupFilesDir, "core")
