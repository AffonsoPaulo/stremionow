// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.tv

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import io.github.affonsopaulo.stremionow.core.WatchProgress
import io.github.affonsopaulo.stremionow.ui.data.TitleLink

/**
 * Mirrors "Continue watching" into the Android TV home screen's Watch Next row. The app owns
 * only its own programs there, so each update replaces them all.
 */
object WatchNext {
    private const val TAG = "StremioNow"

    fun publish(
        context: Context,
        items: List<WatchProgress>,
    ) {
        val resolver = context.contentResolver
        runCatching {
            val programs = TvContractCompat.WatchNextPrograms.CONTENT_URI
            resolver.query(programs, arrayOf("_id"), null, null, null)?.use { rows ->
                while (rows.moveToNext()) {
                    resolver.delete(TvContractCompat.buildWatchNextProgramUri(rows.getLong(0)), null, null)
                }
            }
            val added =
                items.count { item ->
                    resolver.insert(programs, program(context, item).toContentValues()) !=
                        null
                }
            Log.d(TAG, "Watch Next updated with $added of ${items.size} titles")
        }.onFailure {
            // Fire TV and some launchers have no Watch Next provider; the app works without it.
            Log.i(TAG, "Watch Next is not available", it)
        }
    }

    // WatchNextProgram.Builder's setters come from base classes marked library-internal, so
    // Lint reports every call even though the builder is the documented public API.
    @SuppressLint("RestrictedApi")
    private fun program(
        context: Context,
        item: WatchProgress,
    ): WatchNextProgram {
        // Artwork comes from addons and sync; the launcher only gets web images, never local URIs.
        val background = item.background?.takeIf { it.startsWith("https://") }
        val art = background ?: item.poster?.takeIf { it.startsWith("https://") }
        val intent =
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(TitleLink.build(item.kind, item.id))
        return WatchNextProgram
            .Builder()
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setType(
                if (item.kind == "series") {
                    TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE
                } else {
                    TvContractCompat.PreviewPrograms.TYPE_MOVIE
                },
            ).setTitle(item.name)
            .setInternalProviderId(item.id)
            .setLastEngagementTimeUtcMillis(item.updatedAt)
            .setLastPlaybackPositionMillis(item.positionMs.toInt())
            .setDurationMillis(item.durationMs.toInt())
            .apply {
                if (art != null) {
                    setPosterArtUri(Uri.parse(art))
                    setPosterArtAspectRatio(
                        if (background != null) {
                            TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
                        } else {
                            TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3
                        },
                    )
                }
                item.episode?.let { setEpisodeNumber(it.toInt()) }
                item.season?.let { setSeasonNumber(it.toInt()) }
            }.setIntent(intent)
            .build()
    }
}
