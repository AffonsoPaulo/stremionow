// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.player

/**
 * How much of the stream the player keeps in memory: read ahead of the playback position, and
 * kept behind it after it played, so skipping a few seconds either way does not open the source
 * again. Only what was read or played fills it. The torrent cache, on disk, is separate.
 *
 * For a 25 Mbit/s 4K file, 128 MiB is about 40 seconds; for a 1080p one, several minutes.
 */
enum class StreamMemory(
    val aheadMiB: Int,
    val behindMiB: Int,
) {
    LIGHT(aheadMiB = 64, behindMiB = 32),
    BALANCED(aheadMiB = 128, behindMiB = 128),
    SMOOTH(aheadMiB = 256, behindMiB = 256),
    ;

    /** This level's sizes. */
    val sizes: StreamMemorySizes get() = StreamMemorySizes(aheadMiB, behindMiB)

    companion object {
        private const val GIB = 1024L * 1024 * 1024

        /** The level for a device with [totalBytes] of memory: light up to 8 GB, balanced up to 16 GB, smooth above. */
        fun forDeviceMemory(totalBytes: Long): StreamMemory =
            when {
                totalBytes <= 8 * GIB -> LIGHT
                totalBytes <= 16 * GIB -> BALANCED
                else -> SMOOTH
            }
    }
}

/**
 * Exact sizes of the stream memory, in MiB, for the user's "Custom" choice. Both stay within
 * [AHEAD_RANGE] and [BEHIND_RANGE], which the core's settings enforce too.
 */
data class StreamMemorySizes(
    val aheadMiB: Int,
    val behindMiB: Int,
) {
    init {
        require(aheadMiB in AHEAD_RANGE) { "aheadMiB must be in $AHEAD_RANGE" }
        require(behindMiB in BEHIND_RANGE) { "behindMiB must be in $BEHIND_RANGE" }
    }

    /** The level with exactly these sizes, or null when they are custom. */
    val level: StreamMemory? get() =
        StreamMemory.entries.firstOrNull {
            it.aheadMiB == aheadMiB &&
                it.behindMiB == behindMiB
        }

    companion object {
        /** Read ahead: below 16 MiB, a few seconds of a 4K film; above 1 GiB, more than any level. */
        val AHEAD_RANGE = 16..1024

        /** Kept behind. */
        val BEHIND_RANGE = 8..1024
    }
}
