// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop.display

import io.github.affonsopaulo.stremionow.core.DisplaySwitch
import io.github.affonsopaulo.stremionow.player.VideoFormat
import java.util.Collections

/** The user's display choices, as Settings and the HDR prompt keep them. */
interface DisplayPreferences {
    val hdr: DisplaySwitch
    val matchRefreshRate: Boolean

    /** Whether the user chose HDR for this title ("Only for this title"). */
    fun hdrForTitle(metaId: String): Boolean

    fun rememberHdrForTitle(metaId: String)

    /** Sets HDR to [DisplaySwitch.Always]. */
    fun alwaysHdr()
}

/** The answers to the HDR prompt. */
enum class HdrChoice { OnlyThisTitle, Always, NotNow }

/**
 * The display changes made for one player: HDR on the video's monitor, with the user's consent,
 * and the refresh rate, when the user turned that on. Everything it changes is put back by
 * [restore] (leaving the player, closing the app), and HDR is written to the [journal] before it
 * is turned on, so the next launch puts it back after a crash ([recoverAfterCrash]).
 *
 * Nothing is changed for a stream on its own: HDR needs the user's "Always", "Only for this title"
 * or an answer to the prompt, and the video's properties are only trusted after [VideoFormat]
 * checked them. Methods block on system calls and the database; call them off the interface
 * thread. They are safe to call from several threads.
 */
class DisplaySession(
    private val control: DisplayControl,
    private val preferences: DisplayPreferences,
    private val journal: DisplayJournal,
    private val metaId: String,
) {
    private var target: DisplayTarget? = null
    private var format: VideoFormat? = null

    /** The monitor HDR was turned on for, until it is turned off again. */
    private var hdrChangedOn: DisplayTarget? = null

    /** The monitor whose refresh rate was changed, until it is reset. */
    private var refreshChangedOn: DisplayTarget? = null

    private var restored = false

    /** Whether HDR is on because of this session; mpv may then output HDR. */
    val isHdrActive: Boolean
        @Synchronized get() = hdrChangedOn != null

    init {
        live += this
    }

    /**
     * The video's format is known and it plays on [display]. Matches the refresh rate when the
     * user turned that on, and turns on HDR when the user already chose it. Returns true when the
     * user should be asked about HDR; call [answer] with the reply.
     */
    @Synchronized
    fun onVideo(
        format: VideoFormat,
        display: DisplayTarget?,
    ): Boolean {
        if (restored || this.format != null || display == null) return false
        this.format = format
        target = display
        format.framesPerSecond?.let { matchRefreshRate(display, it) }
        if (!format.isHdr) return false
        val mode = preferences.hdr
        if (mode == DisplaySwitch.NEVER) return false
        val state = control.hdr(display) ?: return false
        if (!state.supported || state.enabled) return false
        return when {
            mode == DisplaySwitch.ALWAYS || preferences.hdrForTitle(metaId) -> {
                turnOnHdr(display)
                false
            }

            else -> {
                true
            }
        }
    }

    /** Applies the user's answer to the HDR prompt. */
    @Synchronized
    fun answer(choice: HdrChoice) {
        if (restored) return
        val display = target ?: return
        when (choice) {
            HdrChoice.OnlyThisTitle -> preferences.rememberHdrForTitle(metaId)
            HdrChoice.Always -> preferences.alwaysHdr()
            HdrChoice.NotNow -> return
        }
        // The display may have changed since the prompt opened (turned on by hand, unplugged).
        val state = control.hdr(display) ?: return
        if (state.supported && !state.enabled) turnOnHdr(display)
    }

    /** Puts back everything this session changed. Safe to call more than once. */
    @Synchronized
    fun restore() {
        if (restored) return
        restored = true
        live -= this
        hdrChangedOn?.let { display ->
            // Only if it is still on: the user may have turned it off by hand meanwhile.
            val state = control.hdr(display)
            val off = state == null || !state.enabled || control.setHdr(display, false)
            // A monitor that is gone keeps the journal, for the next launch to try again.
            if (off && state != null) journal.clear()
            hdrChangedOn = null
        }
        refreshChangedOn?.let { display ->
            control.resetRefreshRate(display)
            refreshChangedOn = null
        }
    }

    private fun turnOnHdr(display: DisplayTarget) {
        // Written first: if the app dies right after the switch, the next launch undoes it.
        runCatching { journal.write(display) }.onFailure {
            log.log(System.Logger.Level.WARNING, "Could not write the display journal; HDR stays off", it)
            return
        }
        val reported = control.setHdr(display, true)
        // Trust the display's state over the call's result: a failed call may still have switched.
        val on = control.hdr(display)?.enabled == true
        if (on) {
            hdrChangedOn = display
        } else {
            journal.clear()
        }
        log.log(System.Logger.Level.INFO, "HDR on ${display.deviceName}: requested=$reported, on=$on")
    }

    private fun matchRefreshRate(
        display: DisplayTarget,
        framesPerSecond: Double,
    ) {
        if (!preferences.matchRefreshRate) return
        val current = control.refreshRate(display) ?: return
        val hertz = RefreshRates.choose(framesPerSecond, current, control.refreshRates(display)) ?: return
        if (control.setRefreshRate(display, hertz)) {
            refreshChangedOn = display
            log.log(System.Logger.Level.INFO, "Refresh rate of ${display.deviceName}: $current Hz -> $hertz Hz")
        } else {
            // A refused switch can still leave a temporary mode behind; drop it.
            control.resetRefreshRate(display)
        }
    }

    companion object {
        private val log = System.getLogger("StremioNow.display")

        /** Sessions that have not restored yet, for [restoreAll] at exit. */
        private val live: MutableSet<DisplaySession> = Collections.synchronizedSet(mutableSetOf())

        /** Restores every live session; for the JVM's shutdown hook. */
        fun restoreAll() {
            val sessions = synchronized(live) { live.toList() }
            sessions.forEach { runCatching(it::restore) }
        }

        /**
         * Turns HDR off again if the journal says the app turned it on and never got to turn it
         * off (a crash, a kill, a power cut). Call once at start, before any player opens.
         */
        fun recoverAfterCrash(
            control: DisplayControl,
            journal: DisplayJournal,
        ) {
            val display = runCatching { journal.read() }.getOrNull() ?: return
            val state = control.hdr(display)
            when {
                // The monitor is not connected: nothing to undo now, and nothing to remember,
                // since the user may change it by hand before it comes back.
                state == null -> log.log(System.Logger.Level.INFO, "HDR journal for an absent display dropped")

                state.enabled -> control.setHdr(display, false)
            }
            journal.clear()
            log.log(System.Logger.Level.INFO, "Display settings restored after an unexpected exit")
        }
    }
}
