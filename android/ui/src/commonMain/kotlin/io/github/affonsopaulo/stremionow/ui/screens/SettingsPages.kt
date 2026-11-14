// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import androidx.compose.ui.graphics.vector.ImageVector
import io.github.affonsopaulo.stremionow.ui.l10n.Res
import io.github.affonsopaulo.stremionow.ui.l10n.StringResource
import io.github.affonsopaulo.stremionow.ui.theme.SnIcons

/**
 * The sections of Settings, in the side menu's order. Each has its own route
 * (`settings/<slug>`), so links land on the right one.
 */
enum class SettingsPage(
    val slug: String,
) {
    Addons("addons"),
    General("general"),
    Playback("playback"),
    Display("display"),
    Torrents("torrents"),
    Devices("devices"),
    Privacy("privacy"),
    Account("account"),
    About("about"),
    ;

    companion object {
        /** The section with [slug], or null for anything else. */
        fun fromSlug(slug: String?): SettingsPage? = entries.firstOrNull { it.slug == slug }

        /**
         * The sections shown: Display holds HDR and refresh rate switching, which only some
         * platforms offer.
         */
        fun shown(displaySwitching: Boolean): List<SettingsPage> = entries.filter { it != Display || displaySwitching }
    }
}

/** Routes of Settings, and where the routes of earlier versions lead now. */
object SettingsRoutes {
    /** The list of sections on phones; elsewhere, Settings on its first section after Addons. */
    const val LIST = "settings"

    /** One section; `settings/devices`, a page of its own before, is one of them. */
    const val SECTION = "settings/{section}"

    /** Argument of [SECTION]. */
    const val SECTION_ARGUMENT = "section"

    /** The Profile page, which the avatar menu and Settings › Account replaced. */
    const val LEGACY_PROFILE = "profile"

    /** The Addons page, now Settings › Addons. */
    const val LEGACY_ADDONS = "addons"

    fun of(page: SettingsPage): String = "settings/${page.slug}"

    /** Where an old [route] leads now, or null when it is not an old one. */
    fun redirect(route: String): String? =
        when (route) {
            LEGACY_PROFILE -> of(SettingsPage.Account)
            LEGACY_ADDONS -> of(SettingsPage.Addons)
            else -> null
        }

    /** The section a Settings route shows: its argument, or null for [LIST] or an unknown one. */
    fun pageOf(sectionArgument: String?): SettingsPage? = SettingsPage.fromSlug(sectionArgument)
}

internal fun SettingsPage.title(): StringResource =
    when (this) {
        SettingsPage.Addons -> Res.string.nav_addons
        SettingsPage.General -> Res.string.settings_general
        SettingsPage.Playback -> Res.string.settings_playback
        SettingsPage.Display -> Res.string.settings_display
        SettingsPage.Torrents -> Res.string.settings_torrents
        SettingsPage.Devices -> Res.string.devices_title
        SettingsPage.Privacy -> Res.string.settings_privacy
        SettingsPage.Account -> Res.string.settings_account
        SettingsPage.About -> Res.string.settings_about
    }

internal fun SettingsPage.icon(): ImageVector =
    when (this) {
        SettingsPage.Addons -> SnIcons.Layers
        SettingsPage.General -> SnIcons.Settings
        SettingsPage.Playback -> SnIcons.Play
        SettingsPage.Display -> SnIcons.Monitor
        SettingsPage.Torrents -> SnIcons.Peers
        SettingsPage.Devices -> SnIcons.Tv
        SettingsPage.Privacy -> SnIcons.Shield
        SettingsPage.Account -> SnIcons.User
        SettingsPage.About -> SnIcons.Info
    }
