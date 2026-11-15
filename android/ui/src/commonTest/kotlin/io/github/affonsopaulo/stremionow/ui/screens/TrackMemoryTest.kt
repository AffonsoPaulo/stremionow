// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.screens

import io.github.affonsopaulo.stremionow.core.TrackChoice
import io.github.affonsopaulo.stremionow.player.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMemoryTest {
    private fun audio(
        id: Int,
        language: String?,
        title: String? = null,
        selected: Boolean = false,
    ) = Track(id, Track.Type.Audio, title, language, codec = null, isSelected = selected, isExternal = false)

    private fun subtitle(
        id: Int,
        language: String?,
        title: String? = null,
        selected: Boolean = false,
        external: Boolean = false,
    ) = Track(id, Track.Type.Subtitle, title, language, codec = null, isSelected = selected, isExternal = external)

    @Test
    fun `the selected tracks make the choice`() {
        val tracks =
            listOf(audio(1, "jpn"), audio(2, "eng", selected = true), subtitle(3, "spa", "Forced", selected = true))
        assertEquals(
            TrackChoice(
                audioLanguage = "eng",
                audioTitle = null,
                subtitlesOff = false,
                subtitleLanguage = "spa",
                subtitleTitle = "Forced",
            ),
            choiceOf(tracks),
        )
        assertTrue(choiceOf(listOf(audio(1, "jpn", selected = true)))!!.subtitlesOff)
        assertNull(choiceOf(emptyList()))
    }

    @Test
    fun `tracks are found again by language in any of its codes, then by title`() {
        val choice =
            TrackChoice(
                audioLanguage = "ja",
                audioTitle = null,
                subtitlesOff = false,
                subtitleLanguage = "de",
                subtitleTitle = "SDH",
            )
        val tracks =
            listOf(
                audio(1, "eng", selected = true),
                audio(2, "jpn"),
                subtitle(3, "ger"),
                subtitle(4, "deu", "SDH"),
            )
        assertEquals(TrackPick(audio = 2, subtitle = SubtitlePick.Track(4)), pickTracks(tracks, choice))
        // Nothing to change when the file already plays them; no match leaves the player's pick.
        assertEquals(TrackPick(null, null), pickTracks(listOf(audio(1, "jpn", selected = true)), choice))
    }

    @Test
    fun `subtitles turned off stay off`() {
        val off =
            TrackChoice(
                audioLanguage = null,
                audioTitle = null,
                subtitlesOff = true,
                subtitleLanguage = null,
                subtitleTitle = null,
            )
        assertEquals(SubtitlePick.Off, pickTracks(listOf(subtitle(1, "eng", selected = true)), off).subtitle)
        assertNull(pickTracks(listOf(subtitle(1, "eng")), off).subtitle)
    }

    @Test
    fun `an external subtitle picked before is waited for`() {
        val choice =
            TrackChoice(
                audioLanguage = null,
                audioTitle = null,
                subtitlesOff = false,
                subtitleLanguage = "pob",
                subtitleTitle = null,
            )
        assertFalse(hasWantedSubtitle(listOf(subtitle(1, "eng")), choice))
        assertTrue(hasWantedSubtitle(listOf(subtitle(2, "pt-BR", external = true)), choice))
    }

    @Test
    fun `language codes of every kind meet`() {
        assertEquals(languageKey("pt"), languageKey("por"))
        assertEquals(languageKey("pt-BR"), languageKey("pob"))
        assertEquals(languageKey("ja"), languageKey("jpn"))
        assertEquals(languageKey("zh_TW"), languageKey("zho"))
        assertEquals("xyz", languageKey("XYZ"))
    }

    @Test
    fun `a torrent stops only when its last player lets it go`() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        TorrentUsers.take(hash)
        TorrentUsers.take(hash)
        assertFalse(TorrentUsers.give(hash))
        assertTrue(TorrentUsers.inUse(hash))
        assertTrue(TorrentUsers.give(hash))
        assertFalse(TorrentUsers.inUse(hash))
    }
}
