// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.player

import io.github.affonsopaulo.stremionow.player.PlaybackPrompt.Skip
import io.github.affonsopaulo.stremionow.player.PlaybackPrompt.UpNext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkersTest {
    private val duration = 1440.0

    private fun prompt(
        markers: Markers,
        at: Double,
        hasNext: Boolean = true,
        isEnded: Boolean = false,
        duration: Double? = this.duration,
    ) = PlaybackPrompt.at(markers, at, duration, hasNext, isEnded)

    @Test
    fun chaptersWinOverAniSkipKindByKind() {
        val chapters = Markers(intro = Segment(95.0, 185.0))
        val aniSkip = Markers(intro = Segment(90.0, 180.0), credits = Segment(1320.0, 1410.0))
        assertEquals(
            Markers(intro = Segment(95.0, 185.0), credits = Segment(1320.0, 1410.0)),
            chapters.orElse(aniSkip),
        )
        assertEquals(aniSkip, Markers().orElse(aniSkip))
    }

    @Test
    fun buildsMarkersFromSegmentLists() {
        val markers =
            Markers.of(
                listOf(
                    SkipKind.Credits to Segment(1300.0, 1390.0),
                    SkipKind.Intro to Segment(10.0, 100.0),
                    SkipKind.Intro to Segment(20.0, 110.0),
                ),
            )
        assertEquals(Markers(intro = Segment(10.0, 100.0), credits = Segment(1300.0, 1390.0)), markers)
    }

    @Test
    fun offersToSkipTheSegmentBeingPlayed() {
        val markers = Markers(intro = Segment(95.0, 185.0), recap = Segment(0.0, 60.0))
        assertEquals(Skip(SkipKind.Recap, 60.0), prompt(markers, at = 0.0))
        assertNull(prompt(markers, at = 70.0))
        // Seeking into the middle of the intro brings the button up.
        assertEquals(Skip(SkipKind.Intro, 185.0), prompt(markers, at = 120.0))
        // Its last second is not worth a button; and right at the end it is over.
        assertNull(prompt(markers, at = 184.5))
        assertNull(prompt(markers, at = 185.0))
    }

    @Test
    fun overlappingSegmentsGoInOrder() {
        val markers = Markers(intro = Segment(50.0, 140.0), recap = Segment(0.0, 60.0))
        assertEquals(Skip(SkipKind.Recap, 60.0), prompt(markers, at = 55.0))
        assertEquals(Skip(SkipKind.Intro, 140.0), prompt(markers, at = 60.0))
    }

    @Test
    fun creditsWithMoreAfterThemOfferASkipThenUpNext() {
        // An anime ending followed by a 30-second preview of the next episode.
        val markers = Markers(credits = Segment(1320.0, 1410.0))
        assertEquals(Skip(SkipKind.Credits, 1410.0), prompt(markers, at = 1330.0))
        assertEquals(Skip(SkipKind.Credits, 1410.0), prompt(markers, at = 1330.0, hasNext = false))
        // Up next only once the credits are over, from the last half minute.
        assertEquals(UpNext, prompt(markers, at = 1410.0))
        assertNull(prompt(markers, at = 1410.0, hasNext = false))
    }

    @Test
    fun creditsThatRunToTheEndBecomeUpNext() {
        val markers = Markers(credits = Segment(1300.0, 1436.0))
        assertNull(prompt(markers, at = 1299.0))
        assertEquals(UpNext, prompt(markers, at = 1300.0))
        // Nothing follows them, so without a next episode a skip would only end the video.
        assertNull(prompt(markers, at = 1300.0, hasNext = false))
    }

    @Test
    fun earlyCreditsNeverStartTheNextEpisode() {
        // An ending marked in the first half (a cold open's credits, or a bad submission).
        val markers = Markers(credits = Segment(200.0, 1435.0))
        assertEquals(Skip(SkipKind.Credits, 1435.0), prompt(markers, at = 300.0))
    }

    @Test
    fun upNextFallsBackToTheLastHalfMinute() {
        assertNull(prompt(Markers(), at = 1400.0))
        assertEquals(UpNext, prompt(Markers(), at = 1410.0))
        assertEquals(UpNext, prompt(Markers(), at = 0.0, isEnded = true))
        assertNull(prompt(Markers(), at = 0.0, isEnded = true, hasNext = false))
        // A short video shows it only at its very end.
        assertNull(prompt(Markers(), at = 80.0, duration = 100.0))
        assertEquals(UpNext, prompt(Markers(), at = 100.0, duration = 100.0))
        // Without a known length, only the end tells.
        assertNull(prompt(Markers(), at = 5000.0, duration = null))
    }

    @Test
    fun anEndedVideoOffersNoSkip() {
        val markers = Markers(credits = Segment(1320.0, 1410.0))
        assertEquals(UpNext, prompt(markers, at = 1330.0, isEnded = true))
    }
}
