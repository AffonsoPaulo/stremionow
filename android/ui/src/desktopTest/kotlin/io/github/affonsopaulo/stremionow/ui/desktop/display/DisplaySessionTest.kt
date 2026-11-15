// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop.display

import io.github.affonsopaulo.stremionow.core.DisplaySwitch
import io.github.affonsopaulo.stremionow.player.VideoFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Window
import java.io.File

/**
 * The HDR and refresh rate decisions and their undoing, against a fake display: nothing here
 * touches the real system's display settings.
 */
class DisplaySessionTest {
    @get:Rule val temporary = TemporaryFolder()

    private val monitor =
        DisplayTarget(adapterLow = 7, adapterHigh = 0, targetId = 4353, deviceName = "\\\\.\\DISPLAY1")
    private val hdrVideo = VideoFormat.from("pq", 23.976)
    private val sdrVideo = VideoFormat.from("bt.1886", 23.976)

    @Test
    fun asksBeforeTurningOnHdrAndNotNowChangesNothing() {
        val display = FakeDisplay()
        val journal = MemoryJournal()
        val session = session(display, journal = journal)

        assertTrue(session.onVideo(hdrVideo, monitor))
        assertTrue(display.calls.none { it.startsWith("setHdr") })

        session.answer(HdrChoice.NotNow)
        assertFalse(display.hdr.enabled)
        assertFalse(session.isHdrActive)
        assertNull(journal.entry)
        session.restore()
        assertTrue(display.calls.none { it.startsWith("setHdr") })
    }

    @Test
    fun aPromptThatTimesOutCountsAsNotNowAndTheNextVideoAsksAgain() {
        val display = FakeDisplay()
        val preferences = FakePreferences()
        val first = session(display, preferences)
        assertTrue(first.onVideo(hdrVideo, monitor))
        // The corner prompt faded away unanswered (or Esc dismissed it).
        first.answer(HdrChoice.NotNow)
        first.restore()
        assertFalse(display.hdr.enabled)
        assertEquals(DisplaySwitch.ASK, preferences.hdr)
        assertTrue(preferences.titles.isEmpty())

        assertTrue(session(display, preferences).onVideo(hdrVideo, monitor))
    }

    @Test
    fun anAnswerAfterLeavingThePlayerChangesNothing() {
        val display = FakeDisplay()
        val session = session(display)
        assertTrue(session.onVideo(hdrVideo, monitor))
        session.restore()
        session.answer(HdrChoice.OnlyThisTitle)
        assertFalse(display.hdr.enabled)
    }

    @Test
    fun onlyThisTitleRemembersItTurnsHdrOnAndLeavingTurnsItOff() {
        val display = FakeDisplay()
        val journal = MemoryJournal()
        val preferences = FakePreferences()
        val session = session(display, preferences, journal)

        assertTrue(session.onVideo(hdrVideo, monitor))
        session.answer(HdrChoice.OnlyThisTitle)
        assertEquals(setOf("tt0063350"), preferences.titles)
        assertTrue(display.hdr.enabled)
        assertTrue(session.isHdrActive)
        assertEquals(monitor, journal.entry)

        session.restore()
        assertFalse(display.hdr.enabled)
        assertFalse(session.isHdrActive)
        assertNull(journal.entry)
        // Restoring again changes nothing more.
        val calls = display.calls.size
        session.restore()
        assertEquals(calls, display.calls.size)
    }

    @Test
    fun theJournalIsWrittenBeforeHdrIsTurnedOn() {
        val display = FakeDisplay()
        val journal = MemoryJournal(display.calls)
        session(display, FakePreferences(DisplaySwitch.ALWAYS), journal).onVideo(hdrVideo, monitor)
        assertTrue(display.calls.indexOf("journal") < display.calls.indexOf("setHdr true"))
    }

    @Test
    fun alwaysAnswerChangesTheSettingAndLaterVideosDoNotAsk() {
        val display = FakeDisplay()
        val preferences = FakePreferences()
        val first = session(display, preferences)
        assertTrue(first.onVideo(hdrVideo, monitor))
        first.answer(HdrChoice.Always)
        assertEquals(DisplaySwitch.ALWAYS, preferences.hdr)
        assertTrue(display.hdr.enabled)
        first.restore()

        val second = session(display, preferences)
        assertFalse(second.onVideo(hdrVideo, monitor))
        assertTrue(display.hdr.enabled)
    }

