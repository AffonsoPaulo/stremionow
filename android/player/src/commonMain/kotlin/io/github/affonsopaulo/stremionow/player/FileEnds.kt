// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.player

/**
 * Tells, among the ends of file mpv reports, the one that means the file asked for last could not
 * be opened. mpv reports its events in order, and every file it opens starts with a start of file:
 * an end that comes before the start of the file loaded last belongs to the file it replaced, and
 * an end after a stop is that stop. Players call [onLoad], [onStop] and [onStart] as they happen.
 *
 * A flag set by the stop and cleared by its end would stay set when nothing was open to stop (an
 * idle player sends no end of file), and then hide the next file's failure behind an endless
 * loading screen.
 */
internal class FileEnds {
    /** mpv started opening the file asked for last. */
    private var started = false

    /** The player was stopped since the last file was asked for. */
    private var stopped = false

    /** A file is asked for: what ends from now on until it starts is the previous one's. */
    @Synchronized
    fun onLoad() {
        started = false
        stopped = false
    }

    /** The player was stopped: the end that follows, if anything was open, is not a failure. */
    @Synchronized
    fun onStop() {
        stopped = true
    }

    /** mpv started opening a file. */
    @Synchronized
    fun onStart() {
        started = true
    }

    /**
     * Whether an end of file means the file asked for last failed to open, given whether that file
     * had [loaded] and, when mpv tells it, whether it ended on an error ([failed]; null where the
     * player cannot tell why a file ended).
     */
    @Synchronized
    fun isOpenFailure(
        loaded: Boolean,
        failed: Boolean?,
    ): Boolean {
        if (!started || stopped || loaded) return false
        return failed ?: true
    }
}
