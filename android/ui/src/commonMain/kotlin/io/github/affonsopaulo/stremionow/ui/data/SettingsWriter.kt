// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data

import io.github.affonsopaulo.stremionow.core.AppSettings
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.ui.platform.logWarning
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Every change to the core's settings goes through here, one at a time and in the order it was
 * asked for, and each is applied on top of the settings as last saved. Changes made in parallel
 * used to read the same saved settings and overwrite each other: two quick presses of "+" on the
 * subtitle size saved one step, not two.
 */
object SettingsWriter {
    private val queue by lazy { SerialQueue(AppGraph.scope) }

    /** Saves [change] in the background; it finishes even when the screen that asked is gone. */
    fun post(
        core: StremioCore,
        change: (AppSettings) -> AppSettings,
    ) = queue.post { core.updateSettings(change(core.settings())) }

    /** Saves [change] and waits for it; throws what the core threw. */
    suspend fun update(
        core: StremioCore,
        change: (AppSettings) -> AppSettings,
    ) = queue.run { core.updateSettings(change(core.settings())) }

    /** The saved settings, once every change asked for earlier is saved. */
    suspend fun read(core: StremioCore): AppSettings = queue.run { core.settings() }
}

/**
 * Runs blocking jobs one after another on [scope], in the order they were handed over. [post]
 * and [run] queue at once, without suspending, so the calls of one thread keep their order.
 */
internal class SerialQueue(
    scope: CoroutineScope,
) {
    private val jobs = Channel<() -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch { for (job in jobs) job() }
    }

    /** Queues [job]; a failure is logged, never thrown, so later jobs still run. */
    fun post(job: () -> Unit) {
        jobs.trySend {
            try {
                job()
            } catch (failure: Exception) {
                logWarning("Could not save the settings", failure)
            }
        }
    }

    /** Queues [job] and waits for its result; throws what it threw. */
    suspend fun <T> run(job: () -> T): T {
        val result = CompletableDeferred<T>()
        jobs.trySend {
            try {
                result.complete(job())
            } catch (failure: Exception) {
                result.completeExceptionally(failure)
            }
        }
        return result.await()
    }
}
