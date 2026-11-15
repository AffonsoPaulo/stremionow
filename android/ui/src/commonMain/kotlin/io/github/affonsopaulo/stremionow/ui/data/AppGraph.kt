// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data

import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.ui.platform.DebugLog
import io.github.affonsopaulo.stremionow.ui.platform.PlatformContext
import io.github.affonsopaulo.stremionow.ui.platform.logWarning
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File

/** Process-wide objects shared by every screen. */
object AppGraph {
    @Volatile private var core: StremioCore? = null

    /**
     * For work that must finish after a screen is gone, such as stopping a torrent. A failure
     * nobody caught is logged rather than taking the whole app down.
     */
    val scope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                CoroutineExceptionHandler { _, failure -> logWarning("Background work failed", failure) },
        )

    /** Whether the core is open already, so the first screen need not wait for it. */
    val isCoreOpen: Boolean get() = core != null

    /** The core, opened on first use with its database in private, non-backed-up storage. */
    fun core(context: PlatformContext): StremioCore =
        core ?: synchronized(this) {
            core ?: StremioCore(coreDataDirectory(context).absolutePath)
                .also { opened ->
                    SecureStore.load(context, SecureStore.STREMIO_AUTH_KEY)?.let(opened::restoreSession)
                    core = opened
                    DeviceSharing.restore(context, opened)
                    DebugLog.enabled = runCatching { opened.settings().debugLogs }.getOrDefault(false)
                }
        }
}

/**
 * Where the core keeps its database and torrent cache: private storage that is not backed up
 * (Android's no-backup folder, the user's application data folder on the desktop).
 */
internal expect fun coreDataDirectory(context: PlatformContext): File

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