    @Test
    fun aRememberedTitleSwitchesWithoutAsking() {
        val display = FakeDisplay()
        val session = session(display, FakePreferences(titles = mutableSetOf("tt0063350")))
        assertFalse(session.onVideo(hdrVideo, monitor))
        assertTrue(display.hdr.enabled)
    }

    @Test
    fun neverNeitherAsksNorSwitches() {
        val display = FakeDisplay()
        val session = session(display, FakePreferences(DisplaySwitch.NEVER, titles = mutableSetOf("tt0063350")))
        assertFalse(session.onVideo(hdrVideo, monitor))
        assertFalse(display.hdr.enabled)
    }

    @Test
    fun sdrVideosUnsupportedDisplaysAndHdrAlreadyOnAreLeftAlone() {
        val sdr = FakeDisplay()
        assertFalse(session(sdr).onVideo(sdrVideo, monitor))

        val unsupported = FakeDisplay(HdrState(supported = false, enabled = false))
        assertFalse(session(unsupported, FakePreferences(DisplaySwitch.ALWAYS)).onVideo(hdrVideo, monitor))
        assertTrue(unsupported.calls.none { it.startsWith("setHdr") })

        val alreadyOn = FakeDisplay(HdrState(supported = true, enabled = true))
        val session = session(alreadyOn, FakePreferences(DisplaySwitch.ALWAYS))
        assertFalse(session.onVideo(hdrVideo, monitor))
        session.restore()
        assertTrue("the user's own HDR stays on", alreadyOn.hdr.enabled)

        val unknown = FakeDisplay()
        assertFalse(session(unknown).onVideo(hdrVideo, null))
    }

    @Test
    fun aFailedSwitchLeavesNothingToUndo() {
        val display = FakeDisplay(refuseHdr = true)
        val journal = MemoryJournal()
        val session = session(display, FakePreferences(DisplaySwitch.ALWAYS), journal)
        session.onVideo(hdrVideo, monitor)
        assertFalse(session.isHdrActive)
        assertNull(journal.entry)
    }

    @Test
    fun aSwitchReportedAsFailedThatHappenedAnywayIsStillUndone() {
        val display = FakeDisplay(reportFailure = true)
        val session = session(display, FakePreferences(DisplaySwitch.ALWAYS))
        session.onVideo(hdrVideo, monitor)
        assertTrue(display.hdr.enabled)
        assertTrue(session.isHdrActive)
        session.restore()
        assertFalse(display.hdr.enabled)
    }

    @Test
    fun withoutAJournalHdrIsNotTurnedOn() {
        val display = FakeDisplay()
        val session = session(display, FakePreferences(DisplaySwitch.ALWAYS), MemoryJournal(failWrites = true))
        session.onVideo(hdrVideo, monitor)
        assertFalse(display.hdr.enabled)
    }

    @Test
    fun hdrTurnedOffByHandMeanwhileIsNotTouchedAgain() {
        val display = FakeDisplay()
        val session = session(display, FakePreferences(DisplaySwitch.ALWAYS))
        session.onVideo(hdrVideo, monitor)
        display.hdr = display.hdr.copy(enabled = false)
        val calls = display.calls.count { it.startsWith("setHdr") }
        session.restore()
        assertEquals(calls, display.calls.count { it.startsWith("setHdr") })
    }

    @Test
    fun aDisplayUnpluggedMidPlaybackIsRestoredOnTheNextLaunchIfItIsBack() {
        val display = FakeDisplay()
        val journal = MemoryJournal()
        val session = session(display, FakePreferences(DisplaySwitch.ALWAYS), journal)
        session.onVideo(hdrVideo, monitor)

        display.connected = false
        session.restore()
        assertEquals("kept for the next launch", monitor, journal.entry)

        display.connected = true
        DisplaySession.recoverAfterCrash(display, journal)
        assertFalse(display.hdr.enabled)
        assertNull(journal.entry)
    }

    @Test
    fun recoveryAfterACrashTurnsHdrOffOrDropsAJournalForAnAbsentDisplay() {
        val display = FakeDisplay(HdrState(supported = true, enabled = true))
        val journal = MemoryJournal().apply { write(monitor) }
        DisplaySession.recoverAfterCrash(display, journal)
        assertFalse(display.hdr.enabled)
        assertNull(journal.entry)

        val absent = FakeDisplay(HdrState(supported = true, enabled = true)).apply { connected = false }
        val stale = MemoryJournal().apply { write(monitor) }
        DisplaySession.recoverAfterCrash(absent, stale)
        assertNull(stale.entry)
        assertTrue(absent.calls.none { it.startsWith("setHdr") })
    }

