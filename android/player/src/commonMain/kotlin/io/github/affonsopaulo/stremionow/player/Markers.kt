// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.player

/** What a skippable segment covers. */
enum class SkipKind { Intro, Recap, Credits }

/** Where the intro, the recap of earlier episodes and the end credits are, when known. */
data class Markers(
    val intro: Segment? = null,
    val recap: Segment? = null,
    val credits: Segment? = null,
) {
    operator fun get(kind: SkipKind): Segment? =
        when (kind) {
            SkipKind.Intro -> intro
            SkipKind.Recap -> recap
            SkipKind.Credits -> credits
        }

    /**
     * These markers, with the kinds they lack taken from [fallback]. The file's own chapters
     * come first: they describe this very file, while an online database describes the episode
     * as someone else's copy had it.
     */
    fun orElse(fallback: Markers): Markers =
        Markers(
            intro = intro ?: fallback.intro,
            recap = recap ?: fallback.recap,
            credits = credits ?: fallback.credits,
        )

    companion object {
        /** Markers from a list of segments; the first one of each kind wins. */
        fun of(segments: List<Pair<SkipKind, Segment>>): Markers {
            fun first(kind: SkipKind) = segments.firstOrNull { it.first == kind }?.second
            return Markers(first(SkipKind.Intro), first(SkipKind.Recap), first(SkipKind.Credits))
        }
    }
}

/** The one prompt shown in the corner of the player, if any. */
sealed interface PlaybackPrompt {
    /** "Skip intro", "Skip recap" or "Skip credits", which seeks to [toSeconds]. */
    data class Skip(
        val kind: SkipKind,
        val toSeconds: Double,
    ) : PlaybackPrompt

    /** The next episode's card, counting down before it plays. */
    data object UpNext : PlaybackPrompt

    companion object {
        /** Credits that stop this close to the end run to the end: nothing follows them. */
        private const val CREDITS_AT_END_SECONDS = 10.0

        /** Without credits that run to the end, "Up next" shows this long before the end. */
        private const val UP_NEXT_FALLBACK_SECONDS = 30.0

        /** Shorter videos show "Up next" only at the end, not for most of their length. */
        private const val UP_NEXT_MIN_DURATION_SECONDS = 120.0

        /** A skip of less than this is not worth a button, which would only flash. */
        private const val MIN_SKIP_SECONDS = 1.0

        /**
         * The prompt for [positionSeconds]. Only one shows at a time, so two buttons never
         * compete for the same corner or the same remote press:
         *
         * - Inside an intro or recap: skip it. Where segments overlap, the earlier one first.
         * - Inside end credits that run to the end of the file, with a next episode: "Up next",
         *   since skipping the credits means moving on. Without one, nothing: skipping would
         *   only end the video.
         * - Inside credits followed by more of the episode (an anime's after-credits scene or
         *   next-episode preview): "Skip credits", which lands on what follows instead of
         *   moving on and losing it.
         * - Otherwise, with a next episode: "Up next" from the credits that run to the end, or
         *   the last half minute, or once the video ends.
         */
        fun at(
            markers: Markers,
            positionSeconds: Double,
            durationSeconds: Double?,
            hasNext: Boolean,
            isEnded: Boolean,
        ): PlaybackPrompt? {
            val duration = durationSeconds?.takeIf { it > 0 }
            val credits = markers.credits
            val finalCredits =
                credits?.takeIf {
                    duration != null && duration - it.endSeconds <= CREDITS_AT_END_SECONDS &&
                        it.startSeconds > duration / 2
                }
            if (!isEnded) {
                val active =
                    SkipKind.entries
                        .mapNotNull { kind -> markers[kind]?.let { kind to it } }
                        .filter { (_, segment) ->
                            positionSeconds in segment && segment.endSeconds - positionSeconds >= MIN_SKIP_SECONDS
                        }.minByOrNull { (_, segment) -> segment.startSeconds }
                if (active != null) {
                    val (kind, segment) = active
                    return when {
                        kind != SkipKind.Credits || segment != finalCredits -> Skip(kind, segment.endSeconds)
                        hasNext -> UpNext
                        else -> null
                    }
                }
            }
            if (!hasNext) return null
            if (isEnded) return UpNext
            duration ?: return null
            val upNextAt =
                finalCredits?.startSeconds
                    ?: (duration - UP_NEXT_FALLBACK_SECONDS).takeIf { duration >= UP_NEXT_MIN_DURATION_SECONDS }
                    ?: duration
            return if (positionSeconds >= upNextAt) UpNext else null
        }
    }
}
