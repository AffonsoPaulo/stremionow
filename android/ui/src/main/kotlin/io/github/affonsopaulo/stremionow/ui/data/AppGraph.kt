// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data

import android.content.Context
import io.github.affonsopaulo.stremionow.core.StremioCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File

/** Process-wide objects shared by every screen. */
object AppGraph {
    @Volatile private var core: StremioCore? = null

    /** For work that must finish after a screen is gone, such as stopping a torrent. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The core, opened on first use with its database in private, non-backed-up storage. */
    fun core(context: Context): StremioCore =
        core ?: synchronized(this) {
            core ?: StremioCore(File(context.applicationContext.noBackupFilesDir, "core").absolutePath)
                .also { core = it }
        }
}

/** Runs a blocking core call (database access) off the main thread. */
suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

/** State of something loaded asynchronously. */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>

    data class Ready<T>(
        val value: T,
    ) : Loadable<T>

    data class Failed(
        val error: Throwable,
    ) : Loadable<Nothing>
}
