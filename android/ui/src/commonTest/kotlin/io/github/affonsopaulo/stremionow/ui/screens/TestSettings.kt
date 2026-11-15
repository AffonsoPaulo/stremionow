// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import io.github.affonsopaulo.stremionow.core.AppSettings
import io.github.affonsopaulo.stremionow.core.DisplaySwitch
import io.github.affonsopaulo.stremionow.core.PlayerType
import io.github.affonsopaulo.stremionow.core.StreamMemoryChoice

/**
 * The core's default settings, for tests to [AppSettings.copy] from. The record has no
 * defaults of its own in Kotlin, so this is the one place to update when it gains a field.
 */
internal fun defaultSettings() =
    AppSettings(
        torrentWifiOnly = false,
        torrentCacheMb = 2048u,
        audioLanguage = "",
        subtitleLanguage = "",
        maxResolution = 0u,
        onboarded = true,
        aniskip = true,
        hdr = DisplaySwitch.ASK,
        matchRefreshRate = false,
        serveToDevices = false,
        serveName = "",
        streamMemory = StreamMemoryChoice.AUTOMATIC,
        streamMemoryAheadMib = 128u,
        streamMemoryBehindMib = 128u,
        subtitleSizePercent = 100u,
        subtitlePosition = 100u,
        subtitleTextColor = 0xFFFFFFFFu,
        subtitleBackgroundColor = 0u,
        subtitleOutlineColor = 0xFF000000u,
        subtitleFileStyling = false,
        autoplayNext = true,
        upNextCountdownSeconds = 10u,
        seekStepSeconds = 10u,
        seekShortStepSeconds = 3u,
        playerType = PlayerType.INTERNAL,
        escExitsFullScreen = true,
        quitOnClose = true,
    )