    @Test
    fun theRefreshRateIsMatchedOnlyWhenTurnedOnAndResetWhenLeaving() {
        val off = FakeDisplay()
        session(off).onVideo(sdrVideo, monitor)
        assertTrue(off.calls.none { it.startsWith("setRefreshRate") })

        val display = FakeDisplay()
        val session = session(display, FakePreferences(matchRefreshRate = true))
        assertFalse(session.onVideo(sdrVideo, monitor))
        assertEquals(120, display.refresh)
        session.restore()
        assertEquals(60, display.refresh)
    }

    @Test
    fun theNextVideoInThePlayerIsJudgedAgainForHdr() {
        val display = FakeDisplay()
        val journal = MemoryJournal()
        val session = session(display, FakePreferences(DisplaySwitch.ALWAYS), journal)
        session.onVideo(hdrVideo, monitor)
        assertTrue(display.hdr.enabled)

        // The next episode is SDR: HDR goes off, and mpv is told to stop outputting it.
        assertFalse(session.onVideo(sdrVideo, monitor))
        assertFalse(display.hdr.enabled)
        assertFalse(session.isHdrActive)
        assertNull(journal.entry)

        // And the one after is HDR again.
        assertFalse(session.onVideo(hdrVideo, monitor))
        assertTrue(display.hdr.enabled)
        assertTrue(session.isHdrActive)
        session.restore()
        assertFalse(display.hdr.enabled)
    }

    @Test
    fun anHdrEpisodeAfterAnSdrOneAsks() {
        val display = FakeDisplay()
        val session = session(display)
        assertFalse(session.onVideo(sdrVideo, monitor))
        assertTrue(session.onVideo(hdrVideo, monitor))
        session.answer(HdrChoice.OnlyThisTitle)
        assertTrue(display.hdr.enabled)
        // HDR on for the title: the next HDR episode keeps it without asking.
        assertFalse(session.onVideo(hdrVideo, monitor))
        assertTrue(session.isHdrActive)
    }

    @Test
    fun notNowIsNotAskedAgainInTheSamePlayer() {
        val session = session(FakeDisplay())
        assertTrue(session.onVideo(hdrVideo, monitor))
        session.answer(HdrChoice.NotNow)
        assertFalse(session.onVideo(hdrVideo, monitor))
    }

    @Test
    fun theRefreshRateFollowsEachVideo() {
        val display = FakeDisplay()
        val session = session(display, FakePreferences(matchRefreshRate = true))
        session.onVideo(sdrVideo, monitor)
        assertEquals(120, display.refresh)

        // 24 fps suits 120 Hz as well as 23.976 does: no switch.
        val calls = display.calls.size
        session.onVideo(VideoFormat.from("bt.1886", 24.0), monitor)
        assertEquals(calls, display.calls.size)

        // Nothing suits 25 fps here: the saved mode comes back.
        session.onVideo(VideoFormat.from("bt.1886", 25.0), monitor)
        assertEquals(60, display.refresh)

        session.onVideo(sdrVideo, monitor)
        assertEquals(120, display.refresh)
        // A video with no known frame rate puts the mode back too.
        session.onVideo(VideoFormat.from("bt.1886", null), monitor)
        assertEquals(60, display.refresh)
        session.restore()
        assertEquals(60, display.refresh)
    }

    @Test
    fun aRefusedRefreshRateLeavesTheModeAlone() {
        val display = FakeDisplay(refuseRefresh = true)
        val session = session(display, FakePreferences(matchRefreshRate = true))
        session.onVideo(sdrVideo, monitor)
        assertEquals(60, display.refresh)
        assertTrue("a refused switch is reset at once", "resetRefreshRate" in display.calls)
    }

    @Test
    fun refreshRateChoices() {
        val rates = listOf(23, 24, 50, 59, 60, 100, 119, 120, 144, 240)
        assertEquals(240, RefreshRates.choose(23.976, 60, rates))
        assertNull("240 Hz already shows 24 fps evenly", RefreshRates.choose(24.0, 240, rates))
        assertNull("120 Hz already shows 24 fps evenly", RefreshRates.choose(23.976, 120, rates))
        assertEquals(100, RefreshRates.choose(25.0, 60, rates))
        assertNull(RefreshRates.choose(29.97, 60, rates))
        assertNull("59.94 Hz shows as 59", RefreshRates.choose(59.94, 59, rates))
        assertNull("nothing better", RefreshRates.choose(23.976, 60, listOf(60, 75)))
        assertNull(RefreshRates.choose(Double.NaN, 60, rates))
        assertNull(RefreshRates.choose(1000.0, 60, rates))
        assertEquals(48, RefreshRates.choose(24.0, 60, listOf(48, 60, 300)))
    }

