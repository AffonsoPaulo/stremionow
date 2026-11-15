// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SharperPosterTest {
    private val small = "https://images.metahub.space/poster/small/tt0903747/img"

    @Test
    fun `wide cards get a sharper copy of metahub posters`() {
        assertEquals(small, sharperPoster(small, 240f))
        assertEquals("https://images.metahub.space/poster/medium/tt0903747/img", sharperPoster(small, 420f))
        assertEquals("https://images.metahub.space/poster/large/tt0903747/img", sharperPoster(small, 640f))
        assertEquals("https://images.metahub.space/poster/large/tt0903747/img", sharperPoster(small, 2000f))
    }

    @Test
    fun `other posters and larger links stay as they are`() {
        val large = "https://images.metahub.space/poster/large/tt0903747/img"
        assertEquals(large, sharperPoster(large, 100f))
        assertEquals(
            "https://image.tmdb.org/t/p/w342/a.jpg",
            sharperPoster("https://image.tmdb.org/t/p/w342/a.jpg", 900f),
        )
    }
}
