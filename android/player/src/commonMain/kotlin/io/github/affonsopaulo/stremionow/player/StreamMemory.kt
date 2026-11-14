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