    @Test
    fun videoFormatsAreValidated() {
        assertTrue(VideoFormat.from("pq", 24.0).isHdr)
        assertTrue(VideoFormat.from(" HLG ", 50.0).isHdr)
        assertFalse(VideoFormat.from("bt.1886", 24.0).isHdr)
        assertFalse(VideoFormat.from("pq; vo=null", 24.0).isHdr)
        assertFalse(VideoFormat.from(null, null).isHdr)
        assertNull(VideoFormat.from("pq", Double.POSITIVE_INFINITY).framesPerSecond)
        assertNull(VideoFormat.from("pq", 5.0).framesPerSecond)
        assertNull(VideoFormat.from("pq", 1000.0).framesPerSecond)
        assertEquals(23.976, VideoFormat.from("pq", 23.976).framesPerSecond!!, 0.0)
    }

    @Test
    fun theJournalFileRoundTripsAndRejectsAnythingElse() {
        val file = File(temporary.root, "display-restore.properties")
        val journal = FileDisplayJournal(file)
        assertNull(journal.read())
        journal.write(monitor)
        assertEquals(monitor, journal.read())
        journal.clear()
        assertNull(journal.read())

        file.writeText("adapterLow=1\nadapterHigh=0\ntarget=2\ndevice=C:\\\\evil\n")
        assertNull("only display device names are accepted", journal.read())
        file.writeText("adapterLow=x\nadapterHigh=0\ntarget=2\ndevice=\\\\\\\\.\\\\DISPLAY2\n")
        assertNull(journal.read())
    }

    private fun session(
        display: FakeDisplay,
        preferences: FakePreferences = FakePreferences(),
        journal: MemoryJournal = MemoryJournal(),
    ) = DisplaySession(display, preferences, journal, "tt0063350")

    private inner class FakeDisplay(
        var hdr: HdrState = HdrState(supported = true, enabled = false),
        private val refuseHdr: Boolean = false,
        private val reportFailure: Boolean = false,
        private val refuseRefresh: Boolean = false,
    ) : DisplayControl {
        val calls = mutableListOf<String>()
        var connected = true
        var refresh = 60

        override fun displayOf(window: Window): DisplayTarget? = monitor.takeIf { connected }

        override fun hdr(target: DisplayTarget): HdrState? = hdr.takeIf { connected && target == monitor }

        override fun setHdr(
            target: DisplayTarget,
            enabled: Boolean,
        ): Boolean {
            calls += "setHdr $enabled"
            if (!connected || refuseHdr) return false
            hdr = hdr.copy(enabled = enabled)
            return !reportFailure
        }

        override fun refreshRate(target: DisplayTarget): Int? = refresh.takeIf { connected }

        override fun refreshRates(target: DisplayTarget): List<Int> = listOf(24, 48, 60, 120)

        override fun setRefreshRate(
            target: DisplayTarget,
            hertz: Int,
        ): Boolean {
            calls += "setRefreshRate $hertz"
            if (refuseRefresh) return false
            refresh = hertz
            return true
        }

        override fun resetRefreshRate(target: DisplayTarget): Boolean {
            calls += "resetRefreshRate"
            refresh = 60
            return true
        }
    }

    private class FakePreferences(
        override var hdr: DisplaySwitch = DisplaySwitch.ASK,
        override val matchRefreshRate: Boolean = false,
        val titles: MutableSet<String> = mutableSetOf(),
    ) : DisplayPreferences {
        override fun hdrForTitle(metaId: String) = metaId in titles

        override fun rememberHdrForTitle(metaId: String) {
            titles += metaId
        }

        override fun alwaysHdr() {
            hdr = DisplaySwitch.ALWAYS
        }
    }

    private class MemoryJournal(
        private val calls: MutableList<String>? = null,
        private val failWrites: Boolean = false,
    ) : DisplayJournal {
        var entry: DisplayTarget? = null

        override fun read() = entry

        override fun write(target: DisplayTarget) {
            check(!failWrites) { "disk full" }
            calls?.add("journal")
            entry = target
        }

        override fun clear() {
            entry = null
        }
    }
}
