// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReportStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private var clock = 1_762_862_400_000L

    private fun store(folder: File = temporary.root) = ReportStore(folder) { clock }

    private fun report(
        summary: String,
        time: Long = clock,
    ) = sampleReport().copy(summary = summary, time = time)

    @Test
    fun `the same crash again counts instead of piling up`() {
        val store = store()
        repeat(4) { store.write(report("Same at A.b", time = clock + it * 60_000L)) }
        val reports = store.load(homeFolder = null)
        assertEquals(1, reports.size)
        assertEquals(4, reports.single().report.occurrences)
        assertEquals(clock + 3 * 60_000L, reports.single().report.time)
    }

    @Test
    fun `only the newest few distinct reports are kept`() {
        val store = store()
        repeat(8) { store.write(report("Crash $it at A.b", time = clock + it * 1000L)) }
        val reports = store.load(homeFolder = null)
        assertEquals(ReportStore.MAX_REPORTS, reports.size)
        assertEquals("Crash 7 at A.b", reports.first().report.summary)
        assertEquals("Crash 3 at A.b", reports.last().report.summary)
    }

    @Test
    fun `leftovers, damage and old reports are cleaned up`() {
        val store = store()
        store.write(report("Kept at A.b"))
        File(temporary.root, "report-1-abc.txt.tmp").writeText("half a rep")
        store.write(report("Old at A.b", time = clock - ReportStore.MAX_AGE_MS - 1))
        File(temporary.root, "report-5-dead.txt").writeBytes(ByteArray(600 * 1024))
        File(temporary.root, "unrelated.txt").writeText("not ours")
        val reports = store.load(homeFolder = null)
        assertEquals(listOf("Kept at A.b"), reports.map { it.report.summary })
        assertEquals(
            setOf("unrelated.txt", reports.single().file.name),
            temporary.root.list()!!.toSet(),
        )
    }

    @Test
    fun `an unwritable folder fails quietly`() {
        val blocker = temporary.newFile("blocker")
        val store = store(File(blocker, "reports"))
        assertNull(store.write(report("X at A.b")))
        assertTrue(store.load(homeFolder = null).isEmpty())
        store.writeMode(CrashReports.Mode.Never)
        assertEquals(CrashReports.Mode.Ask, store.readMode())
    }

    @Test
    fun `the choice and the exit watermark persist`() {
        val store = store()
        assertEquals(CrashReports.Mode.Ask, store.readMode())
        store.writeMode(CrashReports.Mode.Never)
        assertEquals(CrashReports.Mode.Never, store().readMode())
        File(temporary.root, "mode").writeText("garbage")
        assertEquals(CrashReports.Mode.Ask, store.readMode())

        assertNull(store.readExitsSeen())
        store.writeExitsSeen(1234)
        assertEquals(1234L, store().readExitsSeen())

        // Failures of the crash question count per version: an update tries it again.
        assertEquals(0, store.readPromptFailures("0.0.1"))
        store.writePromptFailures(2, "0.0.1 (debug)")
        assertEquals(2, store.readPromptFailures("0.0.1 (debug)"))
        assertEquals(0, store.readPromptFailures("0.0.2"))
    }

    @Test
    fun `deleting removes reports but keeps the choice`() {
        val store = store()
        store.writeMode(CrashReports.Mode.Ask)
        val written = store.write(report("X at A.b"))
        assertNotNull(written)
        store.write(report("Y at A.b"))
        store.delete(written!!)
        assertEquals(listOf("Y at A.b"), store.load(homeFolder = null).map { it.report.summary })
        store.deleteAll()
        assertTrue(store.load(homeFolder = null).isEmpty())
        assertTrue(File(temporary.root, "mode").exists())
        assertFalse(temporary.root.list()!!.any { it.startsWith("report-") })
    }
}
