// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data

import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.ui.platform.DebugLog
import io.github.affonsopaulo.stremionow.ui.platform.PlatformContext
import io.github.affonsopaulo.stremionow.ui.platform.logWarning
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
    fun core(context: PlatformContext): StremioCore =
        core ?: synchronized(this) {
            core ?: openStremioCore(context)
                .also { opened ->
                    SecureStore.load(context, SecureStore.STREMIO_AUTH_KEY)?.let(opened::restoreSession)
                    core = opened
                    DeviceSharing.restore(context, opened)
                    DebugLog.enabled = runCatching { opened.settings().debugLogs }.getOrDefault(false)
                }
        }

    /**
     * Opens the core now, so that a failure (a folder that cannot be written, a damaged
     * database) can be shown before any screen needs it. Later calls return the open core; after
     * a failure, the next call tries again.
     */
    fun openCore(context: PlatformContext): Result<StremioCore> =
        runCatching { core(context) }.onFailure { logWarning("Could not open the core", it) }

    /** The folder the core keeps its data in, to name when it cannot be opened. */
    fun coreFolder(context: PlatformContext): String = coreDataDirectory(context).absolutePath
}

/** The core, with its data in [coreDataDirectory] and its torrent cache in [coreCacheDirectory]. */
private fun openStremioCore(context: PlatformContext): StremioCore {
    val data = coreDataDirectory(context).absolutePath
    val cache = coreCacheDirectory() ?: return StremioCore(data)
    return StremioCore.withCacheDir(data, cache.absolutePath)
}

/** Where the core keeps its torrent cache, or null for its default folder inside [coreDataDirectory]. */
internal expect fun coreCacheDirectory(): File?

/**
 * Where the core keeps its database (and its torrent cache, unless [coreCacheDirectory] names
 * another folder): private storage that is not backed up
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
