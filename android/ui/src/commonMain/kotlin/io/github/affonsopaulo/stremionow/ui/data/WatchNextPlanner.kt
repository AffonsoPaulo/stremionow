// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.data

/**
 * Works out how the Android TV home screen's Watch Next row follows Continue watching (the TV
 * app applies it to the launcher's provider). Pure, so it is tested without a TV.
 *
 * - A title the user removed from the row (the launcher keeps its program, marked not
 *   browsable) stays out while nothing new is watched of it; watching it again brings it back.
 *   The launcher's mark goes with the program, so the app remembers the removal itself.
 * - Programs are updated in place rather than deleted and added again on every update, which
 *   is what brought removed titles back.
 * - Titles whose link the app would not open are left out: their tile would open nothing.
 */
object WatchNextPlanner {
    /** One of the app's programs in the row: its provider [rowId] and the title it shows. */
    data class Row(
        val rowId: Long,
        val titleId: String?,
        val browsable: Boolean,
        /** The engagement time the program carries, the title's progress time when published. */
        val engagedAt: Long,
    )

    /** A title of Continue watching. */
    data class Title(
        val id: String,
        /** When its progress last changed. */
        val updatedAt: Long,
        /** Whether a link to it opens in the app (`TitleLink.target`). */
        val opens: Boolean,
    )

    data class Plan(
        /** Programs to delete. */
        val delete: List<Long>,
        /** Programs to update in place, with the title each shows now. */
        val update: Map<Long, String>,
        /** Titles to add, in Continue watching's order. */
        val insert: List<String>,
        /** The removals to remember for next time: title id to its progress time when removed. */
        val removed: Map<String, Long>,
    )

    /**
     * The changes that make [rows] show [titles], given the titles the user [removed] before
     * (title id to its progress time then).
     */
    fun plan(
        rows: List<Row>,
        titles: List<Title>,
        removed: Map<String, Long>,
    ): Plan {
        val byId = titles.distinctBy { it.id }.associateBy { it.id }
        // Removals the launcher reports now join the remembered ones.
        val reported =
            rows
                .filter { !it.browsable && it.titleId != null }
                .associate { row -> row.titleId!! to row.engagedAt.takeIf { it > 0 } }
        val allRemoved = removed.toMutableMap()
        for ((id, engagedAt) in reported) {
            val at = engagedAt ?: byId[id]?.updatedAt ?: continue
            allRemoved[id] = maxOf(at, allRemoved[id] ?: Long.MIN_VALUE)
        }
        // Forgotten once the title leaves Continue watching or is watched again.
        val stillRemoved = allRemoved.filter { (id, at) -> byId[id]?.let { it.updatedAt <= at } == true }
        val wanted = byId.values.filter { it.opens && it.id !in stillRemoved }.map { it.id }
        val wantedIds = wanted.toSet()

        val delete = mutableListOf<Long>()
        val update = linkedMapOf<Long, String>()
        val shown = mutableSetOf<String>()
        for (row in rows) {
            val id = row.titleId
            // One program per title: duplicates from earlier versions go.
            if (row.browsable && id != null && id in wantedIds && shown.add(id)) {
                update[row.rowId] = id
            } else {
                // Removed programs go too: the removal is remembered in [Plan.removed].
                delete += row.rowId
            }
        }
        return Plan(
            delete = delete,
            update = update,
            insert = wanted.filter { it !in shown },
            removed = stillRemoved,
        )
    }
}
