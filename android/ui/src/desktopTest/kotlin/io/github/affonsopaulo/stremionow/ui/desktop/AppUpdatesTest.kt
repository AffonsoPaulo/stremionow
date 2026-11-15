// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatesTest {
    private val sha = "C1DFEDDCB4DD4483D9C2A065C043C72491AC9A24137318B8DCDBD5A265D9A6C3"

    private fun asset(
        version: String,
        type: String = "Full",
        fileName: String = "StremioNow-$version-full.nupkg",
        hash: String = sha,
        id: String = "StremioNow",
    ) =
        """{"PackageId":"$id","Version":"$version","Type":"$type","FileName":"$fileName","SHA256":"$hash","Size":137836874}"""

    private fun feed(vararg assets: String) = """{"Assets":[${assets.joinToString(",")}]}"""

    private fun version(text: String) = AppVersion.parse(text)!!

    @Test
    fun `versions compare by number, and a pre-release comes before its release`() {
        assertTrue(version("0.10.0") > version("0.9.3"))
        assertTrue(version("1.0") == version("1.0.0") || version("1.0").compareTo(version("1.0.0")) == 0)
        assertTrue(version("0.2.0-beta.1") < version("0.2.0"))
        assertTrue(version("0.2.0-beta.1") > version("0.1.9"))
        assertNull(AppVersion.parse("next"))
        assertNull(AppVersion.parse(""))
    }

    @Test
    fun `the newest full package newer than this one is picked`() {
        val newest =
            AppUpdates.newestFull(
                feed(asset("0.1.0"), asset("0.3.0"), asset("0.3.0", type = "Delta"), asset("0.2.0")),
                version("0.1.0"),
            )
        assertEquals("0.3.0", newest?.version?.text)
        assertEquals("StremioNow-0.3.0-full.nupkg", newest?.fileName)
    }

    @Test
    fun `nothing newer means no update`() {
        assertNull(AppUpdates.newestFull(feed(asset("0.1.0")), version("0.1.0")))
        assertNull(AppUpdates.newestFull("not json", version("0.1.0")))
    }

    @Test
    fun `packages with odd names, hashes or ids are ignored`() {
        val current = version("0.1.0")
        assertNull(AppUpdates.newestFull(feed(asset("0.2.0", fileName = "..\\\\evil.nupkg")), current))
        assertNull(AppUpdates.newestFull(feed(asset("0.2.0", hash = "abc")), current))
        assertNull(AppUpdates.newestFull(feed(asset("0.2.0", id = "Other")), current))
    }
}
