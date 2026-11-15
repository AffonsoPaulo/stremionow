// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop.display

import io.github.affonsopaulo.stremionow.core.DisplaySwitch
import io.github.affonsopaulo.stremionow.core.StremioCore
import io.github.affonsopaulo.stremionow.ui.data.DesktopPaths
import io.github.affonsopaulo.stremionow.ui.data.SettingsWriter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** The app's display handling: one system implementation, one journal, one queue of calls. */
object DesktopDisplays {
    private val started = AtomicBoolean(false)

    /** The system's display settings, or null where the app does not change them. */
    internal val control: DisplayControl? by lazy { DisplayControl.current() }

    private val journal: DisplayJournal by lazy {
        FileDisplayJournal(File(DesktopPaths.appData, "display-restore.properties"))
    }

    /** Display calls block (a mode switch takes a moment) and run one at a time, off the interface. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** Whether the app can change displays here (Windows), so Settings offers it. */
    val isSupported: Boolean get() = control != null

    /**
     * Undoes what a previous run left switched (see [DisplaySession.recoverAfterCrash]) and puts
     * displays back when the app exits. Call once at start.
     */
    fun start() {
        val control = control ?: return
        if (!started.compareAndSet(false, true)) return
        runCatching { DisplaySession.recoverAfterCrash(control, journal) }
        Runtime.getRuntime().addShutdownHook(Thread(DisplaySession::restoreAll, "display-restore"))
    }

    /** A session for a player of the title [metaId], or null where displays are not changed. */
    internal fun session(
        core: StremioCore,
        metaId: String,
    ): DisplaySession? = control?.let { DisplaySession(it, CoreDisplayPreferences(core), journal, metaId) }
}

/** [DisplayPreferences] kept by the core: Settings and the per-title HDR choices. */
private class CoreDisplayPreferences(
    private val core: StremioCore,
) : DisplayPreferences {
    override val hdr: DisplaySwitch
        get() = runCatching { core.settings().hdr }.getOrDefault(DisplaySwitch.NEVER)

    override val matchRefreshRate: Boolean
        get() = runCatching { core.settings().matchRefreshRate }.getOrDefault(false)

    override fun hdrForTitle(metaId: String): Boolean = runCatching { core.hdrForTitle(metaId) }.getOrDefault(false)

    override fun rememberHdrForTitle(metaId: String) {
        runCatching { core.setHdrForTitle(metaId) }
    }

    override fun alwaysHdr() {
        SettingsWriter.post(core) { it.copy(hdr = DisplaySwitch.ALWAYS) }
    }
}
