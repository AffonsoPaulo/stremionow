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

    /** The user answered "Not now" in this player: other videos it plays do not ask again. */
    private var hdrDeclined = false

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
     * The format of a video is known and it plays on [display]: the first one, or each next one
     * the player moves on to. Matches the refresh rate when the user turned that on, and turns on
     * HDR when the user already chose it. What was changed for an earlier video and does not suit
     * this one goes back: HDR for an SDR video, a refresh rate made for another frame rate.
     * Returns true when the user should be asked about HDR; call [answer] with the reply.
     */
    @Synchronized
    fun onVideo(
        format: VideoFormat,
        display: DisplayTarget?,
    ): Boolean {
        if (restored || display == null) return false
        target = display
        matchRefreshRate(display, format.framesPerSecond)
        // HDR stays on only for an HDR video on the monitor it was turned on for.
        hdrChangedOn?.takeIf { !format.isHdr || it != display }?.let { turnOffHdr() }
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
                !hdrDeclined
            }
        }
    }

    /** Applies the user's answer to the HDR prompt. */
    @Synchronized
    fun answer(choice: HdrChoice) {
        if (restored) return
        val display = target ?: return
        when (choice) {
            HdrChoice.OnlyThisTitle -> {
                preferences.rememberHdrForTitle(metaId)
            }

            HdrChoice.Always -> {
                preferences.alwaysHdr()
            }

            HdrChoice.NotNow -> {
                hdrDeclined = true
                return
            }
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
        turnOffHdr()
        resetRefreshRate()
    }

    /** Turns HDR off again, if this session turned it on. */
    private fun turnOffHdr() {
        val display = hdrChangedOn ?: return
        // Only if it is still on: the user may have turned it off by hand meanwhile.
        val state = control.hdr(display)
        val off = state == null || !state.enabled || control.setHdr(display, false)
        // A monitor that is gone keeps the journal, for the next launch to try again.
        if (off && state != null) journal.clear()
        hdrChangedOn = null
        log.log(System.Logger.Level.INFO, "HDR on ${display.deviceName} turned off again: off=$off")
    }

    /** Puts back the refresh rate, if this session changed it. */
    private fun resetRefreshRate() {
        val display = refreshChangedOn ?: return
        control.resetRefreshRate(display)
        refreshChangedOn = null
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
        framesPerSecond: Double?,
    ) {
        // A rate chosen for an earlier video stays while it suits this one (24 and 23.976 fps
        // both suit 120 Hz), which spares the screen a blank switch; otherwise the saved mode
        // comes back before choosing again.
        refreshChangedOn?.let { changed ->
            val rate = if (changed == display) control.refreshRate(changed) else null
            val suits = framesPerSecond != null && rate != null && RefreshRates.suits(framesPerSecond, rate)
            if (suits && preferences.matchRefreshRate) return
            resetRefreshRate()
        }
        if (!preferences.matchRefreshRate || framesPerSecond == null) return
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
