// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.tv

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.BaseColumns
import android.util.Log
import androidx.core.content.edit
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import io.github.affonsopaulo.stremionow.core.WatchProgress
import io.github.affonsopaulo.stremionow.ui.data.AppGraph
import io.github.affonsopaulo.stremionow.ui.data.TitleLink
import io.github.affonsopaulo.stremionow.ui.data.WatchNextPlanner
import io.github.affonsopaulo.stremionow.ui.data.build
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Mirrors "Continue watching" into the Android TV home screen's Watch Next row. The app owns
 * only its own programs there; [WatchNextPlanner] decides what changes.
 */
object WatchNext {
    private const val TAG = "StremioNow"
    private const val PREFERENCES = "watch_next"
    private const val REMOVED = "removed"

    /**
     * One update at a time: two overlapping ones (leaving the app twice in quick succession)
     * would each add the titles the other had not written yet, and the row would show them twice.
     */
    private val updates = Mutex()

    /** Reads Continue watching and updates the row. */
    suspend fun update(context: Context) {
        updates.withLock {
            val items = runCatching { AppGraph.core(context).continueWatching() }.getOrNull() ?: return
            publish(context, items)
        }
    }

    private fun publish(
        context: Context,
        items: List<WatchProgress>,
    ) {
        val resolver = context.contentResolver
        runCatching {
            val byId = items.associateBy { it.id }
            val titles =
                items.map { WatchNextPlanner.Title(it.id, it.updatedAt, TitleLink.target(it.kind, it.id) != null) }
            val plan = WatchNextPlanner.plan(rows(resolver), titles, readRemoved(context))
            plan.delete.forEach { resolver.delete(TvContractCompat.buildWatchNextProgramUri(it), null, null) }
            val updated =
                plan.update.count { (rowId, id) ->
                    val values = program(context, byId.getValue(id)).toContentValues()
                    resolver.update(TvContractCompat.buildWatchNextProgramUri(rowId), values, null, null) > 0
                }
            val added =
                plan.insert.count { id ->
                    val values = program(context, byId.getValue(id)).toContentValues()
                    resolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values) != null
                }
            writeRemoved(context, plan.removed)
            Log.d(
                TAG,
                "Watch Next: $updated updated, $added added, ${plan.delete.size} deleted, ${plan.removed.size} hidden",
            )
        }.onFailure {
            // Fire TV and some launchers have no Watch Next provider; the app works without it.
            Log.i(TAG, "Watch Next is not available", it)
        }
    }

    /** The app's programs in the row; the provider shows each app only its own. */
    private fun rows(resolver: ContentResolver): List<WatchNextPlanner.Row> {
        val columns =
            arrayOf(
                BaseColumns._ID,
                TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
                TvContractCompat.WatchNextPrograms.COLUMN_BROWSABLE,
                TvContractCompat.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            )
        return resolver
            .query(TvContractCompat.WatchNextPrograms.CONTENT_URI, columns, null, null, null)
            ?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            WatchNextPlanner.Row(
                                rowId = cursor.getLong(0),
                                titleId = if (cursor.isNull(1)) null else cursor.getString(1),
                                // Missing counts as shown, as the provider's default is.
                                browsable = cursor.isNull(2) || cursor.getInt(2) != 0,
                                engagedAt = if (cursor.isNull(3)) 0 else cursor.getLong(3),
                            ),
                        )
                    }
                }
            }.orEmpty()
    }

    /** Titles the user removed from the row, as "progress time|title id". */
    private fun readRemoved(context: Context): Map<String, Long> =
        context
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getStringSet(REMOVED, emptySet())
            .orEmpty()
            .mapNotNull { entry ->
                val at = entry.substringBefore('|').toLongOrNull() ?: return@mapNotNull null
                entry.substringAfter('|', "").takeIf { it.isNotEmpty() }?.let { it to at }
            }.toMap()

    private fun writeRemoved(
        context: Context,
        removed: Map<String, Long>,
    ) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
            putStringSet(REMOVED, removed.map { (id, at) -> "$at|$id" }.toSet())
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
