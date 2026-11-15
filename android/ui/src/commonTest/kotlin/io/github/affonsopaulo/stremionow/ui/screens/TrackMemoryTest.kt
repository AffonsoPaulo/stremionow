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
    fun `bibliographic and terminology codes are one language`() {
        val pairs =
            listOf(
                "alb" to "sqi",
                "arm" to "hye",
                "baq" to "eus",
                "bur" to "mya",
                "chi" to "zho",
                "cze" to "ces",
                "dut" to "nld",
                "fre" to "fra",
                "geo" to "kat",
                "ger" to "deu",
                "gre" to "ell",
                "ice" to "isl",
                "mac" to "mkd",
                "mao" to "mri",
                "may" to "msa",
                "per" to "fas",
                "rum" to "ron",
                "slo" to "slk",
                "tib" to "bod",
                "wel" to "cym",
            )
        for ((bibliographic, terminology) in pairs) {
            assertEquals(bibliographic, languageKey(terminology), languageKey(bibliographic))
            assertEquals(bibliographic.uppercase(), languageKey(terminology), languageKey(bibliographic.uppercase()))
        }
        assertEquals(languageKey("fr"), languageKey("fre"))
        assertEquals(languageKey("de"), languageKey("ger"))
        assertEquals(languageKey("nl"), languageKey("dut"))
        assertEquals(languageKey("zh-CN"), languageKey("chi"))
        assertEquals(languageKey("cs"), languageKey("cze"))
        assertEquals(languageKey("el"), languageKey("gre"))
        assertEquals(languageKey("fa"), languageKey("per"))
        assertEquals(languageKey("ro"), languageKey("rum"))
        assertEquals(languageKey("sk"), languageKey("slo"))
    }

    @Test
    fun `every language Java knows meets its own codes`() {
        for (code in java.util.Locale.getISOLanguages()) {
            val locale = java.util.Locale.forLanguageTag(code)
            val iso3 = runCatching { locale.isO3Language }.getOrNull()?.ifEmpty { null } ?: continue
            assertEquals(code, languageKey(code), languageKey(iso3))
            assertEquals(code, languageKey(code), languageKey("$code-ZZ"))
            assertEquals(code, languageKey(code), languageKey(displayLanguageTag(iso3)))
        }
    }

    @Test
    fun `old and site codes and names meet the language`() {
        assertEquals(languageKey("sr"), languageKey("scc"))
        assertEquals(languageKey("hr"), languageKey("scr"))
        assertEquals(languageKey("he"), languageKey("iw"))
        assertEquals(languageKey("id"), languageKey("in"))
        assertEquals(languageKey("zh"), languageKey("zht"))
        assertEquals(languageKey("fr"), languageKey("French"))
        assertEquals(languageKey("fr"), languageKey("Français"))
        assertEquals(languageKey("pt"), languageKey("Portuguese"))
        assertEquals(languageKey("de"), languageKey(" german "))
    }

    @Test
    fun `mpv is given every code of the preferred language`() {
        assertEquals(listOf("fr", "fra", "fre"), languageCodes("fr"))
        assertEquals(listOf("pt-BR", "pob", "pt", "por"), languageCodes("pt-BR"))
        assertEquals(listOf("en", "eng"), languageCodes("en"))
        assertEquals(listOf("ger", "de", "deu"), languageCodes("ger"))
    }

    @Test
    fun `tracks are named from any of their codes`() {
        assertEquals("fr", displayLanguageTag("fre"))
        assertEquals("de", displayLanguageTag("ger"))
        assertEquals("sq", displayLanguageTag("alb"))
        assertEquals("pt-BR", displayLanguageTag("pob"))
        assertEquals("pt-BR", displayLanguageTag("pt-BR"))
        assertEquals("ja", displayLanguageTag("jpn"))
        assertEquals("xyz", displayLanguageTag("xyz"))
    }

    @Test
    fun `remembered tracks are found again under the other code`() {
        val choice =
            TrackChoice(
                audioLanguage = "fra",
                audioTitle = null,
                subtitlesOff = false,
                subtitleLanguage = "deu",
                subtitleTitle = null,
            )
        val tracks = listOf(audio(1, "eng", selected = true), audio(2, "fre"), subtitle(3, "ger"))
        assertEquals(TrackPick(audio = 2, subtitle = SubtitlePick.Track(3)), pickTracks(tracks, choice))
    }

    @Test
    fun `a pick changes only its own part of the memory`() {
        val memory =
            TrackChoice(
                audioLanguage = "jpn",
                audioTitle = null,
                subtitlesOff = false,
                subtitleLanguage = "por",
                subtitleTitle = null,
            )
        // This file has no Portuguese subtitles, so English ones show; the viewer picks the
        // English audio. The Portuguese subtitles stay remembered for the next file.
        val tracks = listOf(audio(1, "jpn", selected = true), audio(2, "eng"), subtitle(3, "eng", selected = true))
        assertEquals(memory.copy(audioLanguage = "eng"), withAudioPicked(memory, tracks, 2))
        assertEquals(
            memory.copy(subtitlesOff = true, subtitleLanguage = null),
            withSubtitlePicked(memory, tracks, null),
        )
        assertEquals(memory.copy(subtitleLanguage = "eng"), withSubtitlePicked(memory, tracks, 3))
        // Nothing remembered yet: the pick and what shows besides it.
        assertEquals(
            TrackChoice(
                audioLanguage = "jpn",
                audioTitle = null,
                subtitlesOff = true,
                subtitleLanguage = null,
                subtitleTitle = null,
            ),
            withSubtitlePicked(null, tracks, null),
        )
        assertEquals(memory, withAudioPicked(memory, emptyList(), 2))
    }
}
