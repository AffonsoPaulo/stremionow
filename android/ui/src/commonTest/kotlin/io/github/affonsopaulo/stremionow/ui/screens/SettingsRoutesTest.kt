// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRoutesTest {
    @Test
    fun everySectionHasItsOwnRoute() {
        val routes = SettingsPage.entries.map(SettingsRoutes::of)
        assertEquals(routes.distinct(), routes)
        assertEquals("settings/addons", SettingsRoutes.of(SettingsPage.Addons))
        assertEquals("settings/playback", SettingsRoutes.of(SettingsPage.Playback))
        SettingsPage.entries.forEach { assertEquals(it, SettingsRoutes.pageOf(it.slug)) }
    }

    @Test
    fun oldRoutesLeadToTheirSection() {
        assertEquals("settings/account", SettingsRoutes.redirect("profile"))
        assertEquals("settings/addons", SettingsRoutes.redirect("addons"))
        // Other devices was settings/devices already: the section's own route.
        assertEquals(SettingsPage.Devices, SettingsRoutes.pageOf("devices"))
        assertNull(SettingsRoutes.redirect("settings/devices"))
        assertNull(SettingsRoutes.redirect("home"))
    }

    @Test
    fun unknownSectionsShowTheList() {
        assertNull(SettingsRoutes.pageOf(null))
        assertNull(SettingsRoutes.pageOf(""))
        assertNull(SettingsRoutes.pageOf("Addons"))
    }

    @Test
    fun displayShowsOnlyWhereTheAppCanSwitchTheDisplay() {
        assertTrue(SettingsPage.Display in SettingsPage.shown(displaySwitching = true))
        assertFalse(SettingsPage.Display in SettingsPage.shown(displaySwitching = false))
        // Addons comes first and Playback right after it, where the avatar's Settings lands.
        assertEquals(listOf(SettingsPage.Addons, SettingsPage.Playback), SettingsPage.shown(false).take(2))
    }
}
